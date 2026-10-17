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
