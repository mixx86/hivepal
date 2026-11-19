# FAQ and troubleshooting

## Messages only arrive when the app is open

There's no Firebase yet: set it up ([Firebase](firebase.md)), run the installer again and re-pair the
phone. **Status** in the app says whether pushes work.

## A locked phone doesn't ring

1. In the app: **Settings → Calls** shows what's missing (notifications, full-screen calls, battery).
2. The manufacturer's battery saver kills it: [per brand](troubleshooting-oem.md).
3. Test: **Ring me in 10 s** (at the end of the setup, or in Settings), then lock the phone; or
   `hivepal call "test"` with the screen off.

## "refusing plain http://"

The CLI and the app only send keys over `http://` to private addresses (LAN, Tailscale). For a public
server use HTTPS (the server install does it with Caddy).

## An agent never answers

**Agents** shows when it was last seen. Red after a day: its routine stopped. With wake-up emails,
`hivepal bots` shows ✉ next to agents that have an address; check `HIVEPAL_SMTP_URL` and the relay's
logs (`docker compose logs relay | grep wake`). An agent that reads a mailbox folder needs
`hivepal mailbox` running from cron with its key: the files appear only when that runs
([mailbox files](guide/agents.md#mailbox-files)).

## The relay restarts after an update or the server runs out of memory

Build the images on your computer: `scripts/deploy-images.sh user@server`. See
[keeping a small server light](guide/server.md#keeping-a-small-server-light).

## A subscription says "No date found"

The page you picked doesn't show the date as text the app recognises. Tap **Change page** and pick
the page that literally says when it renews (often *Billing*, *Plan* or *Manage subscription*).
If it still fails, open an issue with the service's name and the sentence that shows the date.

## "Continue with Google" in Subscriptions

It opens Google's account chooser in its own window, which closes once you continue. If Google says
instead that the browser or app may not be secure (it does when it wants your Google password typed
there), log in to that service with email and password or an email code.

## Which APK do I install?

| | Built from | Signed with | For |
|---|---|---|---|
| `hivepal-X.Y.Z.apk` in a [release](https://github.com/mixx86/hivepal/releases/latest) | a version tag | the release key | most people |
| `hivepal.apk` in [android-preview](https://github.com/mixx86/hivepal/releases/tag/android-preview) | every change on `main` | the public debug key | trying what's next |

`scripts/build-app.sh` builds like `android-preview`. Within a channel each newer APK installs over the
older one. Between channels it doesn't: the keys differ.

## An update won't install over the app ("App not installed")

The new APK is signed with another key than the installed one (a release over a preview or a
self-built app, or the other way round). Stay on the channel you have, or uninstall the app first
and pair the phone again (`hivepal pair`): your conversations stay on the relay.

## Can I use it on iPhone?

Not yet: there's only the Android app.

## Can I keep using agentring?

Hivepal's relay speaks agentring's protocol plus its own additions, and reads `AGENTRING_*`
settings. The app has the same package name as agentring's, so you can't have both installed unless
you build Hivepal with `-PhivepalApplicationId=your.id`.

## Where's my data?

On your server, in one SQLite file, and on the phone. [What's stored where](concepts/how-it-works.md#whats-stored-where).
