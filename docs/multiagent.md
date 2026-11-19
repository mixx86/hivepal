# Multi-agent fork

Hivepal lets several agents share one relay, each with its own identity, instead of one agent per
relay (upstream agentring's model).

## Model

- **Agents.** The relay's API key is the agent `main` (the voice daemon on the VPS uses it). Every
  other agent gets its own `ak_` key: `hivepal bots add <id> <Name>` (relay key only). An
  agent's key opens only its own conversations and calls; it can't pair phones or manage agents.
- **Conversations.** Every message has a `conv`: `agent:<id>` (the user and one agent) or
  `group:<id>` (the user and several agents). Messages from before the fork are `agent:main`.
- **Groups.** `hivepal groups add <id> <Name> --members a,b,c`, or from the app. Every member
  reads what the user and the other members write there (`hivepal msg --group <id>`,
  `hivepal replies` shows `[Group] Author: text`).
- **Names.** The user renames agents from the app (`PATCH /v1/agents/{id}`); `agent.updated` and
  `group.updated` events keep every phone in step.
- **Calls** carry `agent_id` and the agent's name. User→agent calls still go to `main`.
- **Presence.** One WebSocket per agent (upstream allowed one per relay); `agent.presence`
  has `agent_id`.

Protocol: `protocol/relay.openapi.yaml` (`/v1/agents`, `/v1/groups`, `conv` on messages).
Tests: `relay/test/multiagent.test.ts`, `daemon/tests/test_multiagent.py`.

## Hivepal additions

- **Quick replies**: an agent sends `options` (≤ 6, ≤ 40 chars); the app shows buttons under the
  message (and 2 in the notification); tapping one posts it with `reply_to`. The CLI shows
  `Sí (reply to: «question»)`.
- **Attachments**: `POST /v1/files` (raw body, image/jpeg|png|webp, audio/mp4|aac|ogg|mpeg|webm,
  ≤ 10 MB, stored in SQLite) and `attachments: [file_id]` on a message. An agent downloads files it
  uploaded or that are attached to a message it can see.
- **Reminders**: `POST /v1/reminders {text, at, conv?, options?}`; the relay posts the message as
  the agent at `at` (checked every 10 s), with a push. Canceled when the agent or its group is gone.
- **Bot conversations**: `bots:<a>.<b>` (ids sorted). Both agents read and write; the user reads it
  in the app ("Between agents"); it never pushes.
- **Calling an agent**: the phone sends `agent_id` in `POST /v1/calls`; the voice daemon (`main`)
  answers every user call and speaks as that agent, with `personas/<agent_id>.md` from its home
  (for example synced from wherever you keep each agent's notes).
- **Last seen**: `last_seen_at` on `/v1/agents`, updated (once a minute at most) on every
  request with the agent's key; the app shows it and turns it red after a day.
- **Pairing link**: `PairingToken.qr_url` = `<relay>/pair#<base64url of qr_payload>`. `GET /pair` is a
  static page (CSP by script hash; the code stays in the fragment) that offers to install the app and
  opens it with `hivepal://pair?c=<code>`; the app shows the relay's host and asks before pairing.
- **Test call**: `POST /v1/calls/test {delay_s}` (phones only): after the delay the relay places a call
  from `main` targeted at that phone, through the normal call path ("Ring me in 10 s").
- **UnifiedPush**: `POST /v1/device/unifiedpush {endpoint, p256dh?, auth?}` (https on a public host,
  re-checked after DNS; `DELETE` goes back to FCM). The relay POSTs the same signed `PushData` there,
  encrypted for the app with RFC 8291 when it gave keys; 404/410 forget it. `Device.push_via` says
  `unifiedpush`, `fcm` or `none`. Accepted deviation: [architecture](architecture.md#accepted-deviations).
- **Wake-up emails**: `PUT /v1/agents/{id}/wake {email}` (relay key; the address never shows in
  views, only `wakes`), and `POST /v1/agents/{id}/wake/test`, which sends it now and answers `204` or
  `502 email_failed` with what the mail server said.
- **What a relay is configured for**: `Status.features {calls, voice, wake_email}`, to agents (used by
  `hivepal doctor` and the web panel).
- **Web panel**: `GET /admin`, a static page that uses the REST API with the relay's key pasted into
  it (kept in that tab): status, phones and a pairing QR, agents with setup to copy, a test message
  and a test call.

## App

Navigation drawer (Chats, Calls, Agents, Reminders, Subscriptions, Status, Settings). One chat per agent and per group,
with a colour per agent; a chat's menu clears it on this phone (the relay keeps the messages),
renames the agent or edits the group. Calls lists the call history with missed calls marked;
Settings can copy agent calls into the Phone app's call log (`WRITE_CALL_LOG`, optional), choose how
the phone is woken (Firebase or UnifiedPush), check for updates, save and restore the app's settings
to a file, and ring the phone as a test.

The app keeps the package `dev.agentring.app`, so it can't be installed over the Play Store
build (different signing key): uninstall that first and pair again.

## Compatibility

An upstream daemon works with the fork: with the relay key it is `main`; with an agent's own key
it gets that agent's conversation (but can't write in groups). The fork's app works with an
upstream relay too: then there is only `main`.
