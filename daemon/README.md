# hivepal daemon

The agent side of [hivepal](https://github.com/mixx86/hivepal): the `hivepal`
CLI, the MCP server (`hivepal mcp`) and the voice AI for calls. It lets your AI
agents message and call your phone, and lets you call them back.

```bash
uv tool install 'hivepal[voice] @ git+https://github.com/mixx86/hivepal#subdirectory=daemon'   # or, from a checkout: uv tool install './daemon[voice]'
hivepal init --relay https://relay.example.com --api-key ak_...
hivepal pair                       # scan the QR with the Android app
hivepal msg "hola"
hivepal call "Testing hivepal" --ai   # needs GEMINI_API_KEY in ~/.agentring/.env
claude mcp add hivepal -- hivepal mcp
```

Your API key must not cross the internet in clear, so a plain `http://` relay is refused
unless its host is loopback, a private or CGNAT (Tailscale) address, a `.local` name or a
single-label name such as `relay` in Docker. `HIVEPAL_ALLOW_INSECURE_RELAY=1` lifts this,
for a network you trust that doesn't look like one. The local database is readable by you only.

You need your own relay (self-hosted, Docker compose) and the Android app. See the
[setup guide](https://github.com/mixx86/hivepal/blob/main/docs/setup.md)
and [connecting agents](https://github.com/mixx86/hivepal/blob/main/docs/agents.md).
