// Caller P2P name server — Cloudflare Worker + D1 (free plan).
//
// POST /verify/start   {number}            -> {code}    first-time check; the app sends `code` to its
//                                                      own number by silent SMS
// POST /verify/confirm {number, code}      -> {token}   the SMS arrived on that phone: number verified
// POST /profile        {name}   (Bearer)   -> {ok}      save my profile name
// GET  /name?number=…           (Bearer)   -> {name}    caller name for a number (used during calls)
// POST /account-sync    {accountKey, devices, calls} (Bearer) -> merged account history

const CODE_TTL_MS = 10 * 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;
const MAX_LOOKUPS_PER_DAY = 300;
const MAX_NAME_LENGTH = 40;

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const route = `${request.method} ${url.pathname}`;
      switch (route) {
        case "POST /verify/start": return await verifyStart(request, env);
        case "POST /verify/confirm": return await verifyConfirm(request, env);
        case "POST /profile": return await saveProfile(request, env);
        case "GET /name": return await lookupName(url, request, env);
        case "POST /account-sync": return await syncAccountHistory(request, env);
        default: return json({ error: "not_found" }, 404);
      }
    } catch (e) {
      return json({ error: "server_error" }, 500);
    }
  },
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Same key for "+91 98765 43210", "919876543210" and "9876543210". */
function numberKey(raw) {
  const digits = String(raw ?? "").replace(/\D/g, "");
  return digits.length >= 10 ? digits.slice(-10) : "";
}

function cleanName(raw) {
  return String(raw ?? "")
    .replace(/[\p{Cc}\p{Cf}]/gu, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_NAME_LENGTH);
}

function randomHex(bytes) {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return [...buf].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function readJson(request) {
  try { return await request.json(); } catch { return {}; }
}

/** The verified user behind the Bearer token, or null. */
async function authUser(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!token) return null;
  const tokenHash = await sha256Hex(token);
  const user = await env.DB.prepare("SELECT number, name FROM users WHERE token_hash = ?")
    .bind(tokenHash).first();
  return user ? { ...user, tokenHash } : null;
}

async function verifyStart(request, env) {
  const { number } = await readJson(request);
  const key = numberKey(number);
  if (!key) return json({ error: "bad_number" }, 400);
  // Tapping Verify again within 10 minutes reuses the same code, so an SMS that arrives late
  // (carrying the earlier code) still matches.
  const existing = await env.DB.prepare("SELECT code, expires_at, attempts FROM verifications WHERE number = ?")
    .bind(key).first();
  if (existing && existing.expires_at > Date.now() && existing.attempts < MAX_CODE_ATTEMPTS) {
    return json({ code: existing.code });
  }
  const code = String(100000 + (crypto.getRandomValues(new Uint32Array(1))[0] % 900000));
  await env.DB.prepare(
    "INSERT INTO verifications (number, code, expires_at, attempts) VALUES (?, ?, ?, 0) " +
    "ON CONFLICT(number) DO UPDATE SET code = excluded.code, expires_at = excluded.expires_at, attempts = 0"
  ).bind(key, code, Date.now() + CODE_TTL_MS).run();
  return json({ code });
}

async function verifyConfirm(request, env) {
  const { number, code } = await readJson(request);
  const key = numberKey(number);
  if (!key || !code) return json({ error: "bad_request" }, 400);
  const row = await env.DB.prepare("SELECT code, expires_at, attempts FROM verifications WHERE number = ?")
    .bind(key).first();
  if (!row || row.expires_at < Date.now() || row.attempts >= MAX_CODE_ATTEMPTS) {
    return json({ error: "expired" }, 400);
  }
  if (String(code) !== row.code) {
    await env.DB.prepare("UPDATE verifications SET attempts = attempts + 1 WHERE number = ?").bind(key).run();
    return json({ error: "wrong_code" }, 400);
  }
  const token = randomHex(32);
  const tokenHash = await sha256Hex(token);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM verifications WHERE number = ?").bind(key),
    // Re-verifying (new phone, Clear data) replaces the old login key but keeps the saved name.
    env.DB.prepare(
      "INSERT INTO users (number, token_hash, name, updated_at) VALUES (?, ?, '', ?) " +
      "ON CONFLICT(number) DO UPDATE SET token_hash = excluded.token_hash, updated_at = excluded.updated_at"
    ).bind(key, tokenHash, Date.now()),
  ]);
  return json({ token });
}

async function saveProfile(request, env) {
  const user = await authUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);
  const name = cleanName((await readJson(request)).name);
  if (!name) return json({ error: "bad_name" }, 400);
  await env.DB.prepare("UPDATE users SET name = ?, updated_at = ? WHERE number = ?")
    .bind(name, Date.now(), user.number).run();
  return json({ ok: true });
}

async function lookupName(url, request, env) {
  const user = await authUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);
  const key = numberKey(url.searchParams.get("number"));
  if (!key) return json({ error: "bad_number" }, 400);

  // Only exact-number lookups, capped per user per day: no browsing or bulk scraping.
  const day = new Date().toISOString().slice(0, 10);
  const counter = await env.DB.prepare(
    "INSERT INTO lookups (token_hash, day, count) VALUES (?, ?, 1) " +
    "ON CONFLICT(token_hash, day) DO UPDATE SET count = count + 1 RETURNING count"
  ).bind(user.tokenHash, day).first();
  if (counter && counter.count > MAX_LOOKUPS_PER_DAY) return json({ error: "limit" }, 429);

  const row = await env.DB.prepare("SELECT name FROM users WHERE number = ? AND name != ''")
    .bind(key).first();
  return row ? json({ name: row.name }) : json({ error: "not_found" }, 404);
}

/**
 * Account history uses a cloud transaction rather than LAN discovery. Devices submit only their
 * own call log; D1 keys each event by the originating device so an OPPO record never turns into
 * a Redmi record (or gets reflected back to OPPO as a new local call).
 */
async function syncAccountHistory(request, env) {
  const user = await authUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);

  const payload = await readJson(request);
  const accountKey = String(payload.accountKey || "").trim();
  if (!/^[a-f0-9]{32,64}$/i.test(accountKey)) return json({ error: "bad_account" }, 400);

  const devices = Array.isArray(payload.devices) ? payload.devices.slice(0, 20) : [];
  const calls = Array.isArray(payload.calls) ? payload.calls.slice(0, 200) : [];
  const now = Date.now();
  const statements = [];

  for (const device of devices) {
    const deviceId = String(device?.deviceId || "").trim().slice(0, 120);
    const deviceName = cleanName(device?.deviceName || "Account phone") || "Account phone";
    if (!deviceId) continue;
    statements.push(env.DB.prepare(
      "INSERT INTO account_devices (account_key, device_id, device_name, updated_at) VALUES (?, ?, ?, ?) " +
      "ON CONFLICT(account_key, device_id) DO UPDATE SET device_name = excluded.device_name, updated_at = excluded.updated_at"
    ).bind(accountKey, deviceId, deviceName, now));
  }

  for (const call of calls) {
    const sourceDeviceId = String(call?.sourceDeviceId || "").trim().slice(0, 120);
    const remoteNumber = numberKey(call?.remoteNumber);
    const timestamp = Number(call?.timestamp);
    if (!sourceDeviceId || !remoteNumber || !Number.isFinite(timestamp) || timestamp <= 0) continue;
    const sourceDeviceName = cleanName(call?.sourceDeviceName || "Linked device") || "Linked device";
    const contactName = cleanName(call?.contactName || "");
    const callType = Number.isInteger(Number(call?.callTypeInt)) ? Number(call.callTypeInt) : 1;
    const duration = Math.max(0, Math.floor(Number(call?.durationSec) || 0));
    const eventId = await sha256Hex([sourceDeviceId, remoteNumber, Math.floor(timestamp), callType, duration].join("|"));
    statements.push(env.DB.prepare(
      "INSERT INTO account_calls (account_key, event_id, source_device_id, source_device_name, remote_number, contact_name, call_type, timestamp, duration_sec, updated_at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(account_key, event_id) DO UPDATE SET " +
      "source_device_name = excluded.source_device_name, contact_name = excluded.contact_name, updated_at = excluded.updated_at"
    ).bind(accountKey, eventId, sourceDeviceId, sourceDeviceName, remoteNumber, contactName, callType, Math.floor(timestamp), duration, now));
  }
  if (statements.length) await env.DB.batch(statements);

  const [deviceRows, callRows] = await Promise.all([
    env.DB.prepare("SELECT device_id, device_name, updated_at FROM account_devices WHERE account_key = ? ORDER BY updated_at DESC LIMIT 20")
      .bind(accountKey).all(),
    env.DB.prepare("SELECT source_device_id, source_device_name, remote_number, contact_name, call_type, timestamp, duration_sec FROM account_calls WHERE account_key = ? ORDER BY timestamp DESC LIMIT 300")
      .bind(accountKey).all(),
  ]);
  return json({
    devices: (deviceRows.results || []).map((d) => ({
      deviceId: d.device_id,
      deviceName: d.device_name,
      verifiedNumbers: [],
      lastSyncTimestamp: d.updated_at,
    })),
    calls: (callRows.results || []).map((c) => ({
      sourceDeviceId: c.source_device_id,
      sourceDeviceName: c.source_device_name,
      remoteNumber: c.remote_number,
      contactName: c.contact_name,
      callTypeInt: c.call_type,
      timestamp: c.timestamp,
      durationSec: c.duration_sec,
    })),
  });
}
