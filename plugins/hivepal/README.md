# hivepal plugin for Grok Build

Lets Grok reach you on your Android phone through [hivepal](https://agentring.io):
send a message, wait for your reply, or ring the phone so a voice AI talks to you and
hands the transcript back. No phone number: the call goes over the internet to the
hivepal app.

## Install

```bash
grok plugin install mutonby/agentring#plugins/hivepal --trust
```

Then:

1. Install the hivepal app from
   [Google Play](https://play.google.com/store/apps/details?id=dev.agentring.app) (Android only).
2. Sign in at https://agentring.io, pair the phone with the QR code on the dashboard
   and copy your API key (`ak_…`).
3. Put the key in the environment Grok Build starts from, for example in your shell profile:

   ```bash
   export HIVEPAL_API_KEY=ak_...
   ```

4. Start a new Grok session and try: "Send me a message on my phone when the tests finish."

## Components and permissions

- One remote MCP server (`.mcp.json`) with four tools: `send_message`, `get_replies`,
  `call` and `status`.
- One skill (`skills/hivepal`) that tells the agent when to message and when to call,
  and how to help you set hivepal up.

No hooks, scripts, local executables or dependency installs. The tools reach only the
phones paired with your own hivepal account.

## Network and credentials

- `https://agentring.io/mcp`: the hosted hivepal MCP server (Streamable HTTP). It is the
  only endpoint the plugin calls.
- `HIVEPAL_API_KEY`: read from your environment and sent to that server as
  `Authorization: Bearer <key>`. The plugin stores no credentials and reads nothing else.

A `call` lasts as long as the conversation. Grok Build's default tool timeout is long
enough; if you lowered it, give this server at least 30 minutes
(`tool_timeout_sec = 1800`).

## Self-hosted

hivepal is open source and you can run your own relay
([setup](https://github.com/mutonby/agentring/blob/main/docs/setup.md)). In that case skip
this plugin's server and add your own instead:

```bash
grok mcp add hivepal -- hivepal mcp
```

## License and support

Apache-2.0, maintained in https://github.com/mutonby/agentring (issues welcome there).
Use of the hosted service follows the terms on https://agentring.io.
