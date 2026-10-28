#!/usr/bin/env python3
"""Check Chinese resource coverage and Android format placeholders without external dependencies."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

resources = Path(__file__).resolve().parents[1] / "Android/src/app/src/main/res"
source = {
    (item.tag, item.attrib["name"]): item
    for path in (resources / "values").glob("*.xml")
    for item in ET.parse(path).getroot()
    if item.tag in ("string", "plurals")
}
translated = {
    (item.tag, item.attrib["name"]): item
    for item in ET.parse(resources / "values-zh-rCN/strings.xml").getroot()
}
placeholder = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

def formats(item):
    return sorted(placeholder.findall("".join(item.itertext())))

for key, item in source.items():
    if item.get("translatable") == "false":
        continue
    assert key in translated, f"Missing Chinese resource: {key}"
    target = translated[key]
    if item.tag == "string":
        assert formats(item) == formats(target), f"Format mismatch: {key}"
    else:
        original = item.find("item[@quantity='other']")
        chinese = target.find("item[@quantity='other']")
        assert original is not None and chinese is not None, key
        assert formats(original) == formats(chinese), f"Plural format mismatch: {key}"
assert translated.keys() <= source.keys(), "Unknown Chinese resource names"
print(f"Validated {len(translated)} Chinese resources, including coverage and format placeholders.")
