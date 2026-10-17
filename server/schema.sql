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
