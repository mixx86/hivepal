# Hivepal — read this first

**Hivepal** (`mixx86/hivepal`) is a fork of [agentring](https://github.com/mutonby/agentring)
(Apache-2.0, by mutonby) that turns its one-agent design into a hive of agents. This file has three
parts: **this fork** (what is true here, now), **working agreement**, and **inherited from agentring**
(upstream's design, layout and gotchas, still valid for the code it describes, plus upstream's own
status for reference). When the two disagree, the fork's part wins.

## This fork

- **Renamed from agentring**, except what must stay: the Android package `dev.agentring.*` (an
  existing install keeps its Firebase registration, widget and shortcuts; `-PhivepalApplicationId=`
  builds under another id), the app's saved-state names (`agentring_state`, …), the data dirs
  `~/.agentring*` and `agentring.db`, the `agentring` CLI alias, and the credit to upstream.
  `AGENTRING_*` settings (environment and `.env` files) are still read as `HIVEPAL_*`.
- **Multi-agent**: agents with their own `ak_` keys, conversations `agent:<id>` / `group:<id>` /
  `bots:<a>.<b>`, quick replies (`options`), attachments (`/v1/files`), reminders sent by the relay
  (`/v1/reminders`, with `insist_s`/`call`), wake-up emails (`/v1/agents/{id}/wake`), subscriptions
  (`/v1/subscriptions`), calls to a specific agent with a voice persona, `last_seen_at` per agent.
  Design and API: `docs/multiagent.md`; contract: `protocol/relay.openapi.yaml`.
- **CLI**: `hivepal` (alias `agentring`): `bots`, `groups`, `msg --group/--to/-o/-f/--voice`, `remind`,
  `reminders`, `replies`, `subs`. Version in `daemon/pyproject.toml`.
- **App**: Hivepal, always dark ("electric blue"), English by default with Spanish (`AppLanguage`),
  drawer with Chats, Calls, Agents, Reminders, Subscriptions, Status, Settings; widget. Subscriptions
  are read in the app (`subs/`: in-app WebView login, daily `SubsWorker`, `BillingParser`).
- **Tests**: relay `npm test`, daemon `uv run --extra voice pytest -q` (needs `npm run build` in
  `relay/` first), app `./gradlew :core:test :app:testReleaseUnitTest :app:assembleRelease`. Don't
  run the instrumented tests on someone's real phone: they re-pair it.
- **Install / deploy**: `./install.sh` (server or `--local`), `scripts/build-app.sh`,
  `scripts/deploy-images.sh <ssh host>` builds images locally and loads them on a small server.
- **Docs and website**: user guides (`docs/guide/`), "how it works" (`docs/concepts/`), CLI and
  configuration reference (`docs/reference/`) and the design docs live in `docs/`. They are published
  at https://mixx86.github.io/hivepal-web/ by the separate repo `mixx86/hivepal-web` (checked out
  next to this one as `../hivepal-site`): its `sync.py` copies `docs/`, `./deploy.sh` publishes.
  Diagrams are Mermaid; keep them narrow (the site's column is ~900 px; prefer `TB`, short labels).

### Status (as of 2026-11-19)

- Public repo since 2026-11-18, secret scanning and push protection on. No rulesets. First release
  **v0.4.0** on 2026-11-19 (signed APK, wheel + sdist, public GHCR images `hivepal-relay` /
  `hivepal-daemon`; PyPI off until `HIVEPAL_PUBLISH_PYPI=true`). agentring's tags were removed on
  2026-11-18; the fork publishes its own `android-preview` pre-release from `main`.
- **Release signing key** created on 2026-11-19 (certificate fingerprint in SECURITY.md; the
  maintainer keeps the keystore offline). `release.yml` reads it from the `HIVEPAL_KEYSTORE_*`
  Actions secrets. `android-preview` stays signed with the committed debug key. Not on Google Play,
  no hosted service; the hub mode code (`relay/src/hub/`, `docs/cloud.md`) is inherited and unused.
- CI: `CI` (relay + daemon tests, compose smoke) and `Android` (APK + emulator e2e, which talks in
  a `group:ci` because `main` has no chat in the app) pass on `main`.
- Upstream: the `upstream` remote is `mutonby/agentring`, fetched with `--no-tags`. The fork's
  history was rewritten (rename) and **shares no commit with upstream**: bring upstream changes over
  by cherry-picking or porting, never by merging.

## Working agreement with the user (important)

- The user writes in **Spanish**; answer in Spanish. **Code, comments, docs and README in English.**
- **Ask before deviating** from the design in `docs/architecture.md` / `docs/protocol.md` /
  `docs/multiagent.md`. Record accepted deviations there.
- The protocol contract is `protocol/relay.openapi.yaml` (single source of truth). Change it first,
  then the implementations, and keep `protocol/examples/` fixtures valid.
- Develop on `main`. Commit with clear messages; don't open PRs unless asked. Only `main` pushes
  replace the `android-preview` APK.
- **Dependency policy** (supply-chain care): prefer releases at least ~2 weeks old, pin exact
  versions, check the lockfiles against OSV before pushing. Apply age limits per package
  (`uv lock --exclude-newer-package`), never globally: a global `--exclude-newer` once downgraded
  certifi to a version with compromised CA roots.

---

# Inherited from agentring

Upstream's notes, kept because they describe the code this fork is built on. "Upstream's
maintainer" below is agentring's author, not this fork's user.

Upstream's goal: let any AI agent (Claude Code, OpenClaw, n8n, scripts) **call the user's phone over
VoIP and message it**, and let the user call the agent back. The phone must ring like a real call
when locked. No PSTN, no numbers, no Twilio. Its first scope was one user, one agent, Android only,
self-hosted, no accounts (this fork adds many agents).

## Upstream status (when the fork was taken)

Upstream worked milestone by milestone (M1→M5). Tests on real hardware were done by upstream's
maintainer on a Pixel 6; nothing here has been re-verified on this fork's phones unless the fork's
status says so.

| Milestone | Scope | Upstream status |
|---|---|---|
| **M1** | relay + pairing + messages + push | **Done**; verified on a Pixel 6 (Android 16), FCM incl. forced Doze (~1.5 s). |
| **M2** | phone rings locked / Doze in < 5 s with native call UI; audio | Works on the Pixel 6 (locked + Doze → call screen in 1.6 s, in the room ~0.5 s after answer). Group calls + "join the call" while active (`POST /v1/calls/{id}/join`) not yet tested on a real phone. |
| **M3** | voice AI on calls; `consult_agent` | Generic `hivepal.voice` library (VoiceAgent, pluggable `RealtimeBackend`, Python/MCP tools, hooks), `call --ai`, `serve --ai` answering the phone (docs/voice.md). Providers: Gemini Live (default), OpenAI Realtime, Grok Voice, Phonic, `openrouter` (experimental: speech-to-text → any OpenRouter chat model → text-to-speech) and `local` (experimental: any OpenAI-Realtime-compatible server at `HIVEPAL_VOICE_URL`) via `--provider` / `HIVEPAL_VOICE_PROVIDER`; `hivepal voice bench` compares them. `Prompts.before_tools` took Gemini's first word on a slow tool from 5.1 s to 1.5 s. Live e2e passes with Gemini, Grok and Phonic; OpenAI only against fake servers. `consult_agent` + `agents.yaml` done (docs/agents.md). TODO: OpenAI live run, transcripts in the store, real-phone test. |
| **M4** | call the agent from the app; missed/declined fallbacks | App side done ("Call agent" → `POST /v1/calls`, cancel while ringing, offline / busy / declined / missed messages), not tested on a real phone. Missed → `msg.new` fallback for AI calls. Android Auto: "Call <agent>" car screen in the `:auto` module, in debug builds and in release only with `-PhivepalAndroidAuto=true` (docs/android-auto.md); checked on the Desktop Head Unit, not in a real car. |
| **M5** | MCP server, skill, examples, docs, quickstart | `hivepal mcp` (stdio, no SDK), `skills/hivepal`, examples (claude-code, n8n, OpenClaw), per-harness recipes (docs/agents.md), `hivepal connect openclaw\|hermes`, a setup guide inside the skill (`skills/hivepal/references/setup.md`, packaged copy in `daemon/src/hivepal/skill/`, a test keeps them equal), release workflow (signed APK, PyPI, GHCR; docs/release.md). MCP servers accept protocol 2025-11-25. |
| **Cloud** | hosted service (docs/cloud.md) | Relay hub mode (`relay/src/hub/`, tenants under `/t/{id}`, admin API, plan limits → `402 quota_exceeded`), `hivepal mcp-http` (remote MCP), app link to a cloud (`-PhivepalCloudUrl`). Upstream runs it at agentring.io and publishes its app on Google Play; **neither applies to this fork**. |

Agreed direction for voice (M3): keep it agnostic with two plug points — the voice backend (Gemini
Live, OpenAI Realtime, Grok Voice, Phonic, or a local OpenAI-Realtime-compatible server via
`OpenAIRealtime(url=…)`) and the tools (any local MCP server plus the built-in `consult_agent`). Over
the phone, only an allowlist of tools.

## Repository layout

```
server.json     MCP Registry entry for upstream's hosted MCP server (docs/release.md#directories)
plugins/        hivepal/ = Grok Build plugin (hosted MCP + a copy of skills/hivepal; a test keeps it equal)
protocol/       relay.openapi.yaml (REST + event schemas), examples/ (event fixtures, push signature vector)
relay/          TypeScript, Fastify 5, node:sqlite (no native deps), jose; FCM HTTP v1 without firebase-admin
daemon/         Python ≥3.11 + uv; `hivepal` CLI (typer), local SQLite mirror synced from the relay
app-android/    :core = pure Kotlin/JVM (protocol models, PushVerifier, RelayApi, RelaySocket)
                :app  = Compose/Material 3 app, package dev.agentring.app, minSdk 26
                :auto = Android Auto car screen (manifest-only module)
deploy/         docker-compose.yml (caddy + relay + LiveKit), docker-compose.local.yml (own computer, LAN/Tailscale, no TLS),
                docker-compose.proxy.yml (behind an existing proxy), Caddyfile, .env.example, .env.local.example
scripts/        build-app.sh, deploy-images.sh, backup-relay.sh, ci-emulator-e2e.sh, device-test.sh (install.sh is at the root)
skills/         hivepal/ = the agent skill (copied into the daemon package and the Grok plugin)
docs/           guides, concepts, reference, design docs (architecture, protocol, multiagent, voice…)
.github/        workflows/ci.yml (relay+daemon tests, compose smoke), workflows/android.yml (APK + emulator e2e + android-preview), workflows/release.yml (v* tags)
```

`android-preview` is a debug-key test build. Once a real release key exists, release APKs signed with
it will not install over it (uninstall first).

## Build and test

```bash
cd relay  && npm ci && npm run typecheck && npm test && npm run build   # Node >= 22.13
cd daemon && uv run --extra voice pytest -q   # needs relay/dist (e2e against the real relay)
cd app-android && ./gradlew :core:test :app:assembleDebug
cd app-android && ./gradlew -PcoreOnly :core:test   # when Google's Maven is unreachable
```

CI (`.github/workflows/android.yml`) builds the APK, starts a relay, boots an API 34 emulator, runs
`M1EndToEndTest` (pair via UI → WS message → read receipt → reply in app → signed push → notification
inline reply), checks `hivepal replies`, uploads screenshots, and replaces the `android-preview`
pre-release with `hivepal.apk`.

Local real-phone test: `scripts/device-test.sh [apk]` or `--instrumented`.

## Key design decisions (accepted upstream, kept by the fork)

- Commands are REST; events flow over a resumable WebSocket (`/v1/ws?after_seq=`) from a persisted
  event log with a global `seq`; FCM carries only what must wake the phone.
- LiveKit tokens are **not** in `call.invite`; the callee gets them from `POST /v1/calls/{id}/accept`
  (or `/join`), each phone its own. The caller gets its own in the `POST /v1/calls` response.
- FCM data payloads are signed: `sig = HMAC-SHA256(push_secret, event_json)` plus `relay_id`; the app
  rejects anything else.
- **Firebase is configured at runtime**: the relay reads the user's `google-services.json` and returns
  `push_config` in the pair response, so one prebuilt APK works with anyone's Firebase project.
- Extra call state `canceled` and informational event `call.ringing`; one call at a time
  (`409 busy`); group calls: every phone rings, any may answer while it rings, and any paired phone
  not on an active call may join it later (`POST /v1/calls/{id}/join`; `call.accept` is pushed at
  normal priority so the other phones can show "Join"). Targeted calls: the agent may pass
  `device_ids` to ring only those phones; the call is then private to them (the relay filters its
  events, pushes and REST views for every other phone).
- Auth: the relay's own `ak_…` API key (generated on first start, stored in `DATA_DIR/api_key`) is
  the agent `main`; each other agent has its own `ak_` key (this fork). Devices use a long-lived HS256
  `device_jwt` checked against the DB on every request (immediate revocation, WS closed with 4401).
- The app accepts `http://` relays only on private/LAN/localhost hosts (`RelayUrlPolicy`); everything
  else must be https.
- Debug keystore `app-android/app/debug.keystore` is committed on purpose so CI builds install over
  each other. Release builds take the real key from `HIVEPAL_KEYSTORE_*` (docs/release.md) and fall
  back to the debug key locally.
- `consult_agent`'s command backend takes an **argv list**, never a shell string (the task text comes
  from the voice model).
- One hostname: Caddy routes `/v1/*` → relay and `/rtc*`, `/twirp*` → LiveKit.

## Environment gotchas

- **Cloud sessions** (claude.ai/code) cannot reach `dl.google.com` / `maven.google.com`, so the
  Android app can't be built there. Use the `-PcoreOnly` build and let GitHub Actions build/test the
  app (read logs with `gh run view --log-failed`; release assets via
  `gh api -H "Accept: application/octet-stream" repos/mixx86/hivepal/releases/assets/<id>`). A cloud
  session also cannot see a USB phone; for that the user runs Claude Code on their own computer.
- Docker builds inside the cloud sandbox can't reach npm; the compose stack is verified by the
  `deploy-smoke` CI job instead.
- `ubuntu-latest` runners need disk freed for the emulator (already in the workflow).
- WebRTC ignores loopback candidates, so `adb reverse` can't carry call media: for local call tests,
  run LiveKit in Docker with `node_ip` = the computer's LAN IP (UDP 50000-50200 open) and let the
  phone reach the relay and LiveKit signalling via `adb reverse tcp:8080` and `tcp:7880` (wireless adb
  works). Upstream did this on macOS, where the firewall blocks the Homebrew `livekit-server` binary;
  `build-local/` (git-excluded) held that setup.
- Espresso < 3.7 crashes on Android 16 (`InputManager.getInstance`); the instrumented test needs the
  phone unlocked.
- Core-Telecom `disconnect()` only accepts LOCAL/REMOTE/MISSED/REJECTED; any other cause throws and
  leaves a ghost RINGING call that blocks later calls (`CallException 5`).
  `TelecomBridge.platformCause` maps them.

## Calls: the original M2 spec

- Relay: calls endpoints, ring timer (30 s → `call.missed`), LiveKit room + per-call tokens (TTL
  120 s, one room), LiveKit webhook, FCM push of `call.invite` (TTL = until `expires_at`) and of
  accept/decline/end/missed for ringing calls.
- Daemon: `hivepal call "reason" [--urgent] [--wait]`, voice worker joining the room.
- App: self-managed `ConnectionService` + `PhoneAccount` (`MANAGE_OWN_CALLS`), CallStyle
  notification with full-screen intent, Android 14+ FSI declaration, urgent priority → alarm stream
  + bypass DnD when policy access granted, LiveKit Android SDK audio, active-call screen (mute,
  speaker, hang up), onboarding (notifications, mic, FSI, DnD, battery optimisation with
  Xiaomi/Samsung/Huawei instructions, test call).
- Acceptance: phone locked, screen off, 10 min in Doze → `hivepal call "test"` rings in < 5 s with
  native call UI, answer, audio works.
