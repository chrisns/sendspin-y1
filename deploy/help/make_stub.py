#!/usr/bin/env python3
"""A placeholder Help video for the player: one title card per chapter of docs/guide, narrated with the guide's own words,
as help.mp4 + help.json.

  deploy/help/make_stub.py [push]     make it in deploy/help/out/, and with "push" put it on the player (/sdcard/Help)

The player just plays /sdcard/Help/help.mp4 and reads chapter names and start times from help.json next to it:
  {"chapters": [{"title": "Intro", "start": 0}, {"title": "Music", "start": 12.5}, ...]}
The real video replaces both files and nothing in the app changes. Needs ffmpeg, Pillow and (for the voice) macOS `say`.
"""
import json, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
FONT = os.path.join(HERE, "..", "..", "java", "android", "assets", "fonts", "Inter-Bold.ttf")
ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
sys.path.insert(0, os.path.join(HERE, "..", "..", "docs", "guide"))
import build as guide                   # the chapters and their narration live in docs/guide (the script and the docs are one)
CHAPTERS = [(c["title"], " ".join(c["narration"])) for c in guide.chapters()]
W, H = 480, 360

os.makedirs(OUT, exist_ok=True)
parts, chapters, t = [], [], 0.0
for i, (title, line) in enumerate(CHAPTERS):
    aiff, wav, png, mp4 = (os.path.join(OUT, "c%d.%s" % (i, e)) for e in ("aiff", "wav", "png", "mp4"))
    subprocess.run(["say", "-v", "Samantha", "-o", aiff, line], check=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", aiff, "-ar", "44100", "-ac", "2", wav], check=True)
    im = Image.new("RGB", (W, H), (16, 18, 28))
    d = ImageDraw.Draw(im)
    d.text((W // 2, 120), "%d of %d" % (i + 1, len(CHAPTERS)), font=ImageFont.truetype(FONT, 22), fill=(120, 130, 160), anchor="mm")
    d.text((W // 2, 190), title, font=ImageFont.truetype(FONT, 40), fill=(255, 255, 255), anchor="mm")
    d.text((W // 2, 300), "placeholder help video", font=ImageFont.truetype(FONT, 18), fill=(90, 100, 130), anchor="mm")
    im.save(png)
    secs = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav],
                                capture_output=True, text=True, check=True).stdout) + 1.0
    # baseline H.264 and AAC-LC: what the Y1's MT6572 can decode
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-loop", "1", "-i", png, "-i", wav, "-t", "%.2f" % secs,
                    "-af", "apad", "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p", "-r", "15",
                    "-g", "15", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-shortest", mp4], check=True)
    chapters.append({"title": title, "start": round(t, 2)})
    t += secs
    parts.append(mp4)
lst = os.path.join(OUT, "list.txt")
open(lst, "w").write("".join("file '%s'\n" % p for p in parts))
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                "-movflags", "+faststart", os.path.join(OUT, "help.mp4")], check=True)
json.dump({"chapters": chapters}, open(os.path.join(OUT, "help.json"), "w"), indent=1)
print("made", os.path.join(OUT, "help.mp4"), "%.0f s" % t, "%d chapters" % len(chapters))
if "push" in sys.argv:
    for f in ("help.mp4", "help.json"):
        subprocess.run([ADB, "-d", "push", os.path.join(OUT, f), "/data/local/tmp/" + f], check=True, capture_output=True)
    subprocess.run([ADB, "-d", "shell", "su -c 'mkdir -p /sdcard/Help && cp /data/local/tmp/help.mp4 /data/local/tmp/help.json /sdcard/Help/ "
                    "&& chmod 664 /sdcard/Help/* && rm /data/local/tmp/help.mp4 /data/local/tmp/help.json'"], check=True)
    print("pushed to /sdcard/Help")
