# The README demo

How `docs/media/hivepal-demo.mp4` was made: demo agents and data only, never real ones. The story is
a day of small errands with three fictional agents: Atlas (planner), Hearth (the house) and Penny
(money), plus a Home group. It shows quick replies, an agent-to-agent message, a photo in a group, a
reminder that insists and ends in a call, a voice note, a mailbox file, subscriptions and a call.

1. `assets.py` makes the props: the plumber's quote Hearth sends (`quote.jpg`) and Hearth's voice on the
   call (`hearth.wav`, Gemini text-to-speech). It reads a Gemini key from the file named by
   `DEMO_GEMINI_KEY_FILE`, kept outside the repository.
2. `reset_demo.py` starts from scratch: `./install.sh --local` with `HIVEPAL_HOME` in this folder (a
   separate demo relay; the same `DEMO_GEMINI_KEY_FILE` gives it voice notes), the demo agents and
   group, the app on the emulator cleared and paired, and some history.
3. An Android emulator (`system-images;android-36;google_apis;x86_64`, 1080x2400) with the app installed.
   With emulator 37.2.x the headless binary (`-no-window`) crashes on a cold boot; run it with a window
   and `-gpu host`.
4. `record.py` plays the scenes: it runs the real `hivepal` commands as the demo agents, taps the
   emulator, records its screen (`adb shell screenrecord`) and logs when each command was typed.
5. `compose.py` redraws the terminal from that log and puts the phone recording beside it (ffmpeg),
   1920x1080 at 30 fps.
6. `audio.py` (numpy) builds the sound from the same log: key clicks, a chime per message, the ringtone,
   Hearth's voice once answered and a quiet pad; ffmpeg muxes it in. For a player with controls on
   GitHub, the MP4 is uploaded as an attachment (drag it into any issue or comment box) and that link
   goes in the README.

`demo.py` holds the helpers (demo agents, adb taps). Everything a run makes stays in this folder and
is ignored by git (`.gitignore`).

```bash
export DEMO_GEMINI_KEY_FILE=/path/outside/the/repo
uv run --with pillow python assets.py
python3 reset_demo.py
python3 record.py
uv run --with pillow python compose.py
uv run --with numpy python audio.py
ffmpeg -i hivepal-demo.mp4 -i demo-audio.wav -c:v copy -c:a aac -b:a 160k -shortest ../../docs/media/hivepal-demo.mp4
```
