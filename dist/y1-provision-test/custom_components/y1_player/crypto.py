"""The device link: X25519 keys, a Noise-style HKDF and ChaCha20-Poly1305, matching the player's Java (me.cns.sendspin.Crypto).

Each side keeps a static X25519 key pair; the shared secret gives one key per direction. A message is
base64(nonce[12]) + base64(ciphertext||tag) with AAD "y1/1"; the plaintext carries a counter that must only go up, so a
captured request cannot be replayed. Through a Nabu Casa cloudhook the relay sees only ciphertext.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

AAD = b"y1/1"
LABEL = b"y1-player v1"


def new_keypair() -> tuple[str, str]:
    """A new X25519 key pair as base64 (private, public)."""
    k = X25519PrivateKey.generate()
    priv = k.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    pub = k.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return base64.b64encode(priv).decode(), base64.b64encode(pub).decode()


def hkdf(ck: bytes, ikm: bytes, n: int) -> list[bytes]:
    """Noise's HKDF (as Crypto.hkdf in the Java client)."""
    tmp = hmac.new(ck, ikm, hashlib.sha256).digest()
    out, prev = [], b""
    for i in range(n):
        prev = hmac.new(tmp, prev + bytes([i + 1]), hashlib.sha256).digest()
        out.append(prev)
    return out


def session_keys(server_priv_b64: str, device_pub_b64: str) -> tuple[bytes, bytes]:
    """(device-to-server key, server-to-device key)."""
    priv = X25519PrivateKey.from_private_bytes(base64.b64decode(server_priv_b64))
    shared = priv.exchange(X25519PublicKey.from_public_bytes(base64.b64decode(device_pub_b64)))
    k = hkdf(LABEL, shared, 2)
    return k[0], k[1]


def seal(key: bytes, obj: dict) -> dict:
    nonce = os.urandom(12)
    ct = ChaCha20Poly1305(key).encrypt(nonce, json.dumps(obj, separators=(",", ":")).encode(), AAD)
    return {"n": base64.b64encode(nonce).decode(), "c": base64.b64encode(ct).decode()}


def open_(key: bytes, msg: dict) -> dict:
    """The plaintext object; raises on a wrong key or a tampered message."""
    pt = ChaCha20Poly1305(key).decrypt(base64.b64decode(msg["n"]), base64.b64decode(msg["c"]), AAD)
    return json.loads(pt)
