# FAQ and troubleshooting

## Messages only arrive when the app is open

There's no Firebase yet: set it up ([Firebase](firebase.md)), run the installer again and re-pair the
phone. **Status** in the app says whether pushes work.

## A locked phone doesn't ring

1. In the app: **Settings → Calls** shows what's missing (notifications, full-screen calls, battery).
2. The manufacturer's battery saver kills it: [per brand](troubleshooting-oem.md).
3. Test: `hivepal call "test"` with the screen off.

## "refusing plain http://"

The CLI and the app only send keys over `http://` to private addresses (LAN, Tailscale). For a public
server use HTTPS (the server install does it with Caddy).

## An agent never answers

**Agents** shows when it was last seen. Red after a day: its routine stopped. With wake-up emails,
`hivepal bots` shows ✉ next to agents that have an address; check `HIVEPAL_SMTP_URL` and the relay's
logs (`docker compose logs relay | grep wake`).

## The relay restarts after an update or the server runs out of memory

Build the images on your computer: `scripts/deploy-images.sh user@server`. See
[keeping a small server light](guide/server.md#keeping-a-small-server-light).

## A subscription says "No date found"

The page you picked doesn't show the date as text the app recognises. Tap **Change page** and pick
the page that literally says when it renews (often *Billing*, *Plan* or *Manage subscription*).
If it still fails, open an issue with the service's name and the sentence that shows the date.

## Can I use it on iPhone?

Not yet: there's only the Android app.

## Can I keep using agentring?

Hivepal's relay speaks agentring's protocol plus its own additions, and reads `AGENTRING_*`
settings. The app has the same package name as agentring's, so you can't have both installed unless
you build Hivepal with `-PhivepalApplicationId=your.id`.

## Where's my data?

On your server, in one SQLite file, and on the phone. [What's stored where](concepts/how-it-works.md#whats-stored-where).
