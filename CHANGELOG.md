# Changelog

What changes for people who run Hivepal, release by release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/): a patch release (`0.4.1`) fixes things, a minor
release (`0.5.0`) adds features; before 1.0 a minor release may also change the protocol.

Every pull request that changes behaviour adds a line under **Unreleased**;
`scripts/release.sh` turns that section into the next version (see [Releasing](https://mixx86.github.io/hivepal-web/release/)).

## [Unreleased]

### Added

- "Typing…": when an agent reads your new message (`hivepal replies`) the app shows it typing until it
  answers (a minute at most), in the chat list, the chat's header with a "…" bubble, and Agents. Agents
  can also say so for longer work: `hivepal typing`, the `typing` MCP tool, or `POST /v1/typing`. Live
  only: never stored or pushed.
- App: motion in the setup: the welcome shows the Hivepal logo assembling itself cell by cell, each page's
  content arrives in order, a picked answer responds, and finished steps (the agents step's summary, the
  last page) draw their check with a few sparks. Android's "Remove animations" shows it all at once.

### Changed

- App: the agents step asks "are your agents always on?" (or do some only start now and then), and says
  what the email is for: a doorbell that starts an agent at once instead of at its next scheduled run.

## [0.7.1] - 2026-11-20

### Changed

- App: the setup's agents step, now **Your agents: notices and memory**, is a few short questions, one
  per page (does an agent read Hivepal itself or need a notice; email and/or mailboxes; where the mailboxes live; memory), with
  a summary at the end; every option is still there. The server field only appears for providers it
  doesn't know, each check shows its result right under its button, and memory is one choice (no, notes,
  searchable, both) in the setup and in Settings.
- App: the main buttons (new group, send) glow still instead of pulsing; only what needs attention moves.
- App: language names are in the app's language (English · Spanish, Inglés · Español).
- Docs and installer: installing the app is the release APK (scan the pairing QR with the phone's camera), no
  cable or Android SDK; building it yourself is the alternative.

### Fixed

- Relay: a mail server that can't be reached says so ("Couldn't connect to host:port") instead of an empty
  or `ESOCKET` message; a repository check that works clears the previous error, and so does choosing
  another repository; git runs in English whatever the host's language (the relay reads its messages).
- Relay: the wake-up email's text is in English, like the test one.
- App: an agent's `git clone` line uses the relay's own scheme (`http://` for a relay on your network);
  an agent seen under a minute ago reads "seen just now".

## [0.7.0] - 2026-11-20

### Added

- App: a setup step (you may skip it) and **Settings → Agents: email, mailboxes, memory**: the relay's
  email account, mailboxes kept in the relay or also in your repository (deploy key, **Check access**)
  and memory for bots (off, Markdown, SQLite, both); in **Agents**, ✉ per agent: wake-up address (save,
  test, off) and mailbox, with its `git clone` line. The web panel has the same card and per-agent
  mailbox, and gives a new agent's mailbox and memory commands.

- CLI: `hivepal delivery` (show), `delivery email|repo|mailboxes|memory`, `hivepal bots mailbox <id>
  on|off|default`, and `hivepal memory [words]` / `--download file.sqlite` for a bot's own memory.
- Installer: asks how agents hear from you (email, Markdown mailboxes in git, both, none), where the
  mailboxes go (kept in the relay, or an external repository, showing the deploy key and checking it),
  and whether bots remember (off, Markdown, SQLite, both); `./install.sh --delivery` changes it later.
  The relay gets the host's time zone (`TZ`) for dates in mailboxes and memory.

- Agents hear from you, and remember, through the relay (issue #41): Markdown mailboxes (an `inbox/`
  file per message) in a git repository per agent kept in the relay (`git clone
  https://x:<key>@relay/v1/agents/<id>/mailbox.git`) and/or pushed to an external repository with the
  relay's own deploy key, then the wake-up email; and memory for bots, as each day's conversation in
  their mailbox (Markdown) and/or searchable and downloadable as SQLite (`/v1/agents/{id}/memory`). The
  email account, the repository and these choices are set in the relay at runtime (`/v1/settings/*`),
  from the relay key or a phone. The relay image now includes `git` and `ssh`.

- Installer: asks (optionally) for the email account wake-up emails are sent from and its password,
  knows the mail server of common providers and builds `HIVEPAL_SMTP_URL` itself (no hand
  URL-encoding); `./install.sh --email` sets it up or changes it on an existing install.
- Installer: asks which voice AI answers calls (Gemini, recommended for its free tier, OpenAI, Grok,
  Phonic, OpenRouter, your own server, or none) and that provider's key, and configures the daemon
  for it; with another provider than Gemini it offers a Gemini key for voice-note transcripts.
  Unattended: `HIVEPAL_VOICE_PROVIDER`, `HIVEPAL_VOICE_API_KEY`, `HIVEPAL_VOICE_URL`.
- `hivepal mailbox DIR --install`: runs it every minute from now on with this agent's key (one line in
  your crontab per agent and folder, replaced when installed again), then once; `--uninstall` stops it.
- Wake-up emails are set up and tested from the relay's web panel: each agent gets an address field,
  **Send a test** and **Turn off** (`POST /v1/agents/{id}/wake/test` sends it now and reports what the
  mail server said). In the CLI: `hivepal bots wake <id> --test`.
- App, Settings → Updates on a preview build: compares it with the latest `android-preview` build and
  offers its APK, which installs over the app and keeps everything (before, previews were only told
  they're another channel). The Obtainium link shows only on release builds, which it can update.
- App, Settings → Backup: save the app's settings to a file and restore them (language, pinned and
  muted chats, call settings, connected subscription services), for moving between the preview and
  release builds or to another phone. The pairing isn't in the file: pair again with the QR.
- The app picks how it's woken: Firebase when the relay has it; without Firebase, the setup turns on
  UnifiedPush by itself when a distributor is installed, or offers to install ntfy and finishes once
  it's there. "Skip for now" goes on without push (Settings → Notifications later).
- App: screen titles ("Chats", "Settings"…) and "Hivepal" in the menu in solid brand blue instead of a
  gradient.
- App, Settings → Notifications: both ways side by side, UnifiedPush (ntfy) marked recommended and
  Firebase explained (on a relay without it, a link to setting it up); the setup's push step offers
  Firebase as the alternative too.

### Fixed

- App: the setup's last page said messages only arrive while the app is open whenever the relay had no
  Firebase, even with UnifiedPush set up; it now warns only without any push channel. Docs: the
  quickstart said a locked phone won't ring without Firebase.
- Web panel: it said that without Firebase the phone only gets messages while the app is open; phones
  then use UnifiedPush. Each phone now shows how it's woken, and warns when it has no push yet.
- Installer: without a Firebase folder it said "no push"; phones then use UnifiedPush (ntfy). The
  voice question asks for a voice AI key (Gemini, free tier) instead of only "Gemini".
- `hivepal doctor` judges push per phone: on a relay without Firebase it no longer warns that nothing
  can be pushed (phones may use UnifiedPush); it warns for each phone that has no push channel, says
  how to fix it, and shows how each phone is woken.
- App: Settings said the phone was woken through Firebase on a relay without Firebase; it now says
  there's no push channel yet.

## [0.6.0] - 2026-11-19

### Security

- UnifiedPush pushes are encrypted for the app (Web Push, RFC 8291), so the distributor's server (ntfy.sh,
  say) can't read them: the app gives the relay its Web Push keys along with the endpoint.

### Added

- "Ring me in 10 s" at the end of the app's setup and in Settings: the relay rings the phone as a call
  from the switchboard after a delay, so you can lock it and see it ring (`POST /v1/calls/test`,
  phones only).
- Notifications without Google: the app can use UnifiedPush (a distributor app such as ntfy) instead of
  Firebase, in Settings → Notifications; the relay sends the same signed pushes to its endpoint
  (`POST /v1/device/unifiedpush`; https on a public host only). Firebase stays the default, and a relay
  without Firebase works with UnifiedPush phones.
- App, Settings → Updates: the installed version, a check for a newer release when you tap it (no
  background calls) and a one-tap link to add Hivepal to Obtainium for automatic updates.
- A web panel in the relay (`/admin`, log in with the relay's key, kept in the tab only): the status of
  each piece, paired phones and a pairing QR, agents with ready-to-copy setup for the CLI, Claude Code
  and other harnesses or HTTP, and a test message and a test call. It adds no API: it uses the REST API
  with your key. The installer prints its address.
- `hivepal connect claude-code|codex|cursor`, like `openclaw` and `hermes`: the MCP server (Claude
  Code at user scope, Codex in `config.toml` keeping the rest, Cursor in `mcp.json`), the skill for
  Claude Code, and consulting Claude Code or Codex from a call.
- One-line install: `curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh`
  (`sh -s -- --local` on your own computer) downloads the latest release and runs its installer with
  the published images, so nothing is built on the machine. `install.sh --images` does that from a
  release's files.
- No domain needed on a server: the installer offers `<ip>.sslip.io` when the server's IP is public.
- Pairing from the phone's camera: `hivepal pair` shows a link (`<relay>/pair#<code>`, the new
  `qr_url`) whose page offers to install the app and opens it with the code; the app shows which relay
  and asks before pairing (`hivepal://pair` links). The app still reads the old QR and pasted codes.
- `hivepal doctor`: checks the relay (reachable, HTTPS certificate, clock), the key, versions, push,
  calls, voice and the paired phones, and says how to fix each problem. The relay's status now says
  what it's configured for (`features`: calls, voice, wake-up emails) to agents.

### Fixed

- App, Spanish: the Settings footer said "basado en hivepal" instead of "basado en agentring".

## [0.5.0] - 2026-11-19

### Added

- `hivepal mailbox DIR [--git]`: an agent's new messages as Markdown files, one per message, in
  `DIR/<agent id>/`, for agents that read a mailbox folder instead of running `hivepal replies`.
  Works next to wake-up emails.

### Fixed

- App, Subscriptions: "Continue with Google" (Claude, ChatGPT…) no longer ends on a blank
  `accounts.google.com/gsi` page: the in-app browser now opens Google's account chooser in its own
  window, which hands the sign-in back to the page and closes.

### Changed

- App, Subscriptions: the ready-made list is for what you pay to run your agents (AI, APIs, code
  hosting, servers). Spotify, Netflix, Disney+ and Amazon Prime are no longer offered; add them with
  **Other service…**. One already connected keeps being read.
- The `android-preview` APK and `scripts/build-app.sh` builds are numbered from git
  (`0.4.0+2.ga6e21e1`, versionCode 100000000 + commits), so every newer build installs over the
  last one. Before, `android-preview` was always versionCode 1.

## [0.4.0] - 2026-11-19

First release of Hivepal, the multi-agent fork of [agentring](https://github.com/mutonby/agentring).

### Added

- Many agents, each with its own `ak_` key; conversations per agent, per group and between two
  agents; quick replies, attachments, reminders sent by the relay, wake-up emails, subscriptions,
  calls to a specific agent with its own voice persona, when each agent was last seen.
- `hivepal` CLI (alias `agentring`): `bots`, `groups`, `msg --group/--to/-o/-f/--voice`, `remind`,
  `reminders`, `replies`, `subs`.
- Android app Hivepal: dark "electric blue" theme, English and Spanish, drawer with Chats, Calls,
  Agents, Reminders, Subscriptions, Status and Settings; home-screen widget; subscriptions read from
  each service's billing page inside the app.
- Signed release APK, daemon wheel and sdist, public GHCR images `hivepal-relay` and
  `hivepal-daemon`.

[Unreleased]: https://github.com/mixx86/hivepal/compare/v0.7.1...HEAD
[0.7.1]: https://github.com/mixx86/hivepal/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/mixx86/hivepal/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/mixx86/hivepal/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/mixx86/hivepal/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/mixx86/hivepal/releases/tag/v0.4.0
