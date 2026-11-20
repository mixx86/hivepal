# Install on a server

A small Linux server with a domain makes Hivepal reachable from anywhere: your agents can run in the
cloud, the phone works on mobile data, and calls ring wherever you are. The smallest plans are enough
(1 CPU, 1–2 GB of RAM): the relay uses about 70 MB, LiveKit about 50 MB.

## What you need

- A Linux server (Debian, Ubuntu, Fedora…) with **Docker** and the Compose plugin.
- Optionally a **domain or subdomain** pointing at it (an `A` record, e.g. `hive.example.com`).
  Without one, the installer offers `<your-ip>.sslip.io`, a name that resolves to the server's public
  IP (from the free [sslip.io](https://sslip.io) service), and Caddy gets it a certificate all the same.
- These **ports open**: TCP 80 and 443 (HTTPS), TCP 7881 and UDP 50000–50100 (call audio).
- Recommended: an API key for a **voice AI** (the voice on calls, voice-note transcripts). We suggest
  [Gemini](https://aistudio.google.com/apikey): it has a free tier and does both. OpenAI, Grok, Phonic,
  OpenRouter or a local model also work for calls ([voice](../voice.md)); transcripts need Gemini.
- Optional: a [Firebase project](../firebase.md). Without it the phone is woken through UnifiedPush
  with the free ntfy app, which the app sets up when you pair it.

## 1. Install

On the server, in one line (no git, nothing built there: it downloads the latest release into
`~/hivepal` and uses its published images):

```bash
curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh
```

Or from a clone, building the images on the server: `git clone https://github.com/mixx86/hivepal &&
cd hivepal && ./install.sh`.

It asks for:

| Question | Example | Notes |
|---|---|---|
| Domain | `hive.example.com` | Caddy gets a Let's Encrypt certificate for it. Offers `<ip>.sslip.io` when the server's IP is public. |
| Switchboard name | `Switchboard` | The voice that answers your calls. |
| Voice AI | `gemini` | Who talks on AI calls: `gemini` (free tier, recommended), `openai`, `grok`, `phonic`, `openrouter`, `local` (your own OpenAI-Realtime-compatible server) or `none`. See [voice](../voice.md). |
| Its API key | `AIza…` | Optional. Without one there are no AI calls. For `local`, the server's address instead (and a key only if it asks for one). |
| Gemini key for transcripts | `AIza…` | Only asked when the voice AI isn't Gemini, and optional: the relay transcribes voice notes with Gemini. |
| How agents hear from you | `mailbox` | `email`, `mailbox`, `both` or `none`: a wake-up email and/or Markdown mailboxes in git ([details](agents.md#how-agents-hear-from-you-and-remember)). |
| Email account | `bots.hive@gmail.com` | With email: the account the relay sends from and its (app) password; the mail server is guessed for common providers. Later: `./install.sh --email`. |
| Mailbox repository | `git@github.com:you/notes.git` | With mailboxes: an external repository the relay pushes to, or empty to keep them in the relay. It prints the relay's deploy key to add with write access, and checks it. |
| Memory | `markdown` | `off`, `markdown`, `sqlite` or `both`: whether bots remember their conversations. All of this: `./install.sh --delivery` later, or the app. |
| Firebase folder | `~/firebase` | A folder with `firebase-service-account.json` and `google-services.json`. Optional: without it the phone uses UnifiedPush (ntfy). |

Then it writes `deploy/.env` (keep it private: it holds the relay's API key), starts everything, configures the daemon inside Docker and shows the QR code to pair the phone.

Answers can come from the environment for an unattended install:

```bash
HIVEPAL_DOMAIN=hive.example.com HIVEPAL_GEMINI_API_KEY=AIza… HIVEPAL_FIREBASE_DIR=~/firebase ./install.sh --yes
# delivery: HIVEPAL_DELIVERY=mailbox HIVEPAL_MAILBOX_REPO=git@github.com:you/notes.git HIVEPAL_MEMORY=both
# another voice AI: HIVEPAL_VOICE_PROVIDER=grok HIVEPAL_VOICE_API_KEY=xai-… (local: HIVEPAL_VOICE_URL=ws://…)
# or through the one-line install: … | HIVEPAL_DOMAIN=… sh -s -- --yes
```

## 2. The app and the phone

The installer ends with a QR code; to show one again, on the server:

```bash
cd deploy && docker compose exec daemon hivepal pair
```

Scan it with the **phone's camera**: the page it opens offers **Download the app**
(`hivepal-X.Y.Z.apk` from the [latest release](https://github.com/mixx86/hivepal/releases/latest);
Android asks once to allow installs from your browser) and then **Open Hivepal**, which pairs it. The
app walks you through permissions, push and how agents hear from you. (Building the app yourself:
[quickstart](quickstart.md#build-the-app-yourself).)

## The web panel

Open `https://hive.example.com/admin` and paste the relay's API key (`HIVEPAL_API_KEY` in
`deploy/.env`; it stays in that browser tab only). It shows whether each piece works (push, calls,
voice, wake-up emails), the paired phones and when they were last seen, and the agents. From there you
can **pair a phone** (a QR for the phone's camera), **add an agent** and copy its setup for the CLI,
Claude Code and the other harnesses, or HTTP, and **send a test message or ring your phone**. The page
itself holds nothing: everything goes through the same API as the CLI, with your key.

## 3. Your agents

Agents talk to `https://hive.example.com` with their own keys:

```bash
docker compose exec daemon hivepal bots add nubi Nubi   # → Nubi's key
```

On the machine where Nubi runs: `hivepal init --relay https://hive.example.com --api-key ak_…`.
See [Connect your agents](agents.md).

## Updating

```bash
curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh   # the one-line install: the latest release, same configuration
git pull && ./install.sh          # a clone: keeps deploy/.env, rebuilds and restarts
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

The copy holds the relay's keys and your conversations: keep it private (the script makes it readable
only by you). The wake-up email account's password is not readable from it: the relay encrypts it with
a key in its own file, `secret_key`, in the same volume. Restored without that file, the relay still
works and only asks for the email account again (app → Settings, or the web panel).

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
