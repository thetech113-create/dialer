// Caller voicemail server — Cloudflare Worker + D1 (free plan). Port of the Twilio voicemail backend.
//
// Flow:
//   1. Someone calls your SIM. You don't answer / are busy / are unreachable.
//   2. Your carrier forwards the call to your Twilio number (the app turns this on with an MMI code).
//   3. Twilio calls POST /voice/incoming -> greeting + record.
//   4. Twilio calls /voice/recording and /voice/transcription when the audio and text are ready.
//   5. The app lists them with GET /voicemails and downloads the audio.
//
// The app signs in with the same login key as the P2P name server (users table in the shared D1
// database); only OWNER_NUMBER (the phone whose calls are forwarded) can read the voicemails.
//
// Secrets (set once with `npx wrangler secret put NAME`): TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN.

const GREETING =
  "Sorry, I can't take your call right now. Please leave a message after the beep, and press hash when you're done.";
const MAX_LENGTH_SEC = 120;
const VOICE = 'voice="Polly.Aditi" language="en-IN"';

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const path = url.pathname;
      const method = request.method;

      if (method === "POST" && path.startsWith("/voice/")) {
        const params = await readForm(request);
        if (!(await validTwilioSignature(request, url, params, env))) {
          return new Response("forbidden", { status: 403 });
        }
        switch (path) {
          case "/voice/incoming": return await voiceIncoming(params, env);
          case "/voice/done": return twiml(`<Say ${VOICE}>Thank you. Your message has been saved. Goodbye.</Say><Hangup/>`);
          case "/voice/recording": return await voiceRecording(params, env);
          case "/voice/transcription": return await voiceTranscription(params, env);
        }
        return json({ error: "not_found" }, 404);
      }

      if (path === "/") return new Response("Voicemail server is running.");

      // ---- App API (Bearer login key of OWNER_NUMBER) ----
      if (path === "/voicemails" || path.startsWith("/voicemails/")) {
        if (!(await isOwner(request, env))) return json({ error: "unauthorized" }, 401);
        const parts = path.split("/").filter(Boolean); // ["voicemails", id?, action?]
        const id = parts[1] ? decodeURIComponent(parts[1]) : null;
        const action = parts[2] || null;
        if (method === "GET" && !id) return await listVoicemails(env);
        if (method === "GET" && id && action === "audio") return await streamAudio(id, env);
        if (method === "POST" && id && action === "heard") return await markHeard(id, env);
        if (method === "DELETE" && id && !action) return await deleteVoicemail(id, env);
      }
      return json({ error: "not_found" }, 404);
    } catch (e) {
      return json({ error: "server_error" }, 500);
    }
  },
};

// ---------------------------------------------------------------------------------------- helpers

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function twiml(inner) {
  return new Response(`<?xml version="1.0" encoding="UTF-8"?><Response>${inner}</Response>`, {
    headers: { "content-type": "text/xml" },
  });
}

function xmlEscape(text) {
  return String(text).replace(/[<>&"']/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" })[c]);
}

async function readForm(request) {
  const form = await request.formData();
  const params = {};
  for (const [key, value] of form.entries()) params[key] = String(value);
  return params;
}

/** Same key for "+91 98765 43210", "919876543210" and "9876543210". */
function numberKey(raw) {
  const digits = String(raw ?? "").replace(/\D/g, "");
  return digits.length >= 10 ? digits.slice(-10) : "";
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Twilio request check: base64(HMAC-SHA1(auth token, full URL + every POST param name+value, sorted by
 * name)) must equal the X-Twilio-Signature header. Stops anyone else posting fake voicemails.
 */
async function validTwilioSignature(request, url, params, env) {
  const signature = request.headers.get("x-twilio-signature") || "";
  if (!signature || !env.TWILIO_AUTH_TOKEN) return false;
  let data = url.toString();
  for (const key of Object.keys(params).sort()) data += key + params[key];
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(env.TWILIO_AUTH_TOKEN), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  const expected = btoa(String.fromCharCode(...new Uint8Array(mac)));
  return expected === signature;
}

/** The Bearer login key belongs to OWNER_NUMBER in the P2P users table. */
async function isOwner(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const owner = numberKey(env.OWNER_NUMBER);
  if (!token || !owner) return false;
  const user = await env.DB.prepare("SELECT number FROM users WHERE token_hash = ?")
    .bind(await sha256Hex(token)).first();
  return !!user && user.number === owner;
}

function twilioAuthHeader(env) {
  return "Basic " + btoa(`${env.TWILIO_ACCOUNT_SID}:${env.TWILIO_AUTH_TOKEN}`);
}

// ---------------------------------------------------------------------------------- Twilio hooks

async function voiceIncoming(params, env) {
  await env.DB.prepare(
    "INSERT INTO voicemails (id, from_number, caller_name, to_number, created_at, status, heard) " +
    "VALUES (?, ?, ?, ?, ?, 'recording', 0) ON CONFLICT(id) DO NOTHING"
  ).bind(
    params.CallSid,
    params.From || "Unknown",
    params.CallerName || null,
    params.To || null,
    Date.now()
  ).run();

  return twiml(
    `<Say ${VOICE}>${xmlEscape(GREETING)}</Say>` +
    `<Record maxLength="${MAX_LENGTH_SEC}" timeout="5" finishOnKey="#" playBeep="true" action="/voice/done" ` +
    `recordingStatusCallback="/voice/recording" recordingStatusCallbackEvent="completed" ` +
    `transcribe="true" transcribeCallback="/voice/transcription"/>` +
    // Only reached if the caller said nothing.
    `<Say ${VOICE}>No message recorded. Goodbye.</Say><Hangup/>`
  );
}

async function voiceRecording(params, env) {
  if (params.RecordingStatus === "completed") {
    await env.DB.prepare(
      "UPDATE voicemails SET recording_sid = ?, recording_url = ?, duration_sec = ?, status = 'new' WHERE id = ?"
    ).bind(params.RecordingSid, params.RecordingUrl, Number(params.RecordingDuration || 0), params.CallSid).run();
  }
  return new Response(null, { status: 204 });
}

async function voiceTranscription(params, env) {
  await env.DB.prepare("UPDATE voicemails SET transcript = ?, transcript_status = ? WHERE id = ?")
    .bind(
      params.TranscriptionStatus === "completed" ? params.TranscriptionText || null : null,
      params.TranscriptionStatus || null,
      params.CallSid
    ).run();
  return new Response(null, { status: 204 });
}

// ------------------------------------------------------------------------------------- App API

async function listVoicemails(env) {
  const { results } = await env.DB.prepare(
    "SELECT id, from_number, caller_name, created_at, duration_sec, transcript, heard FROM voicemails " +
    "WHERE recording_sid IS NOT NULL AND duration_sec > 0 ORDER BY created_at DESC LIMIT 200"
  ).all();
  return json(results.map((r) => ({
    id: r.id,
    from: r.from_number,
    callerName: r.caller_name,
    createdAt: r.created_at,
    durationSec: r.duration_sec,
    transcript: r.transcript,
    heard: r.heard === 1,
  })));
}

/** Proxied, so the app never needs the Twilio credentials. */
async function streamAudio(id, env) {
  const row = await env.DB.prepare("SELECT recording_url FROM voicemails WHERE id = ?").bind(id).first();
  if (!row || !row.recording_url) return json({ error: "not_found" }, 404);
  const upstream = await fetch(`${row.recording_url}.mp3`, { headers: { Authorization: twilioAuthHeader(env) } });
  if (!upstream.ok) return json({ error: "upstream" }, 502);
  return new Response(upstream.body, { headers: { "content-type": "audio/mpeg" } });
}

async function markHeard(id, env) {
  await env.DB.prepare("UPDATE voicemails SET heard = 1 WHERE id = ?").bind(id).run();
  return json({ ok: true });
}

/** Also removes the recording from Twilio. */
async function deleteVoicemail(id, env) {
  const row = await env.DB.prepare("SELECT recording_sid FROM voicemails WHERE id = ?").bind(id).first();
  if (!row) return json({ error: "not_found" }, 404);
  if (row.recording_sid && env.TWILIO_ACCOUNT_SID) {
    await fetch(
      `https://api.twilio.com/2010-04-01/Accounts/${env.TWILIO_ACCOUNT_SID}/Recordings/${row.recording_sid}.json`,
      { method: "DELETE", headers: { Authorization: twilioAuthHeader(env) } }
    ).catch(() => {});
  }
  await env.DB.prepare("DELETE FROM voicemails WHERE id = ?").bind(id).run();
  return new Response(null, { status: 204 });
}
