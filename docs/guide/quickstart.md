# Quickstart: Hivepal on your computer in 15 minutes

This runs everything on your own computer, for a phone on the same Wi-Fi (or on
[Tailscale](https://tailscale.com) from anywhere). It's the fastest way to try Hivepal. For a server
that's reachable from anywhere, see [Install on a server](server.md).

## What you need

- A Linux or macOS computer with **Docker** (with the Compose plugin) and **[uv](https://docs.astral.sh/uv/)**.
- An **Android phone** (Android 8 or newer) and a USB cable.
- To build the app: **JDK 17+** and the **Android SDK** (Android Studio installs both). Or use an APK
  from the [releases](https://github.com/mixx86/hivepal/releases).
- Optional: an API key for a voice AI, for calls with a voice AI and voice-note transcripts. We
  suggest [Gemini](https://aistudio.google.com/apikey) (free tier); other providers work for calls
  ([voice](../voice.md)).

## 1. Start the relay and install the CLI

```bash
curl -fsSL https://raw.githubusercontent.com/mixx86/hivepal/main/scripts/get.sh | sh -s -- --local
```

It downloads the latest release into `~/hivepal` and uses its published images, so nothing is built
on your computer. From a clone it works the same: `git clone https://github.com/mixx86/hivepal && cd
hivepal && ./install.sh --local` (that one builds the images from the source).

It picks your computer's LAN address, asks for a name for the voice switchboard and the voice AI (Gemini, OpenAI,
Grok, Phonic, OpenRouter or your own server) with its optional key, starts the relay and LiveKit in Docker, installs the `hivepal` command and connects it.
At the end it prints the relay address and its API key (also saved in `deploy/.env.local`).

??? note "What just happened"
    `deploy/docker-compose.local.yml` runs the relay on port 8080 and LiveKit on 7880/7881 plus UDP
    50000–50100, bound to your LAN address. The CLI keeps its settings in `~/.agentring/`. Plain HTTP is
    allowed only because the address is private: the app refuses `http://` to public hosts.

## 2. Install the app

Plug in the phone with **USB debugging** on (Settings → About phone → tap *Build number* 7 times →
Developer options → USB debugging), then:

```bash
./scripts/build-app.sh --install
```

## 3. Pair the phone

```bash
hivepal pair
```

Scan the QR code with the phone's camera: it opens a page on your relay that offers to install the
app if it's missing and then opens it, which asks before pairing. With the app already open,
**Get started** → **Scan QR code** works too. Allow the permissions it asks for
(notifications, microphone, full-screen calls, unrestricted battery): they're what make a locked phone
ring.

## 4. Say hello

```bash
hivepal msg "Hello from Hivepal" -o "Hi!" -o "Who's this?"
```

The message arrives with two buttons. Tap one, then:

```bash
hivepal replies
```

## 5. Add a couple of agents

```bash
hivepal bots add nubi Nubi        # prints Nubi's own key
hivepal bots add coach Coach
hivepal groups add hive Hive --members nubi,coach
```

Give each agent its key (`hivepal init --relay http://<your-ip>:8080 --api-key ak_…` on the machine
where it runs, or the `HIVEPAL_API_KEY` variable). From then on each one writes in its own chat.
[Connect your agents](agents.md) explains the options.

## 6. Calls (optional)

With a voice AI key, run the switchboard and leave it running:

```bash
hivepal serve --ai
```

Now `hivepal call "Got a minute?"` rings the phone, and you can call any agent from the app.

!!! warning "Push notifications"
    Without Firebase, messages arrive only while the app is open, and a locked phone won't ring.
    Setting it up takes ten minutes: [Firebase](../firebase.md). Then run `./install.sh --local` again.

**Next:** [use the app](app.md) · [connect your agents](agents.md) · [build a hive of personal agents](hive.md)
