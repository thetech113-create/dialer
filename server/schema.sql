-- Caller P2P name server (Cloudflare D1). Numbers are stored as their last 10 digits.

-- One-time codes for the first-time number check (silent SMS to the phone's own number).
CREATE TABLE IF NOT EXISTS verifications (
  number     TEXT PRIMARY KEY,
  code       TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0
);

-- Verified app users: login key (stored only as a SHA-256 hash) and their profile name.
CREATE TABLE IF NOT EXISTS users (
  number     TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  name       TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);

-- Per-user daily lookup counter (stops bulk scraping of names).
CREATE TABLE IF NOT EXISTS lookups (
  token_hash TEXT NOT NULL,
  day        TEXT NOT NULL,
  count      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (token_hash, day)
);

-- Cloud-backed multi-device Recents. Calls are keyed by their original device and event identity,
-- never by the device that happens to download them.
CREATE TABLE IF NOT EXISTS account_devices (
  account_key TEXT NOT NULL,
  device_id TEXT NOT NULL,
  device_name TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (account_key, device_id)
);

CREATE TABLE IF NOT EXISTS account_calls (
  account_key TEXT NOT NULL,
  event_id TEXT NOT NULL,
  source_device_id TEXT NOT NULL,
  source_device_name TEXT NOT NULL,
  remote_number TEXT NOT NULL,
  contact_name TEXT NOT NULL DEFAULT '',
  call_type INTEGER NOT NULL,
  timestamp INTEGER NOT NULL,
  duration_sec INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (account_key, event_id)
);

CREATE INDEX IF NOT EXISTS idx_account_calls_recent
  ON account_calls (account_key, timestamp DESC);
