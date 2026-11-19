# Configuration

Settings are environment variables. With Docker they go in `deploy/.env` (server) or
`deploy/.env.local` (`--local`); `install.sh` writes the usual ones. Names from before the rename
(`AGENTRING_*`) still work.

## Relay

| Variable | Default | What it does |
|---|---|---|
| `HIVEPAL_DOMAIN` | — | **Compose only.** The server's public name; Caddy gets its certificate. |
| `HIVEPAL_HOST_IP` | — | **Local compose only.** The LAN or Tailscale address the phone uses. |
| `HIVEPAL_API_KEY` | generated | The relay's API key (agent `main`). Generated and stored on first start if empty; must start with `ak_`. |
| `HIVEPAL_AGENT_NAME` | `Agent` | Name of `main`, the voice switchboard, until renamed. |
| `HIVEPAL_PUBLIC_URL` | `http://localhost:8080` | The relay's address as phones see it (set by the compose files). |
| `HIVEPAL_DATA_DIR` | `./data` | Where `relay.db` lives. |
| `HIVEPAL_RING_TIMEOUT_S` | `30` | How long a call rings before it's missed. |
| `HIVEPAL_INSECURE_DEV` | — | `1` allows plain `http://` on a public address. Development only. |
| `HIVEPAL_TRUSTED_PROXIES` | — | Proxies whose `X-Forwarded-*` headers are trusted. |
| `FCM_SERVICE_ACCOUNT_FILE`, `FCM_GOOGLE_SERVICES_FILE` | — | The two [Firebase](../firebase.md) files (paths inside the container: `/secrets/…`). |
| `HIVEPAL_ANDROID_PACKAGE` | `dev.agentring.app` | Only if you built the app with `-PhivepalApplicationId`. |
| `HIVEPAL_GEMINI_API_KEY` → `GEMINI_API_KEY` | — | Voice-note transcripts and text-to-speech. |
| `HIVEPAL_TRANSCRIBE_MODEL`, `HIVEPAL_TTS_MODEL`, `HIVEPAL_TTS_VOICE` | Gemini defaults | Other Gemini models or voice. |
| `HIVEPAL_SMTP_URL` | — | Mail server for wake-up emails: `smtps://user:password@host:465` (URL-encode `@` as `%40`). `./install.sh --email` writes it for you. |
| `HIVEPAL_APP_URL` | the latest GitHub release | Where the pairing page (`/pair`) sends people who don't have the app yet, for a build of your own. |
| `HIVEPAL_WAKE_FROM` | the SMTP user | Sender of wake-up emails. |
| `LIVEKIT_API_KEY`, `LIVEKIT_NODE_IP` | `hivepal`, discovered | LiveKit key id (the secret is generated) and public IP. |

## Daemon / CLI

| Variable | What it does |
|---|---|
| `HIVEPAL_HOME` | Its folder (default `~/.agentring`): `config.yaml`, `.env` with the key, local database, `personas/`, `agents.yaml`. Use one per agent on a shared machine. |
| `HIVEPAL_RELAY_URL`, `HIVEPAL_API_KEY` | Override `hivepal init`. |
| `HIVEPAL_ALLOW_INSECURE_RELAY` | `1` allows `http://` to a public relay (the key would travel unencrypted). |
| `GEMINI_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`, `PHONIC_API_KEY`, `OPENROUTER_API_KEY` | The voice AI's key (in the environment or `HIVEPAL_HOME/.env`). |
| `HIVEPAL_VOICE_PROVIDER`, `HIVEPAL_VOICE_MODEL`, `HIVEPAL_VOICE_NAME`, `HIVEPAL_VOICE_LANGUAGE` | Which voice AI and voice ([voice](../voice.md)). |
| `HIVEPAL_SERVE_FLAGS` | **Compose only.** Flags for `hivepal serve` in the daemon container (`--ai`). |
| `HIVEPAL_MCP_*` | Limits and address of the remote MCP server (`hivepal mcp-http`, [agents](../agents.md#remote-mcp-http)). |

## App (build time)

| Gradle property | What it does |
|---|---|
| `-PversionName=… -PversionCode=…` | Version (`scripts/build-app.sh` sets both). |
| `-PhivepalApplicationId=…` | Build under your own package name (so it can sit next to agentring from Google Play). |
| `HIVEPAL_KEYSTORE_FILE`, `HIVEPAL_KEYSTORE_PASSWORD`, `HIVEPAL_KEY_ALIAS`, `HIVEPAL_KEY_PASSWORD` | Sign with your own key instead of the public debug key ([release](../release.md)). |
