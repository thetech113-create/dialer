// Caller P2P name server — Cloudflare Worker + D1 (free plan).
//
// POST /verify/start   {number}            -> {code}    first-time check; the app sends `code` to its
//                                                      own number by silent SMS
// POST /verify/confirm {number, code}      -> {token}   the SMS arrived on that phone: number verified
// (Removed: /profile, /name and /account-sync. A login proves nothing about the number — /verify/start hands the code
// to whoever asks — so a stranger could set the name shown for any number or read any account's calls.)
// POST /spam-sync       {accountKey, deviceId, reports:[{sender,spam,ham}]} (Bearer) -> the paired phones' reports
// GET  /business?number=+E164&cc=…&region=… (Bearer) -> {name}  Google Maps business name for an unsaved number
//                                                      (needs the PLACES_API_KEY secret)

const CODE_TTL_MS = 10 * 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;
const MAX_LOOKUPS_PER_DAY = 300;
// Places Text Search that returns a phone number is billed as "Enterprise": 1,000 free a month. Stop there, since any
// app user (and anyone who gets a login) can trigger lookups.
const MAX_PLACES_CALLS_PER_MONTH = 1000;
const BUSINESS_MISS_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_NAME_LENGTH = 40;
const MAX_SPAM_REPORTS = 200;
const MAX_SPAM_COUNT = 1000;

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const route = `${request.method} ${url.pathname}`;
      switch (route) {
        case "POST /verify/start": return await verifyStart(request, env);
        case "POST /verify/confirm": return await verifyConfirm(request, env);
        case "POST /spam-sync": return await syncSpamReports(request, env);
        case "GET /business": return await lookupBusiness(url, request, env);
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

/** Only exact-number lookups, capped per user per day: no browsing or bulk scraping. False once over the cap. */
async function countLookup(user, env) {
  const day = new Date().toISOString().slice(0, 10);
  const counter = await env.DB.prepare(
    "INSERT INTO lookups (token_hash, day, count) VALUES (?, ?, 1) " +
    "ON CONFLICT(token_hash, day) DO UPDATE SET count = count + 1 RETURNING count"
  ).bind(user.tokenHash, day).first();
  return !(counter && counter.count > MAX_LOOKUPS_PER_DAY);
}

/**
 * The business Google Maps lists for this exact number, like Google Phone's caller ID. Only a place whose own phone
 * number matches counts (text search also returns places that merely look similar). Google Maps terms allow showing
 * the name but not storing it, so only "no business for this number" is remembered, to save Places calls.
 */
async function lookupBusiness(url, request, env) {
  const user = await authUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);
  if (!env.PLACES_API_KEY) return json({ error: "not_configured" }, 503);
  const number = String(url.searchParams.get("number") ?? "");
  const cc = String(url.searchParams.get("cc") ?? "");
  const region = String(url.searchParams.get("region") ?? "");
  if (!/^\+\d{8,15}$/.test(number) || !/^\d{1,3}$/.test(cc) || !number.startsWith(`+${cc}`) || !/^[A-Z]{2}$/.test(region)) {
    return json({ error: "bad_number" }, 400);
  }
  if (!(await countLookup(user, env))) return json({ error: "limit" }, 429);

  const miss = await env.DB.prepare("SELECT checked_at FROM business_misses WHERE number = ?").bind(number).first();
  if (miss && Date.now() - miss.checked_at < BUSINESS_MISS_TTL_MS) return json({ error: "not_found" }, 404);

  const month = new Date().toISOString().slice(0, 7);
  const usage = await env.DB.prepare(
    "INSERT INTO places_usage (month, count) VALUES (?, 1) " +
    "ON CONFLICT(month) DO UPDATE SET count = count + 1 RETURNING count"
  ).bind(month).first();
  if (usage && usage.count > MAX_PLACES_CALLS_PER_MONTH) return json({ error: "limit" }, 429);

  // Google's advice for phone searches: country code, a space, then the number, with the matching region.
  const response = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-goog-api-key": env.PLACES_API_KEY,
      "x-goog-fieldmask": "places.displayName,places.internationalPhoneNumber",
    },
    body: JSON.stringify({ textQuery: `+${cc} ${number.slice(cc.length + 1)}`, regionCode: region, pageSize: 5 }),
  });
  if (!response.ok) return json({ error: "places_error", status: response.status }, 502);
  const name = businessNameFor(number, (await response.json()).places);
  if (!name) {
    await env.DB.prepare(
      "INSERT INTO business_misses (number, checked_at) VALUES (?, ?) " +
      "ON CONFLICT(number) DO UPDATE SET checked_at = excluded.checked_at"
    ).bind(number, Date.now()).run();
    return json({ error: "not_found" }, 404);
  }
  return json({ name });
}

/** Name of the first place whose phone number is exactly `e164`, or "". */
function businessNameFor(e164, places) {
  const digits = e164.replace(/\D/g, "");
  const match = (Array.isArray(places) ? places : [])
    .find((place) => String(place?.internationalPhoneNumber ?? "").replace(/\D/g, "") === digits);
  return cleanName(match?.displayName?.text);
}

function clampCount(raw) {
  const n = Math.floor(Number(raw));
  return Number.isFinite(n) ? Math.min(MAX_SPAM_COUNT, Math.max(0, n)) : 0;
}

/**
 * Spam reports shared between phones that paired with each other. Each phone writes only its own rows
 * (one per hashed sender id: how many times it said spam and "not spam") and gets back the rows of the OTHER
 * phones under the same private pairing key. No message text and no sender number is ever stored: the sender is a
 * 24-character hash made on the phone, the pairing key a hash of the private link id.
 */
async function syncSpamReports(request, env) {
  const user = await authUser(request, env);
  if (!user) return json({ error: "unauthorized" }, 401);

  const payload = await readJson(request);
  const accountKey = String(payload.accountKey || "").trim().toLowerCase();
  if (!/^[a-f0-9]{32,64}$/.test(accountKey)) return json({ error: "bad_account" }, 400);
  const deviceId = String(payload.deviceId || "").trim().slice(0, 120);
  if (!deviceId) return json({ error: "bad_device" }, 400);

  const reports = Array.isArray(payload.reports) ? payload.reports.slice(0, MAX_SPAM_REPORTS) : [];
  const now = Date.now();
  const statements = [];
  for (const report of reports) {
    const sender = String(report?.sender || "").trim().toLowerCase();
    if (!/^[a-f0-9]{24}$/.test(sender)) continue;
    statements.push(env.DB.prepare(
      "INSERT INTO spam_reports (account_key, device_id, sender, spam, ham, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(account_key, device_id, sender) DO UPDATE SET spam = excluded.spam, ham = excluded.ham, updated_at = excluded.updated_at"
    ).bind(accountKey, deviceId, sender, clampCount(report?.spam), clampCount(report?.ham), now));
  }
  if (statements.length) await env.DB.batch(statements);

  const rows = await env.DB.prepare(
    "SELECT device_id, sender, spam, ham, updated_at FROM spam_reports WHERE account_key = ? AND device_id != ? " +
    "ORDER BY updated_at DESC LIMIT 2000"
  ).bind(accountKey, deviceId).all();
  return json({
    reports: (rows.results || []).map((r) => ({
      deviceId: r.device_id,
      sender: r.sender,
      spam: r.spam,
      ham: r.ham,
      updatedAt: r.updated_at,
    })),
  });
}
