#!/usr/bin/env python3
"""Prüft, ob alle im Swift-Code benutzten L("…")-Schlüssel existieren."""
import glob, os, re, sys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
keys = set(re.findall(r'^"((?:[^"\\]|\\.)*)" =', open(os.path.join(ROOT, "BlockMail/Resources/de.lproj/Localizable.strings"), encoding="utf-8").read(), re.M))
missing = {}
for f in glob.glob(os.path.join(ROOT, "**/*.swift"), recursive=True):
    for k in re.findall(r'\bL\("([A-Za-z0-9_#.]+)"', open(f, encoding="utf-8").read()):
        if k not in keys:
            missing.setdefault(k, set()).add(os.path.relpath(f, ROOT))
for k, fs in sorted(missing.items()):
    print(f"FEHLT: {k}  ({', '.join(sorted(fs))})")
sys.exit(1 if missing else 0)
