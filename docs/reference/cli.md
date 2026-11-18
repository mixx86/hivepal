# CLI reference

`hivepal` (also installed as `agentring`) is what agents run. Every command has `--help`, and most
take `--json` for machine-readable output.

## Setup

| Command | |
|---|---|
| `hivepal init --relay URL --api-key ak_…` | Connect to a relay; saved in `HIVEPAL_HOME` (default `~/.agentring`). |
| `hivepal pair` | QR code (or a code to paste) to pair a phone; single use, 10 minutes. |
| `hivepal status` | Relay connection and paired phones. |
| `hivepal devices` / `devices revoke <id>` | List or unpair phones. |
| `hivepal version` | The version. |

## Messages

| Command | |
|---|---|
| `hivepal msg "text"` | Message the user in your own chat. Prints the `msg_…` id. |
| `  -o "Yes" -o "No"` | Quick-reply buttons (up to 10). |
| `  -f photo.jpg` | Attach a photo or audio (up to 4). |
| `  --voice` | Also send it as a voice note (spoken by Gemini). |
| `  --group hive` | Write in a group. |
| `  --to coach` | Write to another agent (an agent-to-agent conversation). |
| `  --reply-to msg_…` | Answer a specific message. |
| `hivepal replies` | New answers from the user (and from groups/agents), with voice-note transcripts. `--peek` doesn't mark them seen. |
| `hivepal file <file_id>` | Download a photo or audio the user sent. |

## Reminders

| Command | |
|---|---|
| `hivepal remind --at 18:00 "text"` | The relay sends it then, even if you aren't running. Also `--in 30m`, or a date `2026-12-05T09:30`. |
| `  -o …`, `--group …` | Buttons, or send it in a group. |
| `  --insist 15m` | No answer after 15 minutes → sent again. |
| `  --call` | With `--insist`: still no answer after as long again → the phone rings. |
| `hivepal reminders` / `reminders --cancel rem_…` | Your pending reminders. |

## Calls

| Command | |
|---|---|
| `hivepal call "why"` | Ring the phone (30 s). Without `--ai`, an audio test. |
| `  --ai` | A voice AI talks to the user for you and hands you the transcript. |
| `  --urgent` | Ring through Do Not Disturb (if the user allowed it). |
| `hivepal serve [--ai]` | The switchboard: stays connected and answers the user's calls (with personas from `personas/<agent>.md`). |

## Agents, groups, subscriptions (relay API key)

| Command | |
|---|---|
| `hivepal bots` | Every agent and when it was last seen (✉ = wakes by email). |
| `hivepal bots add <id> <Name>` | A new agent; prints its key once. |
| `hivepal bots rename <id> <Name>` · `bots key <id>` · `bots remove <id>` | Rename, new key, revoke. |
| `hivepal bots wake <id> <email\|off>` | Email this address when the user writes to the agent. |
| `hivepal groups` · `groups add <id> <Name> --members a,b` · `groups set` · `groups remove` | Groups. |
| `hivepal subs` · `subs set …` · `subs rm <id>` | Subscriptions (the app writes them; these are for scripts). |

## For harnesses

| Command | |
|---|---|
| `hivepal mcp` | MCP server over stdio: `claude mcp add hivepal -- hivepal mcp`. |
| `hivepal mcp-http` | Remote MCP server (Streamable HTTP) for many users. |
| `hivepal connect openclaw\|hermes` | Set up those harnesses in one go. |
| `hivepal agents` | Agents the voice AI can consult during a call (`agents.yaml`). |
