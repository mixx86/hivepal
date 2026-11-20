# Quickstart: Hivepal on your computer in 15 minutes

This runs everything on your own computer, for a phone on the same Wi-Fi (or on
[Tailscale](https://tailscale.com) from anywhere). It's the fastest way to try Hivepal. For a server
that's reachable from anywhere, see [Install on a server](server.md).

## What you need

- A Linux or macOS computer with **Docker** (with the Compose plugin) and **[uv](https://docs.astral.sh/uv/)**.
- An **Android phone** (Android 8 or newer). The app is a ready-made APK: no cable, no Android Studio
  (building it yourself is [at the end](#build-the-app-yourself), for those who want to).
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

## 2. Install the app and pair the phone

```bash
hivepal pair
```

Scan the QR code it shows with the **phone's camera**. It opens a page on your relay:

1. **Download the app**: it takes you to the [latest release](https://github.com/mixx86/hivepal/releases/latest);
   download `hivepal-X.Y.Z.apk` and open it. Android asks once to allow installs from your browser.
2. Back on that page, **Open Hivepal**: the app opens with the code and asks before pairing.

The app then walks you through the rest: allow the permissions it asks for (notifications,
microphone, full-screen calls, unrestricted battery: they're what make a locked phone ring), the free
ntfy app for push if your relay has no Firebase, and how your agents hear from you (you can skip that
and set it later in Settings). With the app already installed, **Get started** → **Scan QR code**
works too.

## 3. Say hello

```bash
hivepal msg "Hello from Hivepal" -o "Hi!" -o "Who's this?"
```

The message arrives with two buttons. Tap one, then:

```bash
hivepal replies
```

## 4. Add a couple of agents

```bash
hivepal bots add nubi Nubi        # prints Nubi's own key
hivepal bots add coach Coach
hivepal groups add hive Hive --members nubi,coach
```

Give each agent its key (`hivepal init --relay http://<your-ip>:8080 --api-key ak_…` on the machine
where it runs, or the `HIVEPAL_API_KEY` variable). From then on each one writes in its own chat.
[Connect your agents](agents.md) explains the options.

## 5. Calls (optional)

With a voice AI key, run the switchboard and leave it running:

```bash
hivepal serve --ai
```

Now `hivepal call "Got a minute?"` rings the phone, and you can call any agent from the app.

!!! note "Push notifications"
    Without Firebase, the app wakes through UnifiedPush: during pairing it offers the free ntfy app and
    sets it up, and a locked phone rings the same. [Firebase](../firebase.md) is optional.

## Build the app yourself

Instead of the release APK you can build it from the source: you need **JDK 17+** and the **Android
SDK** (Android Studio installs both) and the phone on a USB cable with **USB debugging** on
(Settings → About phone → tap *Build number* 7 times → Developer options → USB debugging). In a clone
of the repository:

```bash
./scripts/build-app.sh --install
```

It's signed with a different key than the releases, so one won't install over the other without
uninstalling first ([which APK](../faq.md#which-apk-do-i-install)).

**Next:** [use the app](app.md) · [connect your agents](agents.md) · [build a hive of personal agents](hive.md)
