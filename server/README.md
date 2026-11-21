# Caller P2P name server (free)

Cloudflare Worker + D1 database on the free plan (no card). Stores each verified user's
profile name and returns it to other app users during calls.

## Deploy (one time)

Needs Node.js (https://nodejs.org, LTS). In this `server` folder:

```
npx wrangler login
npx wrangler d1 create caller-p2p
```

Copy the `database_id` it prints into `wrangler.toml`, then:

```
npx wrangler d1 execute caller-p2p --remote --file=schema.sql
npx wrangler deploy
```

`deploy` prints the address, e.g. `https://caller-p2p.<your-name>.workers.dev`.
Put it in the app: `P2P_SERVER_URL` in `app/src/main/java/com/caller/P2PServerClient.kt`.

## Update later

Edit `src/index.js`, then `npx wrangler deploy` again.

## Multi-device Recents

The Worker also provides the cloud endpoint used by the app's multi-device Recents. It works when
phones are on different networks. After updating an already deployed Worker, run the schema command
above once to create the `account_devices` and `account_calls` tables, then deploy the Worker.

## Shared spam reports

`POST /spam-sync` lets phones that are paired with each other share which senders they reported as spam
(hashed sender ids and counts only, never a number or a message). It needs the `spam_reports` table: run the schema
command above once (every statement is `IF NOT EXISTS`), then deploy. Until then the app gets a 404 and just waits.

## Business Caller ID (Google Maps names)

`GET /business` gives the app the Google Maps name of a business calling from an unsaved number, like Google Phone's
caller ID. It uses the Places API (New) with your own key; this one feature needs a Google Cloud billing account.

1. In Google Cloud Console: make a project, attach billing, enable **Places API (New)**, create an API key and limit it
   to that API.
2. In this folder: `npx wrangler secret put PLACES_API_KEY` and paste the key.
3. Run the schema command above once (adds `business_misses` and `places_usage`), then `npx wrangler deploy`.

Each lookup is billed as Places Text Search Enterprise (it returns the phone number, so the server can check it is
the same number). The Worker stops at 1,000 Places calls a month, Google's free amount for that tier
(`MAX_PLACES_CALLS_PER_MONTH`), and remembers numbers with no business for 30 days. Found names are not stored:
Google Maps terms only allow showing them. Without the key the app gets a 503 and shows the number as before.
