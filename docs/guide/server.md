# Install on a server

A small Linux server with a domain makes Hivepal reachable from anywhere: your agents can run in the
cloud, the phone works on mobile data, and calls ring wherever you are. The smallest plans are enough
(1 CPU, 1–2 GB of RAM): the relay uses about 70 MB, LiveKit about 50 MB.

## What you need

- A Linux server (Debian, Ubuntu, Fedora…) with **Docker** and the Compose plugin.
- A **domain or subdomain** pointing at it (an `A` record, e.g. `hive.example.com`). Many VPS providers
  give you a hostname that works as is.
- These **ports open**: TCP 80 and 443 (HTTPS), TCP 7881 and UDP 50000–50100 (call audio).
- Recommended: a [Firebase project](../firebase.md) (pushes and ringing when the app is closed) and a
  [Gemini API key](https://aistudio.google.com/apikey) (voice AI, voice-note transcripts).

## 1. Install

On the server:

```bash
git clone https://github.com/mixx86/hivepal && cd hivepal
./install.sh
```

It asks for:

| Question | Example | Notes |
|---|---|---|
| Domain | `hive.example.com` | Caddy gets a Let's Encrypt certificate for it. |
| Switchboard name | `Switchboard` | The voice that answers your calls. |
| Gemini key | `AIza…` | Optional. Without it there are no AI calls and no transcripts. |
| Firebase folder | `~/firebase` | A folder with `firebase-service-account.json` and `google-services.json`. Optional, but without it the phone only gets messages while the app is open. |

Then it writes `deploy/.env` (keep it private: it holds the relay's API key), builds and starts
everything, configures the daemon inside Docker and shows the QR code to pair the phone.

Answers can come from the environment for an unattended install:

```bash
HIVEPAL_DOMAIN=hive.example.com HIVEPAL_GEMINI_API_KEY=AIza… HIVEPAL_FIREBASE_DIR=~/firebase ./install.sh --yes
```

## 2. The app and the phone

Build the app on your computer (`./scripts/build-app.sh --install`, phone on USB) or install an APK
from the [releases](https://github.com/mixx86/hivepal/releases). Then, on the server:

```bash
cd deploy && docker compose exec daemon hivepal pair
```

## 3. Your agents

Agents talk to `https://hive.example.com` with their own keys:

```bash
docker compose exec daemon hivepal bots add nubi Nubi   # → Nubi's key
```

On the machine where Nubi runs: `hivepal init --relay https://hive.example.com --api-key ak_…`.
See [Connect your agents](agents.md).

## Updating

```bash
git pull && ./install.sh          # keeps deploy/.env, rebuilds and restarts
```

On a small server, building the images there can eat its memory. Build them on your own computer
and send them instead:

```bash
scripts/deploy-images.sh user@hive.example.com     # builds locally, loads them there, restarts
```

## Backups

Everything lives in one SQLite file in the `relay-data` volume. `scripts/backup-relay.sh` takes a
consistent copy while it runs and keeps the last seven:

```bash
scripts/backup-relay.sh ~/backups          # once
crontab -e                                  # daily at 04:30:
# 30 4 * * *  /home/you/hivepal/scripts/backup-relay.sh
```

## Keeping a small server light

- Build images elsewhere (`scripts/deploy-images.sh`), then `docker image prune -f` and
  `docker builder prune -af` once in a while.
- Cap the logs: `/etc/docker/daemon.json` with `{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}}`
  and `SystemMaxUse=50M` for journald.
- A 1–2 GB swap file avoids out-of-memory kills during updates.

## Optional settings

| Setting in `deploy/.env` | What it does |
|---|---|
| `HIVEPAL_SMTP_URL` | A mail server for [wake-up emails](agents.md#wake-up-emails), e.g. `smtps://you%40gmail.com:APP_PASSWORD@smtp.gmail.com:465`. |
| `HIVEPAL_SERVE_FLAGS` | Flags for the switchboard (`--ai` to answer with a voice AI; set by the installer when you give a Gemini key). |
| `HIVEPAL_ANDROID_PACKAGE` | Only if you built the app under your own id. |
| `LIVEKIT_NODE_IP` | The server's public IP, if LiveKit can't discover it. |

The full list: [Configuration](../reference/configuration.md). If something doesn't work, see
[Troubleshooting](../faq.md).
