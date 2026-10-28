#!/usr/bin/env python3
"""Keep SOLAR_SHA256 in provision/y1.py in step with SOLAR_TAG: GitHub publishes each release asset's sha256, so when a
Renovate pull request moves the tag, this fills in the matching digest (and fails if the release has no rom.zip)."""
import json, re, subprocess, sys

P = "provision/y1.py"
s = open(P).read()
tag = re.search(r'SOLAR_TAG = "([^"]+)"', s).group(1)
out = subprocess.run(["gh", "api", "repos/thesolarproject/solar/releases/tags/" + tag], capture_output=True, text=True, check=True).stdout
asset = next((a for a in json.loads(out)["assets"] if a["name"] == "rom.zip"), None)
if not asset or not str(asset.get("digest", "")).startswith("sha256:"):
    sys.exit("release %s has no rom.zip with a published sha256" % tag)
digest = asset["digest"].split(":", 1)[1]
new = re.sub(r'(SOLAR_SHA256 = ")[0-9a-f]{64}(")', r"\g<1>%s\g<2>" % digest, s)
if new != s:
    open(P, "w").write(new)
    print("SOLAR_SHA256 set for %s: %s" % (tag, digest))
else:
    print("SOLAR_SHA256 already matches %s" % tag)
