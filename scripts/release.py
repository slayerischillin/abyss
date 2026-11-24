#!/usr/bin/env python3
"""Publish an Abyss update: python3 scripts/release.py 140

Keeps the three version numbers in step (version.json, the page's abyss-version tag,
and the copy under builds/) and gives every release its own file name. A new file name
means GitHub's 5-minute cache can never hand Abyss an old copy of the page."""
import json, re, sys, pathlib, glob, os
root = pathlib.Path(__file__).resolve().parent.parent
v = int(sys.argv[1])
page = root / "index.html"
s = page.read_text(encoding="utf8")
s, n = re.subn(r'name="abyss-version" content="[\d.]+"', f'name="abyss-version" content="{v}"', s, count=1)
assert n == 1, "abyss-version tag not found"
page.write_text(s, encoding="utf8")
(root / "builds").mkdir(exist_ok=True)
for old in glob.glob(str(root / "builds" / "index-*.html")):
    os.remove(old)  # only the newest build is needed
(root / "builds" / f"index-{v}.html").write_text(s, encoding="utf8")
info = json.loads((root / "version.json").read_text())
info["version"] = v
info["file"] = f"builds/index-{v}.html"
(root / "version.json").write_text(json.dumps(info, indent=1) + "\n")
print("release", v, "->", info["file"])
