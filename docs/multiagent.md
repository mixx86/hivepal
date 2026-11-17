# Multi-agent fork

This private fork (`mixx86/agentring`) lets several agents share one relay, each with its own
identity, instead of one agent per relay.

## Model

- **Agents.** The relay's API key is the agent `main` (the voice daemon on the VPS uses it). Every
  other agent gets its own `ak_` key: `agentring bots add <id> <Name>` (relay key only). An
  agent's key opens only its own conversations and calls; it can't pair phones or manage agents.
- **Conversations.** Every message has a `conv`: `agent:<id>` (the user and one agent) or
  `group:<id>` (the user and several agents). Messages from before the fork are `agent:main`.
- **Groups.** `agentring groups add <id> <Name> --members a,b,c`, or from the app. Every member
  reads what the user and the other members write there (`agentring msg --group <id>`,
  `agentring replies` shows `[Group] Author: text`).
- **Names.** The user renames agents from the app (`PATCH /v1/agents/{id}`); `agent.updated` and
  `group.updated` events keep every phone in step.
- **Calls** carry `agent_id` and the agent's name. User→agent calls still go to `main`.
- **Presence.** One WebSocket per agent (upstream allowed one per relay); `agent.presence`
  has `agent_id`.

Protocol: `protocol/relay.openapi.yaml` (`/v1/agents`, `/v1/groups`, `conv` on messages).
Tests: `relay/test/multiagent.test.ts`, `daemon/tests/test_multiagent.py`.

## App

Navigation drawer (Chats, Calls, Agents, Status, Settings). One chat per agent and per group,
with a colour per agent; a chat's menu clears it on this phone (the relay keeps the messages),
renames the agent or edits the group. Calls lists the call history with missed calls marked;
Settings can copy agent calls into the Phone app's call log (`WRITE_CALL_LOG`, optional).

The app keeps the package `dev.agentring.app`, so it can't be installed over the Play Store
build (different signing key): uninstall that first and pair again.

## Compatibility

An upstream daemon works with the fork: with the relay key it is `main`; with an agent's own key
it gets that agent's conversation (but can't write in groups). The fork's app works with an
upstream relay too: then there is only `main`.
