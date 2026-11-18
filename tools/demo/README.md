# The README demo

How `docs/media/hivepal-demo.mp4` was made: demo agents and data only, never real ones.

1. `./install.sh --local` with `HIVEPAL_HOME` pointing to a throwaway folder (a separate demo relay).
2. An Android emulator (`system-images;android-36;google_apis;x86_64`) with the app, paired with that relay.
3. `record.py` plays the scenes: it runs the real `hivepal` commands as the demo agents, taps the
   emulator, records its screen (`adb shell screenrecord`) and logs when each command was typed.
4. `compose.py` redraws the terminal from that log and puts the phone recording beside it (ffmpeg),
   1920x1080 at 30 fps. For a player with controls on GitHub, the MP4 is uploaded as an attachment
   (drag it into any issue or comment box) and that link goes in the README.

`demo.py` holds the helpers (demo agents, adb taps). Paths in the scripts point to the scratch folder
they were run from: adjust `S` before running them again.
