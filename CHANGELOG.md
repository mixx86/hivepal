# Changelog

What changes for people who run Hivepal, release by release. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/): a patch release (`0.4.1`) fixes things, a minor
release (`0.5.0`) adds features; before 1.0 a minor release may also change the protocol.

Every pull request that changes behaviour adds a line under **Unreleased**;
`scripts/release.sh` turns that section into the next version (see [docs/release.md](docs/release.md)).

## [Unreleased]

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

[Unreleased]: https://github.com/mixx86/hivepal/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/mixx86/hivepal/releases/tag/v0.4.0
