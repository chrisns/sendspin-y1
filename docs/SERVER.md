# The server side: Music Assistant, music-sync, the file server

The player needs a [Music Assistant](https://music-assistant.io/) (MA) server on the home network, the child's Apple Music
added to it, and two small services next to it. This is tested on k3s (the manifests are Kubernetes); the same containers
should run under Docker with the same environment and mounts (untested).

```mermaid
flowchart LR
    AM["Apple Music (child's account)"] --- MA["Music Assistant 2.10 + patch"]
    MS["music-sync (MA image)"] -->|"plays each song to a hidden Sendspin player"| MA
    MS -->|"192k MP3, cover, lyrics, manifest.json"| D[("/var/lib/music-sync")]
    D --> FS["audiobook-files (nginx, :18099)"]
    FS -->|"downloads"| Y1["Y1"]
    MA <-->|"API :8095, Sendspin :8927"| Y1
```

## 1. Music Assistant

- Add the child's Apple Music as its **own provider instance** (Settings > Providers). Note its instance id
  (`apple_music--XXXXXXXX`).
- Create a **Music Assistant user for the child** (role *user*) restricted to their own provider and to the speakers they
  may use, and mint a long-lived token for it. MA picks the streaming account by the requesting user's provider filter, so
  the child's token streams from the child's account, never an adult's. Keep it in `deploy/secrets/ma_token`.
- An **admin token** for music-sync goes in the `music-sync-token` secret (key `token`); the child's token goes in the same
  secret as `play_token`.
- Optional patch: `deploy/music-assistant-patch/` makes "remove from library" work for Apple Music (stock MA cannot delete
  from Apple's library). It is a `sitecustomize.py` mounted into the stock image; see that folder's README. Without it,
  *Remove from my iPod* may leave the song in the child's Apple Music library, so it would come back on the next sync.

## 2. music-sync (`deploy/music-sync.yaml`, `deploy/music-sync/`)

Records the library to MP3 in real time (Apple Music can only be decrypted by MA): each song is played to one of up to 8 hidden
Sendspin players (`WORKERS`), captured and encoded, with cover art and lyrics (LRCLIB, then Genius), and a `manifest.json`
the player syncs from. It adopts clean versions of explicit songs, drops songs removed from the library, and learns how many
parallel streams Apple allows. Settings are environment variables: `LIBRARY_PROVIDER` (the instance id above),
`PLAY_TOKEN_FILE`, `OWNER_NAME`, `DEVICE_NAME` (the names shown on the player), `WORKERS`, `BITRATE`, `INTERVAL`, `TZ`.
Create the config map from the folder, put the secret in place, `kubectl apply`:

```
kubectl -n music-assistant create configmap music-sync --from-file=deploy/music-sync
kubectl -n music-assistant create secret generic music-sync-token --from-literal=token=ADMIN_TOKEN --from-literal=play_token=CHILD_TOKEN
kubectl apply -f deploy/music-sync.yaml
```

The first full sync is roughly real time (a library of over a thousand songs takes many hours).

## 3. The file server (`deploy/audiobook-files.yaml`)

A read-only nginx on port 18099 serving the audiobook volume and music-sync's output, so the player can download whole books
and songs. **The secret path prefix is the access control** (LAN only): replace `CHANGE-ME-RANDOM-PREFIX` in both `location`
blocks with `openssl rand -hex 12`, and give the player the same string (`gw_prefix`). Restart the deployment after changing
the config map (nginx does not reload by itself).

## 4. The player's connection

`python3 provision/y1.py provision` asks for the Music Assistant address, the child's token and the file-server prefix
and sets them in the app; or `MA_HOST=... deploy/deploy_y1.sh` with `deploy/secrets/ma_token` and `deploy/secrets/gw_prefix`.
Ports: Music Assistant API 8095, Sendspin 8927, file server 18099. Audiobooks come from MA's audiobook library, which
needs the books added there.

## Rotating the file-server prefix

Pick a new prefix, change both `location` blocks, apply and restart nginx, then re-provision the player (the app's
`gw_prefix`). The old prefix then answers 404.
