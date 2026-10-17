// Caller P2P name server — Cloudflare Worker + D1 (free plan).
//
// POST /verify/start   {number}            -> {code}    first-time check; the app sends `code` to its
//                                                      own number by silent SMS
// POST /verify/confirm {number, code}      -> {token}   the SMS arrived on that phone: number verified
// POST /profile        {name}   (Bearer)   -> {ok}      save my profile name
// GET  /name?number=…           (Bearer)   -> {name}    caller name for a number (used during calls)

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
