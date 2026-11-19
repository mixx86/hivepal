# Changelog

What changes for people who run Hivepal, release by release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/): a patch release (`0.4.1`) fixes things, a minor
release (`0.5.0`) adds features; before 1.0 a minor release may also change the protocol.

Every pull request that changes behaviour adds a line under **Unreleased**;
`scripts/release.sh` turns that section into the next version (see [Releasing](https://mixx86.github.io/hivepal-web/release/)).

## [Unreleased]

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

[Unreleased]: https://github.com/mixx86/hivepal/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/mixx86/hivepal/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/mixx86/hivepal/releases/tag/v0.4.0
