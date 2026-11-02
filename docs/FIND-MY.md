# Find My on the Y1

The Y1 advertises Apple offline-finding packets, so passing iPhones report where it is, even with no Wi-Fi. There are two
ways to give it an identity. Both use `rom/findmy/findmy_keys.py` and the same files on the player.

| | Needs | Shows up as | Lasts |
|---|---|---|---|
| **Own key (OpenHaystack style)** | nothing but a computer | not in the Find My app; you fetch reports yourself with [FindMy.py](https://github.com/malmeloo/FindMy.py) | for ever |
| **A real tag's keys** | a Find My compatible tag, paired to your Apple ID, and a Mac to take the keys from | an item in Apple's Find My app, like any other | rotating keys never run out; the fixed key (see below) lapses after about a week |

Nothing here is secret-free: a key file lets anyone broadcast as that item. Keep `deploy/secrets/` out of git (it is
gitignored) and never post key files, screenshots of them, or the tag's serial.

## 1. Own key

```
rom/findmy/findmy_keys.py haystack     # makes a key, keeps the private half in deploy/secrets/findmy
rom/findmy/findmy_keys.py push         # copies findmy.bin to /sdcard/FindMy/ on the player over adb
```

Then fetch locations with FindMy.py and the private key in `deploy/secrets/findmy/`.

## 2. A real tag's keys (shows in the Find My app)

You need a tag that speaks Find My (an Apple AirTag works, as do third-party tags that say "works with Apple Find My").
Pair it to your Apple ID once, on the iPhone or Mac. Then you need its **master keys**, exported as the FindMy.py JSON:

1. On a Mac signed in to the same Apple ID, the keys live in the keychain-protected BeaconStore. Since macOS 15 only an
   Apple-signed, entitled process may read the BeaconStore key, so the ready-made exporters stop at an error
   (`-80045`) on a normal Mac. [stek29/export-findmy](https://github.com/stek29/export-findmy) and FindMy.py's own tools
   show the shape of what is wanted. I got past the entitlement on my own Mac by temporarily turning SIP and AMFI off,
   running a small self-signed extractor, and turning them back on. That weakens the machine while it is off: do it only
   on your own Mac, for your own tag, and put the protections back afterwards. Decrypting the records is AES-GCM with
   the layout `[nonce 16][tag 16][ciphertext]`.
2. Save the item as JSON (the FindMy.py `FindMyAccessory` format: master public key, shared secret, secret keys, and the
   alignment date and index). Put it somewhere gitignored, for example `deploy/secrets/findmy/accessory.json`.
3. Make the file for the player:
   ```
   rom/findmy/findmy_keys.py compact deploy/secrets/findmy/accessory.json
   rom/findmy/findmy_keys.py push
   ```
   `compact` writes the 140-byte `FMB2` file: the master **public** key, the secret for the current slot, and one fixed
   key. The player works out each 15-minute key itself, so it never runs out. The private key is not on the player,
   so a lost player cannot be used to read where the item has been. Use `--no-fixed` to leave the fixed key out.
4. The old tag can go: the player now answers for that item. Remove the physical tag from your account only after it
   shows up in Find My from the Y1, and keep your exported JSON.

How the key is derived is in `docs/NOTES.md` (SHA-256 chain, then `u*P + v*G` on P-224) and
`java/android/src/me/cns/sendspin/android/FindMyDerive.java`, with a test against FindMy.py in `test/FindMyDeriveTest.java`.

## 3. The radio

- Bluetooth off: a small root helper (`java/android/native/fmhci.c`, shipped inside the app) writes raw HCI to
  `/dev/stpbt`, with rotating keys.
- Bluetooth on (headphones): the driver shim in the ROM, installed with `rom/bt/install_shim.sh` (or `--shim` when
  provisioning), carries one fixed key at a pinned Bluetooth address, so headphones stay paired.
- The app switches between them by itself (`BtPolicy`): Bluetooth goes off after 10 quiet minutes (screen idle, nothing
  playing, no headphones), and back on when someone uses the player.
- Never send `LE Set Advertising Parameters` (0x2006): this chip's firmware hangs. Never switch Bluetooth with
  `service call bluetooth` while the helper is stopped.

## Limits

- **No Play Sound.** The chip never accepts an LE connection, so the button in Find My cannot ring it.
- **The fixed key lapses.** Apple keeps reports for about seven days, so the fixed key (used while headphones are in
  use) is findable for roughly a week after its slot. Make a new file with `compact` and pair the headphones again.
- **Only tested** on one Innioasis Y1, Type A, on the Solar ROM.

Details, failures and numbers: [`docs/NOTES.md`](NOTES.md) and [`rom/bt/NOTES.md`](../rom/bt/NOTES.md).
