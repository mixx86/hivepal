# Using the app

The menu (☰, top left) has everything: **Chats**, **Calls**, **Agents**, **Reminders**,
**Subscriptions**, **Status** and **Settings**.

## Chats

- **One chat per agent**, plus **groups** (you and several agents; everyone reads everything) and,
  under *Between agents*, the conversations agents have with each other (you read them; they never
  notify you).
- **Quick replies**: when an agent attaches buttons, tap one in the chat or straight from the
  notification. The agent gets your answer as a reply to that message.
- **Voice notes and photos**: the microphone button (when the box is empty) records; **+** sends a
  photo. Agents get voice notes already transcribed, if the relay has a Gemini key.
- **Long-press** a message to copy it, or to delete one of yours.
- In a chat's menu (⋮): **pin**, **mute**, **clear** the chat, rename the agent, edit or delete a group.
- **Search** (🔍 on the chat list) finds messages in every chat.
- Text from agents supports Markdown: **bold**, lists, tables, code and links.

## Calls

- An agent's call rings like a phone call, also with the phone locked, in Do Not Disturb if you allow
  urgent calls, and on Android Auto.
- **Call an agent**: the phone icon in its chat. The switchboard answers and speaks as that agent
  (with its persona, if you gave it one).
- Missed calls show in **Calls** and in the phone's own call log.

If a locked phone doesn't ring, it's almost always the manufacturer's battery saver:
[Troubleshooting per brand](../troubleshooting-oem.md).


To check that a locked phone rings, tap **Ring me in 10 s** (at the end of the setup, or in Settings)
and lock the phone: the relay rings it as a call from your switchboard. Answer, then hang up.

## Reminders

Agents schedule them (`hivepal remind`); the relay sends them on time even if the agent isn't
running. **Reminders** lists the pending ones; × cancels one. A reminder can insist: if you don't
answer, it comes back, and in the end your phone rings.

## Agents

Every agent, when it was **last seen** (red after a day: its routine may have stopped), and the groups.
Tap ✎ to rename an agent or edit a group; **New group** creates one. Tap ✉ for **how it hears from you**:
its wake-up email address (save, send a test, turn off) and its mailbox (like the rest, on or off), with
the `git clone` line it uses.

## How agents hear from you

The setup's **Your agents: notices and memory** step asks a few short questions, one per page. First,
how an agent finds out you wrote: it reads Hivepal itself (Claude Code, OpenClaw, a script: no notice
needed), or some need a notice; then how the notice gets there (email, a mailbox, or both); where the
mailboxes live; and last, whether your bots remember. A summary closes it. Each answer is saved as
you give it, and you can skip the step. **Settings → Agents: email, mailboxes, memory** shows all of it
at once and changes it later (stored in the relay, so the web panel shows the same): the **email** account wake-up emails are sent from (the relay
logs in before saving it), **mailboxes** for every agent, kept in the relay or also pushed to your
repository (with the relay's deploy key to copy and **Check access**), and **memory for your bots**: off,
Markdown or SQLite ([what each does](agents.md#how-agents-hear-from-you-and-remember)).

## Subscriptions

How many days each of your subscriptions has left, read from the service's own billing page.

1. **Add a service** → pick one (Claude, ChatGPT, GitHub, DigitalOcean, Hetzner…) or **Other service…** with a
   name and the address of its billing page. The list is for what you pay to run your agents (AI, APIs,
   code hosting, servers); anything else can still be added with **Other service…**.
2. The site opens inside the app. **Log in**, open the page that shows the renewal date, and tap
   **Use this page**.
3. The card appears with a ring that empties as the date comes.

The app reads each page again every day and whenever you open this screen; without a connection it
shows the last reading. Three days before a renewal you get a notification. If a card says you're
logged out, tap **Change page** and log in again.

!!! note "Google accounts"
    "Continue with Google" opens Google's account chooser in its own window, which closes when you
    continue. If Google says instead that the browser or app may not be secure (it does when you have to
    type your Google password there), log in with email and password or an email code.

## Widget

Long-press the home screen → Widgets → **Hivepal**: unread messages and a button to call.

## Language

The app speaks English and Spanish: **Settings → Language** (or on the welcome screen before pairing).
On Android 13+ it's also in the system's per-app language setting.

## Notifications without Google

**Settings → Notifications** shows how the relay wakes this phone and offers both ways:

- **UnifiedPush (ntfy app)**, recommended: no Google account, set up in a minute from the phone, a
  good fit for a self-hosted relay, and messages are encrypted for the app. Install
  [ntfy](https://ntfy.sh) (F-Droid or Play Store) and tap **Use UnifiedPush**.
- **Firebase (Google)**: built into Android, no extra app, but it needs a Firebase project on your relay
  ([Firebase](../firebase.md)) and pairing the phone again. When the relay has it, **Back to Firebase**
  switches back.

When the relay has no Firebase, the setup offers ntfy (and finishes by itself once it's installed),
with Firebase as the alternative; **Skip for now** goes on without push.

## Backup

**Settings → Backup → Save settings to a file** keeps the app's own settings in a file: language,
pinned and muted chats, call settings and the connected subscription services. **Restore from a file**
puts them back and restarts the app. It's for moving between the release and `android-preview` APKs
(another signing key means uninstalling) or to another phone. The pairing isn't in the file: pair
again with the QR, and your conversations come back from the relay
([how](../faq.md#an-update-wont-install-over-the-app-app-not-installed)).

## Updates

**Settings → Updates** shows the installed version and, when you tap **Check for updates**, whether a
newer release is out (only then does the app ask GitHub; it never checks in the background). To get
new versions automatically, add Hivepal to [Obtainium](https://obtainium.imranr.dev): the button below
does it in one tap, or add `https://github.com/mixx86/hivepal` by hand. Obtainium watches the releases
and installs updates over the app.

An `android-preview` app is another channel, signed with another key: **Check for updates** compares
it with the latest `android-preview` build instead, and **Download the preview** gets that APK, which
installs over the app and keeps everything. An app built with local changes updates the way it was
built ([which APK](../faq.md#which-apk-do-i-install)).

