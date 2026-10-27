"""Local patches for the stock Music Assistant image, applied without a fork.

Python imports sitecustomize at interpreter start (the image ships none). This file registers an import hook: when a
patched Music Assistant module is first imported, the named functions in it are replaced. If an upgrade moves or
renames a target, the patch logs a warning and leaves the stock behaviour alone: it never stops Music Assistant
starting. Mounted from the music-assistant-patches ConfigMap (see README.md next to this file).

Patches:
- apple_music library delete. Stock Music Assistant skips it ("not yet supported by the Apple Music API"): the public
  API refuses library deletes, but Apple's web player endpoint (amp-api) accepts them with the web player's own
  developer token, which is public and read from music.apple.com, plus the account's existing music user token.
  Scoped to the requesting user's providers (a user with a provider filter only deletes from accounts it can see).
"""

import importlib.abc
import importlib.util
import logging
import sys

_LOG = logging.getLogger("music_assistant.local_patches")


# ------------------------------------------------------------------ apple_music: delete from the library

_WEB_TOKEN = {"token": None, "at": 0.0}
_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
       "Version/17.0 Safari/605.1.15")


async def _web_token(session):
    """The developer token music.apple.com itself uses, read from its JavaScript (cached for 12 hours)."""
    import re
    import time

    if _WEB_TOKEN["token"] and time.time() - _WEB_TOKEN["at"] < 12 * 3600:
        return _WEB_TOKEN["token"]
    headers = {"User-Agent": _UA}
    async with session.get("https://music.apple.com/gb/browse", headers=headers) as resp:
        html = await resp.text()
    for path in dict.fromkeys(re.findall(r"(/assets/index[~\-][^\"']+\.js)", html)):
        async with session.get("https://music.apple.com" + path, headers=headers) as resp:
            js = await resp.text(errors="ignore")
        match = re.search(r"eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}", js)
        if match:
            _WEB_TOKEN.update(token=match.group(0), at=time.time())
            return match.group(0)
    raise RuntimeError("no web player token found on music.apple.com")


def _patch_apple_music_library(module):
    from music_assistant_models.enums import MediaType

    cls = module.AppleMusicLibraryManager
    stock = cls.library_remove
    kinds = {MediaType.TRACK: "songs", MediaType.ALBUM: "albums"}   # catalog/{sf}/songs/X and me/library/songs/i.X

    async def library_remove(self, prov_item_id: str, media_type: MediaType) -> None:
        """Remove a song or album from the Apple Music library through Apple's web player endpoint."""
        if media_type not in kinds:
            return await stock(self, prov_item_id, media_type)
        # Music Assistant removes from EVERY account holding the item. Only delete from accounts the requesting user can
        # see: a child's account removing a song must not delete it from a parent's Apple Music too.
        try:
            from music_assistant.controllers.webserver.helpers.auth_middleware import get_current_user
            user = get_current_user()
        except Exception:
            user = None
        allowed = getattr(user, "provider_filter", None) or []
        if allowed and self.provider.instance_id not in allowed:
            self.logger.info("Library delete: %s is not one of the requesting user's accounts, left alone", self.provider.instance_id)
            return None
        kind = kinds[media_type]
        session = self.provider.mass.http_session
        headers = {
            "Authorization": "Bearer " + await _web_token(session),
            "media-user-token": self.provider._music_user_token,
            "Origin": "https://music.apple.com",
            "Referer": "https://music.apple.com/",
            "User-Agent": _UA,
        }
        base = "https://amp-api.music.apple.com/v1/"
        if str(prov_item_id).startswith(("i.", "l.")):
            library_ids = [prov_item_id]
        else:   # a catalogue id: find the library copy (or copies) of it
            storefront = getattr(self.provider, "_storefront", None) or "us"
            url = f"{base}catalog/{storefront}/{kind}/{prov_item_id}?relate=library"
            async with session.get(url, headers=headers) as resp:
                if resp.status != 200:
                    self.logger.warning("Library delete: looking up %s %s failed (HTTP %s)", media_type, prov_item_id, resp.status)
                    return
                data = await resp.json()
            rel = (data.get("data") or [{}])[0].get("relationships", {}).get("library", {})
            library_ids = [x["id"] for x in rel.get("data", [])]
        for library_id in library_ids:
            async with session.delete(f"{base}me/library/{kind}/{library_id}", headers=headers) as resp:
                if resp.status in (200, 202, 204, 404):
                    self.logger.info("Deleted %s %s from the Apple Music library", media_type, library_id)
                else:
                    self.logger.warning("Library delete of %s %s failed (HTTP %s)", media_type, library_id, resp.status)
        return None

    cls.library_remove = library_remove
    _LOG.info("local patch applied: apple_music library delete (amp-api)")


# ------------------------------------------------------------------ the import hook

PATCHES = {
    "music_assistant.providers.apple_music.library": _patch_apple_music_library,
}


class _PatchingLoader(importlib.abc.Loader):
    def __init__(self, real, patch):
        self.real, self.patch = real, patch

    def create_module(self, spec):
        return self.real.create_module(spec)

    def exec_module(self, module):
        self.real.exec_module(module)
        try:
            self.patch(module)
        except Exception:   # an upgrade changed the target: keep the stock code, say so loudly
            _LOG.warning("local patch for %s not applied (Music Assistant changed?)", module.__name__, exc_info=True)


class _PatchingFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, name, path, target=None):
        patch = PATCHES.get(name)
        if patch is None:
            return None
        sys.meta_path.remove(self)          # find the real module without recursing into ourselves
        try:
            spec = importlib.util.find_spec(name)
        finally:
            sys.meta_path.insert(0, self)
        if spec is None or spec.loader is None:
            return None
        spec.loader = _PatchingLoader(spec.loader, patch)
        return spec


sys.meta_path.insert(0, _PatchingFinder())
