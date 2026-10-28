---
chapter: 11
title: That is everything
---

# 11. That is everything

*Have fun*

## Narration

> That is everything! Your music, your books, your favourites, and your voice.
>
> You can watch any part again from the Help list. Now go and enjoy your iPod.

## On screen

- ![Scroll down for Listen on, Settings, Status and Help.](../screenshots/02-menu-more.png) <!-- shot:menu-more -->
- ![The last row of the menu: a video that explains the iPod, in chapters.](../screenshots/29-help.png) <!-- shot:help -->

## For grown-ups

**Giftable setup.** *Armed for gifting* (the Home Assistant switch locks the player; arming from Settings with the grown-up code does not, so it can be cancelled there): the first three-tap unlock plays this video at full volume from the start, then disarms itself. If bedtime is locked at that moment nothing plays and it stays armed; with no Help video on the player it disarms itself, so a freshly set-up iPod can be handed over and explain itself.

**Making the video.** The player plays `/sdcard/Help/help.mp4` and reads chapter names and start times from `help.json` beside it. `docs/guide/build.py` writes the spoken script (`SCRIPT.md`) and `help.json` timings from these chapters; `deploy/help/make_stub.py` makes a placeholder video from the same text.
