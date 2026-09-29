#!/usr/bin/env python3
"""Übernimmt die Android-Texte (values/, values-en/) als iOS-Localizable.strings.

Aufruf aus dem Repo-Wurzelverzeichnis:  python3 ios/tools/convert_strings.py
Zusätzliche, nur unter iOS benötigte Texte stehen in ios/tools/extra_strings_<lang>.json.
"""
import glob, json, os, re
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOURCES = ["app/src/main/res", "document/src/main/res"]
OUT = os.path.join(ROOT, "ios/BlockMail/Resources")


def android_to_ios(text: str) -> str:
    # In Anführungszeichen eingeschlossene Werte (Leerzeichen erhalten) auspacken
    if len(text) >= 2 and text.startswith('"') and text.endswith('"') and not text.endswith('\\"'):
        text = text[1:-1]
    # Android-Escapes auflösen
    text = text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
    text = text.replace("\\@", "@").replace("\\?", "?")
    # Formatplatzhalter: %s → %@, %d → %ld
    text = re.sub(r"%(\d+\$)?s", lambda m: "%" + (m.group(1) or "") + "@", text)
    text = re.sub(r"%(\d+\$)?d", lambda m: "%" + (m.group(1) or "") + "ld", text)
    return text


def inner_text(el) -> str:
    # Inline-Markup (<b>, <xliff:g>) als Text übernehmen
    s = (el.text or "")
    for child in el:
        s += inner_text(child) + (child.tail or "")
    return s


def load(folder: str) -> dict:
    out = {}
    for src in SOURCES:
        for f in sorted(glob.glob(os.path.join(ROOT, src, folder, "strings*.xml"))):
            for el in ET.parse(f).getroot():
                name = el.get("name")
                if not name:
                    continue
                if el.tag == "string":
                    out[name] = android_to_ios(inner_text(el).strip())
                elif el.tag == "plurals":
                    for item in el:
                        out[f"{name}#{item.get('quantity')}"] = android_to_ios(inner_text(item).strip())
                elif el.tag == "string-array":
                    for i, item in enumerate(el):
                        out[f"{name}#{i}"] = android_to_ios(inner_text(item).strip())
    return out


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def write(lang: str, data: dict):
    extra = os.path.join(ROOT, "ios/tools", f"extra_strings_{lang}.json")
    if os.path.exists(extra):
        with open(extra, encoding="utf-8") as fh:
            data.update(json.load(fh))
    d = os.path.join(OUT, f"{lang}.lproj")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "Localizable.strings"), "w", encoding="utf-8") as fh:
        fh.write("/* Automatisch erzeugt von ios/tools/convert_strings.py – nicht von Hand ändern. */\n")
        for k in sorted(data):
            fh.write(f'"{esc(k)}" = "{esc(data[k])}";\n')


de = load("values")
en = load("values-en")
# Fehlende englische Texte fallen auf Deutsch zurück
en_full = dict(de)
en_full.update(en)
write("de", de)
write("en", en_full)
print(f"de: {len(de)} Texte, en: {len(en)} übersetzt")
