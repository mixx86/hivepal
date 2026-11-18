# How Hivepal works

Hivepal is four pieces that you run yourself, plus the agents you already have. This page follows a
message, a call, a reminder and a subscription through them. If you only want to install it, start
with the [quickstart](../guide/quickstart.md).

## The pieces

```mermaid
flowchart LR
    subgraph agents["Your agents (anywhere)"]
        A1["Claude Code / Codex<br/>(MCP or CLI)"]
        A2["Grok bots, cron jobs,<br/>scripts, n8n (CLI)"]
    end
    subgraph server["Your server (Docker)"]
        C["Caddy<br/>HTTPS"]
        R["Relay<br/>Node + SQLite"]
        L["LiveKit<br/>calls (WebRTC)"]
        D["Daemon<br/>voice switchboard"]
    end
    P["Android app"]
    FCM["Firebase Cloud Messaging"]
    G["Gemini<br/>(optional)"]
    M["Mail server<br/>(optional)"]

    A1 -- "REST + agent key" --> C
    A2 -- "REST + agent key" --> C
    C --> R
    C --> L
    R -- "WebSocket while open" --> P
    R -- "push when closed" --> FCM --> P
    P -- "REST + device token" --> C
    P <-- "voice" --> L
    D <-- "voice" --> L
    D -- "events" --> R
    R -. "transcribe / speak" .-> G
    R -. "wake-up email" .-> M
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
    Ag->>R: POST /v1/messages {text, options: ["OK","Later"]}<br/>(Nubi's key)
    R->>R: store in agent:nubi, add to the event log
    alt app open
        R-->>P: event over WebSocket
    else app closed
        R-->>P: Firebase push (the event, signed)
    end
    P->>P: notification with the two buttons
    P->>R: POST /v1/messages {text: "OK", reply_to}<br/>(tapped on the notification)
    Note over R: if Nubi has a wake-up address,<br/>the relay emails it now (see below)
    Ag->>R: hivepal replies → GET /v1/events
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
    participant D as Switchboard (daemon)
    Ag->>R: hivepal call "You skipped the gym twice. Two minutes?"
    R->>L: create room (lives for the ring time + 30 s)
    R-->>P: call invite (high-priority push / WebSocket)
    P->>P: rings like a phone call (Android Telecom, full screen when locked)
    P->>R: accept
    P->>L: join the room (token from the relay)
    D->>L: join as Coach, with Coach's persona
    Note over P,D: you talk to the voice AI (Gemini Live by default)
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
    R->>R: Nubi has a wake-up address → queue it<br/>(one email per address per minute)
    R->>SMTP: To: bots@… Subject: "Hivepal: nubi coach"
    SMTP->>B: email arrives
    B->>Ag: routine "on email" runs
    Ag->>Ag: my id is in the subject? yes
    Ag->>R: hivepal replies, then answers
```

The email never carries your message, only the agent ids: a leaked address gets nothing, and the
agent is told to ignore anything written in an email. See [Connect your agents](../guide/agents.md#wake-up-emails).

## Subscriptions

```mermaid
flowchart LR
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
    AGENTS ||--o{ MESSAGES : writes
    AGENTS }o--o{ AGENT_GROUPS : "member of"
    AGENTS ||--o{ REMINDERS : schedules
    AGENTS ||--o| AGENT_WAKE : "woken at"
    DEVICES ||--o{ MESSAGES : writes
    MESSAGES }o--o{ FILES : attaches
    CALLS ||--o{ CALL_PARTICIPANTS : has
    EVENTS }o--|| MESSAGES : "records"
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
