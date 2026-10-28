# music-sync as its own image: the stock Music Assistant image (it has python, cryptography and ffmpeg with libmp3lame)
# plus the two scripts. Built and published by .github/workflows/image.yml to ghcr.io/chrisns/sendspin-y1-music-sync.
# Renovate keeps the base tag current; bump it together with your Music Assistant server.
FROM ghcr.io/music-assistant/server:2.10.7
COPY music_sync.py sendspin_min.py /app/music-sync/
ENTRYPOINT ["/app/venv/bin/python", "-u", "/app/music-sync/music_sync.py"]
