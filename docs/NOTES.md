# Development notes

How the pieces were built and the traps found on the way. The README is the place to start.
This file is a build log in time order: later sections supersede earlier ones, and the first sections are history
(the prototype below has long since grown into the player described later).


Prototype (historical): Sendspin player for the Innioasis Y1 (Solar firmware, Android 4.2.2).
`sendspin_min.py` is stdlib + `cryptography` only, written so it can be ported line by line to Java.

Proven  against Music Assistant 2.10.7 in Docker, client-initiated on port 8927:
Noise KKpsk2 (25519/ChaChaPoly/SHA256) as responder with the Sentinel PSK, hello, activate,
clock sync, PCM 44.1k/16/stereo chunks. Received WAV matched the source tone (440 Hz L, 660 Hz R, no gaps).

Java port (`java/`, Java 7 syntax, Android 4.2 APIs only; `CryptoSelfTest` checks RFC 7748/8439 vectors) passes the same end-to-end test on a desktop JDK and persists its identity key.
Received audio has the right tones and level with no gaps; it is not sample-exact because Music Assistant processes it.

Android app (`java/android`, `java/build_apk.sh`, targets API 17, compiled clean against Android 4.2.2's own class library): runs the whole flow in an Android 5.0.2 emulator against the test server,
with AudioTrack consuming 528,960 frames in 12.0 s wall (real time). Audibility was not checked (headless emulator).

Everything the prototype lacked (running on the real Y1, a persisted client key, AirPlay to Sonos, Apple Music) has since
been done; only a Kalman time filter is still missing (the clock uses the best-RTT offset).

Test rig for the Music Assistant side: `docker run -d --name ma-sendspin-test -p 18095:8095 -p 18927:8927 -v $PWD/data:/data ghcr.io/music-assistant/server:latest`,
create the admin via POST /setup, run the client, wait ~25 s for the universal player, then call
`player_queues/play_media` on its websocket API with a tone served from inside the container (Docker Desktop cannot
reach host ports). The helper script for that is not published.

## Y1 audiobook player (current app)

The APK is now a full player for the child, not just a Sendspin test client. It talks to Music Assistant over its
websocket API and lists the audiobooks in MA's library.

- **Listen on**: "This Y1" plays the book from a local file (or streams it); any Sonos speaker plays it through MA and the Y1
  becomes its remote (play/pause, 30 s skip, wheel volume). Switching target moves the place with it.
- **Offline**: Download fetches the original m4b from a read-only file server (`deploy/audiobook-files.yaml`, nginx on the
  MA node, path-prefix secret). Downloads resume after a Wi-Fi drop. Offline books play with no network at all.
- **Place + sync**: every book keeps `{pos, synced}`. Unsynced positions are pushed to MA (`music/mark_played`) as soon as the
  Y1 reaches it, and MA's position wins whenever the Y1 has nothing newer. The resume point shows in MA's own UI.
- **Music**: playlists, albums, radio and ambient sounds from MA. "This Y1" starts the Sendspin speaker on demand
  (idle after 10 minutes, or "Y1 as a speaker: always on" in Status).
- Media buttons work with the screen off (MediaButtonReceiver).

Deploy: `deploy/deploy_y1.sh` (builds, installs, provisions host and the child's token from `deploy/secrets/`, gitignored).
Test without touching the wheel: `adb shell am broadcast -a me.cns.ab.KEY --ei code N` presses a key in the app (a
root-only broadcast; see Hold). `adb kill-server` fixes a hung adb. (My local helper scripts for this are not published.)

(Historical: Apple Music is now the core library feature, added as a provider in MA's web UI. Still open: a Kalman clock
filter for Sendspin. The click-wheel direction is a setting; `Last key` in Status shows what each control sends.)

## Music sync

`deploy/music-sync/` + `deploy/music-sync.yaml`: a service in the cluster (Music Assistant image) that records every track of one
MA playlist (or, with LIBRARY_PROVIDER, the child's whole Apple Music library) to 192 kbps stereo MP3 through MA itself (Apple Music can only be decrypted by MA), with cover jpgs and
a `manifest.json`, into `/var/lib/music-sync`. The file server publishes it under `<prefix>/music/`. 8 capture players are started (`WORKERS`); how many run at once is learned and backed off when Apple refuses, which in
practice settles around 7; it pauses while anyone plays a music track elsewhere. The playlist is re-read every 5 minutes, new tracks jump the
backlog, removed tracks are deleted. The Y1 (`MusicLibrary`) mirrors the manifest to `/sdcard/Music`, plays from files (next/previous,
shuffle) and, with a speaker as target, tells MA to play the playlist from the same track. Position is not kept for music.
Create the service: token secret `music-sync-token`, configmap from `deploy/music-sync/`, `kubectl apply -f deploy/music-sync.yaml`.

## Notes from the music build 

- **Throughput of the sync is bound by Music Assistant's real-time pacing** (30 s run-ahead, then 1x per stream) and Apple's cap of
  about 7 simultaneous streams, so about 140 songs an hour. Reported output delay does not lift it; extra MA copies would not either.
  A fake DLNA renderer (MA gets a plain HTTP URL which is not paced) is the only route to faster seeding; not built.
- `deploy/seed_usb.sh music` copies what is finished to the iPod over USB (6.5 MB/s vs about 2 MB/s on Wi-Fi).
- Playlists: Apple's API cannot remove tracks or create playlists (MA can only add). Duplicates were removed with AppleScript in
  the Mac's Music app (68,406 entries -> 1,482 songs; old one kept as the old one). MA returns long lists in
  batches of 500 marked `partial`; read until the final message.
- Solar: top-level home menu is `home_menu_order` in its SOLAR_SETTINGS.xml (set to `apps,radio,settings`); "Apps" lists the player.
  Solar's "custom home app" target did not take for our app, so instead PlayService (always on) relaunches the activity if it is gone
  and on screen-on. The wheel's physical events: rotation = KEY_RIGHT/KEY_LEFT (clockwise = RIGHT), centre = KEY_REPLY (232, arrives
  as ENTER), Back/Menu/Next/Prev/PlayPause on the touch pad device.

## The player ROM (Solar without Solar)

`rom/make-player-rom.sh` turns a fresh Solar flash into the slim build: Solar's launcher, home helper, overlays, watchdogs,
rescue daemon, Xposed modules and updater are removed; `rom/overlay/99PlayerInit.sh` keeps the useful boot bits (wheel key
layouts, GPU tweak, rotation lock, Bluetooth track-info file, storage folders); Y1Bridge (Bluetooth AVRCP) stays.
The player is the home screen and provides what Solar did: Settings (Wi-Fi join with a wheel text entry covering all printable
ASCII plus £ €, Bluetooth headphones pairing/connect, brightness, screen timeout, sleep timer, volume limit, wheel click),
battery in the header, power menu on a long press of Menu, long press of Play to sleep. Root (`su`) does sleep/power.
Gone with Solar: FM radio, USB mass-storage mode, Solar OTA updates.
Why not patch Solar: PR #81 hard-codes `LauncherPreference.getHomeTarget()` to Solar and `applyHomeTargetWithComponent()`
forces every target to Solar, and `reconcileHomeTargetFromProperty()` re-applies it on every start, so a custom home never sticks;
its rescue script also disables any extra HOME app.

## Find My beacon

The iPod advertises Apple offline-finding packets so passing iPhones report where it is (Settings > Find My beacon).

- Keys: `rom/findmy/findmy_keys.py haystack` (one static OpenHaystack key, fetch reports with FindMy.py) or
  `accessory FILE.json` (the rotating 15-minute keys of a real Find My item, exported on the Mac). `push` copies
  `findmy.bin` to `/sdcard/FindMy/` and tells the app. Secrets live in `deploy/secrets/findmy/` (gitignored).
- Radio: Android 4.2 has no LE advertising API, so `java/android/native/fmhci.c` (static ARM binary, `build.sh`,
  bundled as an APK asset) holds `/dev/stpbt` as root and writes raw HCI. Only while Android's Bluetooth is off:
  switching headphones on stops the beacon first, switching them off brings it back. It runs alongside Wi-Fi.
- With Bluetooth headphones on, the radio is Android's, so a driver shim in the ROM (`native/btshim.c`,
  `rom/bt/install_shim.sh`) carries the beacon inside Android's own Bluetooth session. The app writes its config
  (`/data/local/tmp/fmbeacon.hex`) from the key. The classic Bluetooth address is pinned once in
  `/data/local/tmp/fmbeacon.addr` (the first key's) and set at every Bluetooth enable, so paired headphones keep
  working; the shim only advertises a config for that address. So with Bluetooth on, only a static key works; a new
  static key needs the pin file removed and the headphones paired again.
- Never send LE Set Advertising Parameters (0x2006): this chip's firmware hangs on it and the kernel resets the whole
  chip, Wi-Fi included. The beacon uses the default parameters and sets the address with MediaTek's FC1A instead
  (`rom/bt/NOTES.md`).
- Never read `/dev/stpbt` with a blocking read: MediaTek's `BT_read` sleeps uninterruptibly (`wait_event`), and a
  shell `cat` reader stuck there holds the radio until a reboot. `fmhci` opens it `O_NONBLOCK` and reads only after
  `poll()`; it is always killable.
- Never toggle Android's Bluetooth while something holds `/dev/stpbt`: it once left Wi-Fi (same combo chip) in
  "supplicant UNINITIALIZED" until a reboot.
- The first LE advertising enable straight after an HCI reset is silently ignored, so the app re-asserts it 2 s later
  and every 5 minutes.

## Bedtime limit (shared with Home Assistant)

The integration provides four bedtime controls: a time for the window start (default 19:45), a time for the window end
(default 06:30), a number for the allowance in minutes (default 20) and a counter of used minutes. To share the budget
with an existing setup, link each one in the integration's options (`link_bedtime_start` and `link_bedtime_end` to
input_datetime entities, `link_bedtime_cap` and `link_bedtime_used` to input_number entities). The player adds its own
playing minutes to the used counter (a speaker in her room is counted by Home Assistant itself), and locks to a
night-sky screen when the budget is spent.

- How it talks to HA: through its own integration, Y1 Player (`custom_components/y1_player`), which
  replaced the earlier mobile_app device. The player has an X25519 key pair (`files/device.key`, made on first run,
  never leaves it) and holds no Home Assistant token. `deploy/y1_setup.py pair` reads its public key over adb, adds it
  to the integration (config flow, unique id = the key), links the bedtime controls to existing helpers if asked, and
  pushes `files/ha.json` (webhook, cloudhook, the integration's public key, the unlock secret). Each sync is one
  ChaCha20-Poly1305 message (keys from HKDF over the X25519 secret, counter against replay) carrying sensors, location,
  played minutes {minutes, seq} and unlocks; the sealed reply carries every setting, the bedtime budget and any find
  request. Settings carry a change time each way: newest wins, and a new pairing adopts the player's current values.
  Wire details (both sides): a reply is only accepted if its sealed `c` equals the request's counter (a 409 carries
  `last` plus `rc`, the request counter); the sealed `now` sets the clock (never the HTTP `Date`), and the reply returns
  `played_seq`. The played batch {seq, n} is frozen on the player and resent unchanged until `played_seq` reaches its
  seq; a `played_seq` ahead of the player's seq means the batch was dropped and the player adopts it and resends. The
  integration holds counted minutes in `pending_minutes` until a linked helper is readable, ignores device setting
  stamps more than 5 minutes ahead and values outside the setting's definition, and five 400s in a row mark the
  player as not paired. Replies from an older integration lack `played_seq`/`rc` and are handled as before.
- Limits of read-only (tested 26 Oct): it cannot control entities, but HA cannot stop a mobile app device calling
  services that target no entity: scripts called directly (`script.alarm_disarm`...), shell_command, rest_command,
  notify. Accepted as enough; the full lockdown (webhook triggers only, settings via music-sync) was offered and declined.
- In HA it is the device (named after the player, e.g. "Y1") with sensors `sensor.<device>_*` / `binary_sensor.<device>_*`: battery,
  charging, now playing (artist, kind, playing), playing, listen on, bedtime minutes used/left, bedtime locked, buttons on
  hold, Find My, Wi-Fi. Sent every minute in the window (5 min outside) and straight away on play/pause, track, hold or
  lock changes; sensors HA does not know yet are registered with their names on the fly.
- Location: `device_tracker.<device>` is "home" on home Wi-Fi (the home Wi-Fi names set in Home Assistant,
  "your home Wi-Fi IoT"), "not_home" on other Wi-Fi (a hotspot), and the GPS coordinates of the last fix otherwise, plus
  `sensor.<device>_last_gps_fix` (time, with latitude/longitude/accuracy attributes). GPS only listens away from
  Wi-Fi: up to 2 minutes, at most every 30 minutes, stopping at the first fix, and mostly hears nothing indoors. A fix
  made with no Wi-Fi is sent at the next connection (last seen). Test hooks: 992 pretend away (off again clears any
  test fix), 993 a pretend fix at Trafalgar Square.
- If the iPod is lost: delete the device in HA (Settings > Devices). Its webhook can call HA services, so that revokes it;
  the iPod then shows "removed from Home Assistant" (410). Re-register with `child` and `push`.

- Offline-proof: settings and the counter are cached (`Bedtime`, pure Java, `java/android/test/run.sh`); minutes
  played offline are sent later. Time comes from `TrustedClock`: NTP on any Wi-Fi, the sealed `now` in HA's reply, GPS away from
  Wi-Fi, and elapsedRealtime in between (a flat battery cannot wind it back).
- Local time anywhere: `Geo` sets the timezone from a GPS fix via the offline map `assets/tzgrid.bin`
  (`java/android/tools/make_tzgrid.py`, timezonefinder data) or GeoIP (ip-api.com) on Wi-Fi.
- DST: the stock 2012 tz database is wrong for places that changed since (Turkey, Mexico, Brazil...). Install a
  current one: `rom/tzdata/make_android_tzdata.py` builds the Android 4.2 zoneinfo files (`rom/tzdata/legacy`) from
  the Mac's IANA zoneinfo; `rom/tzdata/install_zoneinfo.sh` installs them (also run by `rom/make-player-rom.sh`).
- Grown-up code: 5-digit TOTP (30 s, each code once, accepted up to 90 s late) lifts the limit for 8 hours and
  notifies a parent's phone. `deploy/y1_setup.py secret | op VAULT | check | pair`.
- HA over HTTPS on Android 4.2 needs Conscrypt (`java/android/libs`, TLS 1.2 GCM) and the roots in `assets/certs`:
  Let's Encrypt (chain is YR1 > Root YR > ISRG Root X1) and Amazon Root CA 1 (hooks.nabu.casa: without it the
  cloudhook, so syncing away from home, failed with "Trust anchor for certification path not found"). URLs: your Home Assistant, then the Nabu Casa remote URL.
- Traps: never pipe secrets into `adb shell cat` (some adb/device pairs echo stdin back, and it hangs without EOF):
  `adb push` a temp file. Android 4.2/5 have no `stat`/`awk`: get the app uid from `dumpsys package`.

## Look and feel

Hand-drawn, iPod classic layout with today's Apple finish: Inter (assets/fonts, OFL), animated gradient highlight,
slide transitions with parallax, hairlines and chevrons, label/value settings rows, an artwork-tinted Now Playing with
an iPod-style volume overlay, gliding lyrics, cover flow on black, passcode dots for the grown-up code, night theme in
the bedtime window. Design previews via the root-only key hook: 997 Now Playing for the highlighted book (no
playback), 996 lock screen, 995 night theme. Emulator: the y1test AVD is arm64-only, so install without lib/.

## Hold

Like the iPod's hold switch, in software (`KeyHold.java`, unit-tested in `test/BedtimeTest.java`). After a quiet spell
(Settings > Hold after: 30 s, 1 min (default), 2 min, 5 min or never) the buttons stop doing anything. While something
plays the screen stays on Now Playing with the unlock prompt over the scrubber; otherwise it shows the time, the owner's
name with "iPod" and how to return it (an email address). No "unlocked" message: the screen coming back says it. Pressing the same button 3 times within
the unlock time (Settings > Unlock: 0.5 to 2 s, default 1 s) brings them back; the screen counts the presses with dots and
says how many more of which button. Wheel turns never count, so a wheel rubbing in a pocket cannot unlock it, and none of
the unlocking presses act. Headphone buttons (the "AVRCP" input device) still play and pause, as with an iPod's remote.
Power menu > Hold locks straight away. Test hook: `am broadcast -a me.cns.ab.KEY --ei code 994` holds now; raw centre
presses for testing: `sendevent /dev/input/event0 1 232 1/0` (the wheel itself is `mtk-tpd`, event2, scancodes 105/106).


## Birthday features 

- Clean versions: music-sync records Apple's clean version of any song Apple marks explicit, where the catalogue has one
  (same title and artist, within 10 s; `meta["clean"]`, looked up again weekly when there is none). Songs synced before
  are re-recorded clean. Explicit songs with no clean version stay, without lyrics.
- Library-only songs (`apple_music://track/i.…`, which MA cannot stream) are recorded from their catalogue version
  (`meta["catalog"]`); a version MA cannot stream either is ruled out (`catalog_bad`). Songs with no catalogue copy are
  listed as "not in Apple Music" (manifest `unavailable`), not "being prepared".
- New songs: the manifest stamps when each song first appeared (`added`, `meta["first_seen_v3"]`); Music shows the last
  30 days, newest first.
- Favourites: hold the centre button on a song (or on Now Playing) to add or remove it. Kept on the iPod, mirrored to the
  MA playlist "<owner>'s Favourites" (Favourites.java; the iPod is the source of truth).
- Chapters: from MA's audiobook metadata, cached in files/chapters/ for offline. Next/Previous jump chapters (Previous
  within 4 s of a start goes back one); holding them, or fast forward/rewind, skips 30 s. Book page has a Chapters list,
  Now Playing shows "Chapter N of M".
- Find it: the integration's Find it button. The iPod syncs every 30 s on Wi-Fi and rings a
  chime on the alarm stream at full volume for 60 s (any button stops it). Presses older than 10 minutes are ignored.
- Self-healing audio: playing on the iPod but the position stuck 8 s (15 s while preparing) restarts the player where it
  was; still stuck 15 s later on Bluetooth reboots the iPod, at most once in 6 hours (`watchdogRebootAt`). Restarting
  Bluetooth alone is never used: it breaks A2DP on this chip until a reboot.

## Review fixes 

An adversarial review (26 Oct 2026; the report is not published) confirmed 70 defects; all were fixed. Things that changed behaviour:
- adb is locked to a key file on the device: `99PlayerInit.sh` sets `ro.adb.secure=1` at boot when `/data/misc/adb/adb_keys`
  exists and is not empty (no keys file: left open, nobody locked out). A fresh provision installs no key, so a new unit
  stays open (and root); to lock it, append your `~/.android/adbkey.pub` to that file as root (see PROVISIONING.md). A
  computer whose key is not listed gets "unauthorized". The same script resets MediaTek's xlog
  filter to info after the adbd restart (otherwise only errors are logged).
- Home Assistant payloads are encrypted end to end (now by the Y1 Player integration, see above), so the Nabu Casa
  cloudhook sees ciphertext. Played minutes carry a `seq` the integration dedupes; failover to the cloudhook only when
  nothing was sent.
- Bedtime: HA's counter is adopted only for the same night (template `night`, HA's own time zone), not in the first
  2 minutes of the window, and never when it is unusable (counts locally instead). Next/Previous respect the lock.
  The grown-up session uses elapsedRealtime and ends when the screen goes off. Joining or forgetting Wi-Fi needs the code.
- An alarm wakes the iPod every 2 minutes (Find within ~2 min when idle); a wake lock is held while a counted speaker
  plays. The watchdog only watches local files, never resets during its own restart, keeps the book's place on reboot.
- The library is read from the card at start (works offline), pruned before the manifest is saved, re-fetched when a
  track's `rev` changes, `.part` files are per revision and size-checked, downloads stop with 200 MB left.
- Speakers play the recorded (clean) version, the queue is remembered, paused music stays paused across handovers,
  pause is idempotent, someone else's audio on a speaker is ignored.

## Search 

Music > Search: wheel text entry (space first in the letters), Music Assistant `music/search` as the iPod's MA user
(the child, so only her Apple Music). Songs / Albums / Artists, explicit items hidden (`metadata.explicit`). Each find:
Play (streams via `playMusic`, on the chosen speaker or the iPod's own), Add to my library (`music/library/add_item`
with her Apple Music instance URI from the provider mappings; a plain `apple_music://` URI may add to another account),
album Songs, artist Top songs / Albums. Added songs reach the iPod through the normal sync: music-sync asks MA to
re-read her library every 15 minutes, records the song, the iPod downloads it.
Music library sync now reads the child's whole Apple Music library (LIBRARY_PROVIDER) and records as her (PLAY_TOKEN_FILE).
Streaming fix: Poly1305 in 26-bit limbs (Crypto.java); the BigInteger version could not keep up with PCM on the Y1.
Lyrics: LRCLIB first, then Music Assistant's lyrics providers (Genius); plain lyrics are marked [unsynced:1] and scroll
without a highlighted line.
- Remove from my iPod (search page of a synced song): Music Assistant `music/library/remove_item` as the child, which the
  local MA patch (`deploy/music-assistant-patch/sitecustomize.py`) makes delete from her Apple Music library
  (only accounts she can see), plus the "the player: removed" playlist that music-sync leaves out. Re-adding from
  search takes it off that list.

## Voice search

- The Y1 has an unadvertised microphone that works (AudioRecord, VOICE_RECOGNITION, 16 kHz mono). Hold the centre
  button (not on a song row or Now Playing, where hold still favourites) to talk; let go to search. Up to 10 s.
- Speech to text: Home Assistant's speech-to-text through the Y1 Player integration when on Wi-Fi (option `stt_entity`,
  default Home Assistant Cloud, ~1.7 s), on the iPod when offline or when the "Voice on the iPod only" setting is on.
  On the iPod: `files/stt/y1-stt` + `files/stt/model.bin`, built and installed by `java/android/native/stt/build.sh
  install` (whisper.cpp v1.7.6 libraries for ARMv7 via zig, `y1-stt.cpp` front end, tiny.en q5_1): 3 to 5 s.
- Traps found getting whisper usable on the Y1 (one 1.3 GHz Cortex-A7 in practice):
  - MediaTek's hotplug governor keeps the second core off and takes it away mid-run even with `cpu_hotplug_disable`;
    ggml's worker threads spin waiting for each other, so `-t 2` on one core took minutes. Always one thread.
  - whisper-cli on a short, noisy clip loops ("give you better give you better ..." for 1,112 tokens with temperature
    fallback: 3 minutes). y1-stt: greedy, single segment, no fallback, 24 tokens at most, and a logits filter that
    forces end-of-text after the first sentence or the first repeat.
  - The encoder window (`audio_ctx`) cut to the audio itself; trailing silence invites repeats. The app trims quiet
    around the speech against the recording's own noise floor (the Y1 mic hears its room at rms 100 to 1500).
  - Building: CMake mangles a compiler named "zig cc", and macOS ar/ranlib cannot index ARM ELF objects (every symbol
    then undefined at the link): build.sh uses one-word zig wrappers for cc, c++, ar and ranlib.
- Results: songs on the iPod first (word match on title, artist, album, one letter of slack per word so "team spirit"
  finds "Teen Spirit"), then Music Assistant's search (songs already on the iPod left out), albums, artists.
- Test hook 990 recognises `/sdcard/voice-test.pcm` (16 kHz mono s16le) as if spoken: `say -o q.aiff "..." &&
  afconvert -f WAVE -d LEI16@16000 -c 1 q.aiff q.wav && tail -c +45 q.wav > q.pcm`.
- A real press for testing: `sendevent /dev/input/event0 1 232 1` (centre, mtk-kpd), then `... 1 232 0` to let go.

## Help video and gifting

- `Help.java`/`HelpActivity.java`: plays `/sdcard/Help/help.mp4` in a VideoView from a chapter's start time and keeps
  going; chapters come from `/sdcard/Help/help.json` (`{"chapters": [{"title": "Intro", "start": 0}, ...]}`, seconds). Wheel,
  Next and Previous change chapter (Previous restarts the chapter first), the middle button pauses, Back or Menu leave. The
  chapter name shows for 3 s on each change. The video must be baseline H.264 + AAC (`deploy/help/make_stub.py` shows the
  ffmpeg settings the MT6572 decodes: 480x360, 15 fps).
- "Armed for gifting" (`giftArmed`, setting `gift_armed`, Home Assistant switch): arming engages the button hold; the next
  unlock (`UiView.holdKey`, left == 0) disarms and plays chapter 0 at full volume (the volume limit stands aside while
  `HelpActivity.open`; the previous volume returns after). Tested awake, locked, three Menu presses. With the screen asleep
  (injected presses do not wake it) Help paused and closed within 150 ms; a real tap wakes the screen first, and the awake
  path is the one verified.
- Test: `su -c am start -n me.cns.sendspin.android/.HelpActivity --ei chapter 3 --ez loud true`. adb dropped for a minute
  when the video first started (USB blip, the iPod stayed up); `adb kill-server` brought it back.
- An empty 200 from Home Assistant (webhook not registered, e.g. while it restarts) used to mark the player "removed"
  until the app restarted; it is now a retryable error.
