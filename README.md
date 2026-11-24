# Mission Control

A small read-only Android app that shows the owner's AI worker fleet in real
time: who is running, who finished today, and who is stuck or waiting on the
owner.

The app polls `snapshot.json` in this repo every 30 seconds. That file is the
published Work Dashboard snapshot, compacted by
`publish_snapshot.py` in the owner's workspace and committed here only when
it changes.

Layout:
- `app/` the Android app (single activity, Kotlin, no external dependencies)
- `snapshot.json` the live feed the app reads
- `.github/workflows/build.yml` builds the signed release APK on GitHub Actions

Made by Mohammad Sidique Ibrahim
