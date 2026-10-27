---
name: y1-docs
description: Keep the sendspin-y1 user guide, its screenshots and the Help video script in step with the code. Use after any feature or UI change to the player app or the Home Assistant device page, when asked to refresh screenshots or docs, update the Help video script or chapters, or regenerate the placeholder Help video.
---

# Keeping the docs, screenshots and Help video in step

The guide in `docs/guide/` is three things at once: narration (the spoken script for the Help video on the player), an
on-screen shot list (screenshots), and plain documentation for grown-ups. One source, so a feature change updates all three.

## When a feature changes

1. **Read the diff** (`git diff main -- java/android/src custom_components`) and decide which chapters it touches. A new
   screen or setting needs a sentence in the matching chapter's *For grown-ups* and, if the child will meet it, a line of
   *Narration*. Narration is for an 8 year old: short sentences, second person, British, no jargon, no em-dashes. Keep each
   chapter under about 40 seconds (about 100 words). Add a chapter by adding `NN-slug.md` with the same front matter.
2. **Re-take the screenshots**: `python3 docs/screenshots/capture.py` (device on USB, outside the bedtime window: it refuses within 20 minutes of it, adb, root; see its header).
   It drives the app through the debug key hook, restores the device's state, and blurs phone numbers, email addresses and
   Wi-Fi names. Never keep unblurred PNGs under `docs/screenshots/`. Look at every image after a run.
   The Home Assistant device page is captured through Claude in Chrome (a new tab on the user's Home Assistant, signed in
   already; never type credentials); `capture.py` documents the steps.
3. **Rebuild the derived files**: `python3 docs/guide/build.py` resolves `{{shot:name}}` markers to image links with captions
   from `docs/screenshots/manifest.json`, writes `docs/guide/SCRIPT.md` (all narration, timed) and `chapters.json`. CI-style
   check: `python3 docs/guide/build.py --check`.
4. **Placeholder video** (until the real one exists): `python3 deploy/help/make_stub.py push` makes `help.mp4` + `help.json`
   from the chapters (macOS `say` voice, one title card per chapter) and puts them on the player (`/sdcard/Help`). Check the
   chapter list on the player (menu, Help) and that a chapter plays.
5. **Tell the user what changed** in the guide, and anything you could not capture.

## Rules that matter

- Never leave the iPod armed for gifting, locked, loud, or with a different song playing than it had; `capture.py` restores
  state, check anyway. Never press anything that changes settings, favourites, the library, or uses the grown-up code.
- Blur: the "if found" phone and email lines on the lock screen and on the Home Assistant text entity, and every Wi-Fi network
  name (Settings, Wi-Fi, Status, the Wi-Fi sensor and home Wi-Fi text). Freya's name is fine.
- Keep claims true: run the feature, do not describe it from memory. README, `docs/NOTES.md` and the guide must agree.
- The video's real chapters come from `chapters.json`/`help.json`; chapter titles are what the child sees in the Help menu.
