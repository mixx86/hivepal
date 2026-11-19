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
Tap ✎ to rename an agent or edit a group; **New group** creates one.

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

By default the relay wakes the phone through Firebase. To do without Google, install a UnifiedPush
distributor such as [ntfy](https://ntfy.sh) (F-Droid or Play Store) and tap **Settings → Notifications
→ Use UnifiedPush**: the app registers with it and gives the relay its address, and pushes, ringing
calls included, go through it. **Back to Firebase** undoes it. A relay without Firebase works this way
too.

## Updates

**Settings → Updates** shows the installed version and, when you tap **Check for updates**, whether a
newer release is out (only then does the app ask GitHub; it never checks in the background). To get
new versions automatically, add Hivepal to [Obtainium](https://obtainium.imranr.dev): the button below
does it in one tap, or add `https://github.com/mixx86/hivepal` by hand. Obtainium watches the releases
and installs updates over the app.

An `android-preview` or self-built app is another channel, signed with another key: it updates from
where it came from, not from the releases ([which APK](../faq.md#which-apk-do-i-install)).

