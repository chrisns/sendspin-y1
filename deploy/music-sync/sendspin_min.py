#!/usr/bin/env python3
"""Minimal Sendspin player client: stdlib + `cryptography` only.

Prototype for the Innioasis Y1 / Solar port. Everything here is something the
Android 4.2 / Java 6 build must also do: RFC 6455 WebSocket client, Noise
KKpsk2 (25519 / ChaChaPoly / SHA256) as responder, framing, clock sync, and PCM
chunk handling. Keep it simple enough to translate line by line.

Usage: sendspin_min.py HOST PORT [--wav out.wav] [--seconds N]
Client-initiated connection to ws://HOST:PORT/sendspin.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import sys
import time
import wave

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat, NoEncryption

SENTINEL_PSK = hashlib.sha256(b"sendspin-sentinel-psk-v1").digest()
PROTOCOL_NAME = b"Noise_KKpsk2_25519_ChaChaPoly_SHA256"


def b64u(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64u_dec(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def psk_id(psk):
    return b64u(hashlib.sha256(b"sendspin-psk-id-v1" + psk).digest())


# ---------------------------------------------------------------- WebSocket

class WS:
    def __init__(self, host, port, path):
        self.s = socket.create_connection((host, port), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.sendall(
            (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
             f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
             f"Sec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.s.recv(4096)
            if not chunk:
                raise ConnectionError("closed during upgrade")
            buf += chunk
        head, self.buf = buf.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise ConnectionError("upgrade failed: " + head.decode(errors="replace"))

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise ConnectionError("closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, data, binary):
        op = 2 if binary else 1
        if isinstance(data, str):
            data = data.encode()
        n = len(data)
        hdr = bytes([0x80 | op])
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        mask = os.urandom(4)
        self.s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv(self):
        """Return (is_binary, payload) for one complete message."""
        msg, binary = b"", False
        while True:
            b0, b1 = self._read(2)
            fin, op, n = b0 & 0x80, b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            data = self._read(n)  # server frames are unmasked
            if op == 8:
                raise ConnectionError(f"server closed: {data[2:].decode(errors='replace')}")
            if op == 9:  # ping
                self.send_control(10, data)
                continue
            if op == 10:
                continue
            if op in (1, 2):
                binary = op == 2
            msg += data
            if fin:
                return binary, msg

    def send_control(self, op, data):
        mask = os.urandom(4)
        self.s.sendall(bytes([0x80 | op, 0x80 | len(data)]) + mask +
                       bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


# -------------------------------------------------------------------- Noise

def hmac_sha256(k, d):
    return hmac.new(k, d, hashlib.sha256).digest()


def hkdf(ck, ikm, n):
    tmp = hmac_sha256(ck, ikm)
    o1 = hmac_sha256(tmp, b"\x01")
    o2 = hmac_sha256(tmp, o1 + b"\x02")
    if n == 2:
        return o1, o2
    o3 = hmac_sha256(tmp, o2 + b"\x03")
    return o1, o2, o3


def dh(priv, pub_bytes):
    return priv.exchange(X25519PublicKey.from_public_bytes(pub_bytes))


def pub_bytes(priv):
    return priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def nonce(n):
    return b"\x00" * 4 + struct.pack("<Q", n)


class Symmetric:
    def __init__(self, prologue):
        self.h = hashlib.sha256(PROTOCOL_NAME).digest()  # name > 32 bytes
        self.ck = self.h
        self.k = None
        self.n = 0
        self.mix_hash(prologue)

    def mix_hash(self, d):
        self.h = hashlib.sha256(self.h + d).digest()

    def mix_key(self, ikm):
        self.ck, k = hkdf(self.ck, ikm, 2)
        self.k, self.n = k, 0

    def mix_key_and_hash(self, ikm):
        self.ck, th, k = hkdf(self.ck, ikm, 3)
        self.mix_hash(th)
        self.k, self.n = k, 0

    def encrypt_and_hash(self, pt):
        ct = ChaCha20Poly1305(self.k).encrypt(nonce(self.n), pt, self.h) if self.k else pt
        self.n += 1 if self.k else 0
        self.mix_hash(ct)
        return ct

    def decrypt_and_hash(self, ct):
        pt = ChaCha20Poly1305(self.k).decrypt(nonce(self.n), ct, self.h) if self.k else ct
        self.n += 1 if self.k else 0
        self.mix_hash(ct)
        return pt


class Transport:
    def __init__(self, send_key, recv_key):
        self.sk, self.rk, self.sn, self.rn = send_key, recv_key, 0, 0

    def encrypt(self, pt):
        ct = ChaCha20Poly1305(self.sk).encrypt(nonce(self.sn), pt, b"")
        self.sn += 1
        return ct

    def decrypt(self, ct):
        pt = ChaCha20Poly1305(self.rk).decrypt(nonce(self.rn), ct, b"")
        self.rn += 1
        return pt


def noise_respond(prologue, my_priv, server_pub, msg1, choose_psk):
    """KKpsk2 responder. Returns (msg2, Transport, msg1_payload_json)."""
    ss = Symmetric(prologue)
    ss.mix_hash(server_pub)          # <- initiator static (premessage)
    ss.mix_hash(pub_bytes(my_priv))  # -> responder static (premessage)
    # message 1: e, es, ss
    re_pub, rest = msg1[:32], msg1[32:]
    ss.mix_hash(re_pub)
    ss.mix_key(re_pub)               # PSK handshake: e token also mixes key
    ss.mix_key(dh(my_priv, re_pub))  # es
    ss.mix_key(dh(my_priv, server_pub))  # ss
    payload = json.loads(ss.decrypt_and_hash(rest))
    psk = choose_psk(payload)
    # message 2: e, ee, se, psk
    e_priv = X25519PrivateKey.generate()
    e_pub = pub_bytes(e_priv)
    ss.mix_hash(e_pub)
    ss.mix_key(e_pub)
    ss.mix_key(dh(e_priv, re_pub))       # ee
    ss.mix_key(dh(e_priv, server_pub))   # se
    ss.mix_key_and_hash(psk)             # psk2
    msg2 = e_pub + ss.encrypt_and_hash(b"{}")
    c1, c2 = hkdf(ss.ck, b"", 2)         # c1: initiator->responder
    return msg2, Transport(send_key=c2, recv_key=c1), payload


# ------------------------------------------------------------------- client

class Client:
    def __init__(self, host, port, name="Y1 prototype"):
        self.host, self.port, self.name = host, port, name
        self.priv = X25519PrivateKey.generate()
        self.client_id = b64u(pub_bytes(self.priv))
        self.t0 = time.monotonic_ns()
        self.offset_us = None   # server_time - local_time, best (min RTT) sample
        self.best_rtt = None
        self.wav = None
        self.fmt = None
        self.chunks = 0
        self.pcm_bytes = 0

    def now_us(self):
        return (time.monotonic_ns() - self.t0) // 1000

    def connect(self):
        self.ws = WS(self.host, self.port, "/sendspin")
        init = json.dumps({"type": "client/init", "payload": {
            "client_id": self.client_id, "version": 1, "suite": "25519_ChaChaPoly_SHA256"}},
            separators=(",", ":"))
        self.ws.send(init, binary=False)
        _, server_init = self.ws.recv()
        si = json.loads(server_init)
        if si["type"] != "server/init":
            raise RuntimeError(f"expected server/init, got {si}")
        self.server_id = si["payload"]["server_id"]
        _, hs = self.ws.recv()
        msg1 = b64u_dec(json.loads(hs)["payload"]["data"])
        prologue = init.encode() + server_init
        known = {psk_id(SENTINEL_PSK): SENTINEL_PSK}

        def choose(p):
            print("  msg1 payload:", p)
            return known.get(p["psk_id"], SENTINEL_PSK)  # lookup miss -> Sentinel fallback

        msg2, self.tr, _ = noise_respond(prologue, self.priv, b64u_dec(self.server_id), msg1, choose)
        self.ws.send(json.dumps({"type": "noise/handshake", "payload": {"data": b64u(msg2)}},
                                separators=(",", ":")), binary=False)
        print("handshake done; server_id", self.server_id)

    def send_json(self, obj):
        data = json.dumps(obj, separators=(",", ":")).encode()
        self.ws.send(self.tr.encrypt(b"\x00" + data), binary=True)

    def recv_msg(self):
        _, ct = self.ws.recv()
        pt = self.tr.decrypt(ct)
        return pt[0], pt[1:]

    def send_time(self):
        self.t_sent = self.now_us()
        self.send_json({"type": "client/time", "payload": {"client_transmitted": self.t_sent}})

    def on_server_time(self, p):
        t4 = self.now_us()
        t1, t2, t3 = p["client_transmitted"], p["server_received"], p["server_transmitted"]
        rtt = (t4 - t1) - (t3 - t2)
        off = ((t2 - t1) + (t3 - t4)) // 2
        if self.best_rtt is None or rtt <= self.best_rtt:
            self.best_rtt, self.offset_us = rtt, off

    def run(self, seconds, wav_path):
        self.connect()
        self.send_json({"type": "client/hello", "payload": {
            "client_id": self.client_id,
            "name": self.name,
            "device_info": {"product_name": "sendspin_min", "software_version": "0.1"},
            "supported_roles": ["player@v1"],
            "player@v1_support": {
                "supported_formats": [{"codec": "pcm", "channels": 2, "sample_rate": 44100, "bit_depth": 16}],
                "buffer_capacity": 1048576},
            "supported_pair_methods": {"pairing_psk": {}},
            "unpaired_access": {"enabled": True}}})
        deadline = time.time() + seconds
        state_sent = False
        self.ws.s.settimeout(5)
        while time.time() < deadline:
            try:
                mid, body = self.recv_msg()
            except socket.timeout:
                if self.offset_us is not None or state_sent:
                    self.send_time()
                continue
            if mid == 0:
                m = json.loads(body)
                t = m.get("type")
                print("<-", t, json.dumps(m.get("payload"))[:300])
                if t == "server/activate":
                    if self.offset_us is None:
                        for _ in range(8):  # burst of time exchanges before claiming available
                            self.send_time()
                            mid2, b2 = self.recv_msg()
                            if mid2 == 0 and json.loads(b2)["type"] == "server/time":
                                self.on_server_time(json.loads(b2)["payload"])
                        print(f"clock: offset {self.offset_us} us, best rtt {self.best_rtt} us")
                    self.send_json({"type": "client/state", "payload": {
                        "available": True,
                        "player": {"volume": 100, "muted": False, "output_delay_ms": 0,
                                   "required_lead_time_ms": 200, "min_buffer_ms": 300,
                                   "supported_commands": ["volume", "mute"]}}})
                    state_sent = True
                elif t == "server/time":
                    self.on_server_time(m["payload"])
                elif t == "stream/start":
                    p = m["payload"]["player"]
                    self.fmt = p
                    if wav_path and p["codec"] == "pcm":
                        self.wav = wave.open(wav_path, "wb")
                        self.wav.setnchannels(p["channels"])
                        self.wav.setsampwidth(p["bit_depth"] // 8)
                        self.wav.setframerate(p["sample_rate"])
                elif t == "stream/end" and self.wav:
                    self.wav.close()
                    self.wav = None
            elif mid == 4:
                ts, ahead = struct.unpack(">qI", body[:12])
                audio = body[12:]
                self.chunks += 1
                self.pcm_bytes += len(audio)
                if self.chunks == 1:
                    lead_ms = (ts - (self.now_us() + (self.offset_us or 0))) / 1000
                    print(f"first audio chunk: {len(audio)} bytes, play-in {lead_ms:.0f} ms, send_ahead {ahead} us")
                if self.wav:
                    self.wav.writeframes(audio)
            else:
                print("<- binary id", mid, len(body), "bytes")
        if self.wav:
            self.wav.close()
        print(f"done: {self.chunks} audio chunks, {self.pcm_bytes} PCM bytes")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("port", type=int)
    ap.add_argument("--wav")
    ap.add_argument("--seconds", type=int, default=30)
    a = ap.parse_args()
    Client(a.host, a.port).run(a.seconds, a.wav)
