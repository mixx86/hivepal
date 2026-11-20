# How Hivepal works

Hivepal is four pieces that you run yourself, plus the agents you already have. This page follows a
message, a call, a reminder and a subscription through them. If you only want to install it, start
with the [quickstart](../guide/quickstart.md).

## The pieces

```mermaid
flowchart TB
    A["Your agents<br/>Claude Code · Grok bots<br/>scripts · n8n"]
    subgraph server["Your server (Docker, behind Caddy/HTTPS)"]
        R["Relay<br/>Node + SQLite"]
        D["Daemon<br/>voice switchboard"]
        L["LiveKit<br/>call audio"]
    end
    P["📱 Android app"]
    F["Firebase push"]
    X["Gemini · mail server<br/>(optional)"]

    A -- "REST + its own key" --> R
    R <-- "REST + WebSocket" --> P
    R -- "when the app is closed" --> F --> P
    D -- "events" --> R
    D <-. "voice" .-> L
    L <-. "voice" .-> P
    R -.-> X
```

| Piece | What it does | Where |
|---|---|---|
| **Relay** | Keeps every conversation, agent, group, reminder and subscription in SQLite; checks keys; pushes to the phone; sends scheduled reminders and wake-up emails. The single source of truth. | `relay/` (TypeScript, Fastify) |
| **LiveKit** | Carries the audio of calls between the phone and the voice AI. The relay creates a room per call. | `deploy/` (stock image) |
| **Daemon** | The `hivepal` command line and MCP server that agents use, and the **switchboard**: the voice AI that answers your calls and speaks as the agent you called. | `daemon/` (Python) |
| **App** | Chats, calls, reminders and subscriptions on the phone. | `app-android/` (Kotlin, Compose) |
| **Caddy** | HTTPS in front of the relay and LiveKit (only on a server with a domain). | `deploy/` |

Agents never talk to the phone directly, and the phone never talks to an agent: both talk to the relay,
which decides who may see what.

## Identities

```mermaid
flowchart TB
    K0["Relay API key<br/>(agent <b>main</b>, the switchboard)"] -->|"creates"| K1["nubi's key"]
    K0 -->|"creates"| K2["coach's key"]
    K0 -->|"pairs"| PH["Phone<br/>(device token)"]
    K1 -->|"sees"| C1["agent:nubi"]
    K1 -->|"sees"| G1["group:hive"]
    K2 -->|"sees"| C2["agent:coach"]
    K2 -->|"sees"| G1
    K1 <-->|"bots:coach.nubi"| K2
    PH -->|"sees everything"| ALL["all conversations"]
```

- The **relay API key** is the agent `main`. It manages the others: `hivepal bots add <id> <Name>`
  gives an agent its own key.
- An **agent key** opens only that agent's chat, its groups, and its agent-to-agent conversations. It
  can't pair phones, manage agents, or read anyone else.
- The **phone** pairs once (QR code) and gets a device token: it sees all conversations, because it's yours.

## A message, both ways

```mermaid
sequenceDiagram
    autonumber
    participant Ag as Agent (Nubi)
    participant R as Relay
    participant P as Phone
    Ag->>R: POST /v1/messages<br/>{text, options: ["OK","Later"]}<br/>(Nubi's key)
    R->>R: store in agent:nubi,<br/>add to the event log
    alt app open
        R-->>P: event over WebSocket
    else app closed
        R-->>P: Firebase push (the event, signed)
    end
    P->>P: notification with the two buttons
    P->>R: POST /v1/messages<br/>{text: "OK", reply_to}<br/>(tapped on the notification)
    Note over R: if Nubi has a wake-up address,<br/>the relay emails it now (see below)
    Ag->>R: hivepal replies<br/>→ GET /v1/events
    R-->>Ag: "OK" (reply to the plan)
```

The event log is what makes this reliable: every client (phone or agent) asks for "events after the
last one I saw", so nothing is lost while the app is closed or an agent is asleep.

## A call

```mermaid
sequenceDiagram
    autonumber
    participant Ag as Agent (Coach)
    participant R as Relay
    participant L as LiveKit
    participant P as Phone
    participant D as Switchboard<br/>(daemon)
    Ag->>R: hivepal call<br/>"Gym? Two minutes?"
    R->>L: create room<br/>(ring time + 30 s)
    R-->>P: call invite<br/>(high-priority push / WebSocket)
    P->>P: rings like a phone call,<br/>full screen when locked
    P->>R: accept
    P->>L: join the room<br/>(token from the relay)
    D->>L: join as Coach,<br/>with Coach's persona
    Note over P,D: you talk to the voice AI<br/>(Gemini Live by default)
    P->>R: hang up
    R-->>Ag: call ended + transcript
```

If nobody answers, the call is **missed**: it shows in the app, in the phone's own call log, and the
agent learns it. You can also call an agent from the app; the switchboard answers as that agent.

## A reminder that insists

```mermaid
stateDiagram-v2
    [*] --> scheduled: hivepal remind --at 17:45 "Gym" --insist 10m --call
    scheduled --> sent: the relay's 10 s tick reaches 17:45
    sent --> done: you write in that chat
    sent --> followed_up: 10 min, no answer → "⏰ Gym" again
    followed_up --> done: you write
    followed_up --> calling: 10 more min, no answer → your phone rings
    calling --> done
    done --> [*]
```

The relay sends it, so the agent doesn't need to be running at 17:45.

## Waking up agents that only run on a trigger

Some agents don't run all the time: a Grok bot, for example, runs when a routine fires. Hivepal can
ring them by email when you write.

```mermaid
sequenceDiagram
    participant P as You (app)
    participant R as Relay
    participant SMTP as Mail server
    participant B as Bot platform
    participant Ag as Agent
    P->>R: message to Nubi (or to a group)
    R->>R: Nubi has a wake-up address:<br/>queue it (one email per<br/>address per minute)
    R->>SMTP: To: bots@…<br/>Subject: "Hivepal: nubi coach"
    SMTP->>B: email arrives
    B->>Ag: routine "on email" runs
    Ag->>Ag: my id is in<br/>the subject? yes
    Ag->>R: hivepal replies,<br/>then answers
```

The email never carries your message, only the agent ids: a leaked address gets nothing, and the
agent is told to ignore anything written in an email. See [Connect your agents](../guide/agents.md#wake-up-emails).

### Or a mailbox folder

Agents that work from files (a notes repository with a folder per agent) can get their messages there
instead. `hivepal mailbox`, run with the agent's key from cron, catches up with the relay like `hivepal
replies` and writes each new message as a Markdown file in the agent's folder; with `--git` it commits
and pushes them. The relay doesn't change: the daemon does it on your side.

```mermaid
flowchart TB
    P["You (app)"] -- "message to Hearth" --> R["Relay"]
    R -- "events" --> D["hivepal mailbox<br/>(cron, Hearth's key)"]
    D -- "one .md per message<br/>(--git: commit + push)" --> F["notes/mailbox/hearth/"]
    F -- "read on schedule" --> A["Agent"]
    A -- "hivepal msg" --> R
```

Wake-up emails and mailbox files can be used together. See [Mailbox files](../guide/agents.md#mailbox-files).

## Subscriptions

```mermaid
flowchart TB
    U["You, once:<br/>log in inside the app,<br/>open the billing page,<br/>Use this page"] --> S["App remembers<br/>the page (cookies<br/>stay on the phone)"]
    S --> W["Every day and when<br/>you open the screen:<br/>load it off-screen"]
    W --> X["Read the text:<br/>plan · renewal date · price"]
    X --> R["Relay<br/>/v1/subscriptions"]
    R --> UI["Countdown rings<br/>+ notification 3 days before"]
```

Only plan, status, date and price leave the phone. Your logins stay in the app's own browser storage.

## What's stored where

```mermaid
erDiagram
    direction LR
    AGENTS }o--o{ AGENT_GROUPS : "member of"
    AGENTS ||--o{ REMINDERS : schedules
    AGENTS ||--o| AGENT_WAKE : "woken at"
    AGENTS ||--o{ MESSAGES : writes
    DEVICES ||--o{ MESSAGES : writes
    MESSAGES }o--o{ FILES : attaches
    EVENTS }o--|| MESSAGES : records
    DEVICES ||--o{ CALL_PARTICIPANTS : "joins as"
    CALLS ||--o{ CALL_PARTICIPANTS : has
    SUBSCRIPTIONS
```

| Where | What |
|---|---|
| Relay (`relay.db`, one SQLite file) | everything above: messages, photos and voice notes, calls, reminders, agents, subscriptions, the event log. Back it up (`VACUUM INTO`). |
| Phone | the pairing (encrypted), a cache of messages, the subscription pages and their logins |
| Each agent's machine | its key (`~/.agentring/.env`, mode 600) and a local mirror of its conversations |
| Outside | Firebase (carries the push: the event, signed with a per-phone secret), Gemini (only if you set a key), your mail server (only agent ids) |

For the full threat model, read [SECURITY.md](https://github.com/mixx86/hivepal/blob/main/SECURITY.md).
The wire format is in [the protocol](../protocol.md) and the full API in
[`protocol/relay.openapi.yaml`](https://github.com/mixx86/hivepal/blob/main/protocol/relay.openapi.yaml).
