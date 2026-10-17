# Caller voicemail server (Cloudflare Worker)

Unanswered calls are forwarded by the carrier to the Twilio number (+1 510 491 0090). Twilio plays
a greeting, records the message and tells this Worker; the app's Voicemail tab lists and plays them.

- Address: https://caller-voicemail.vishwacaller.workers.dev
- Database: the same D1 database as the P2P name server (`caller-p2p`), table `voicemails` (schema.sql).
- Login: the app's P2P login key. Only `OWNER_NUMBER` (wrangler.toml) can read the voicemails.

## One-time setup

```bash
npx wrangler secret put TWILIO_ACCOUNT_SID
npx wrangler secret put TWILIO_AUTH_TOKEN
```

Twilio Console → Phone Numbers → Active numbers → +1 510 491 0090 → Voice configuration →
"A call comes in" → Webhook → `https://caller-voicemail.vishwacaller.workers.dev/voice/incoming`, HTTP POST → Save.

## Deploy changes

```bash
npx wrangler deploy
```
