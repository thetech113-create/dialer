-- Caller voicemail server. Lives in the same D1 database as the P2P name server (caller-p2p),
-- so the app can sign in with its existing login key (users table).

CREATE TABLE IF NOT EXISTS voicemails (
  id                TEXT PRIMARY KEY,      -- Twilio CallSid
  from_number       TEXT NOT NULL,
  caller_name       TEXT,
  to_number         TEXT,
  created_at        INTEGER NOT NULL,      -- epoch ms
  status            TEXT NOT NULL,         -- recording | new
  recording_sid     TEXT,
  recording_url     TEXT,
  duration_sec      INTEGER,
  transcript        TEXT,
  transcript_status TEXT,
  heard             INTEGER NOT NULL DEFAULT 0
);
