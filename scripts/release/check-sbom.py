#!/usr/bin/env python3
"""Offline CycloneDX SBOM policy checks for release artifacts.

Usage:
  scripts/release/check-sbom.py [bom.json] [--strict]

Exit codes: 0 pass (warnings allowed), 1 policy violation, 2 malformed input.
No network access; standard library only.
"""
import json
import sys
from collections import Counter
from pathlib import Path

DENY_LICENSES = {
    "AGPL-3.0",
    "AGPL-3.0-only",
    "AGPL-3.0-or-later",
    "SSPL-1.0",
}
WARN_LICENSES = {
    "GPL-2.0",
    "GPL-2.0-only",
    "GPL-3.0",
    "GPL-3.0-only",
    "LGPL-2.1",
    "LGPL-2.1-only",
    "LGPL-3.0",
    "LGPL-3.0-only",
    "EPL-1.0",
    "EPL-2.0",
    "MPL-2.0",
}
REQUIRED_FIELDS = ("type", "name", "version")


def license_ids(component):
    ids = set()
    for entry in component.get("licenses") or []:
        if "expression" in entry:
            ids.add(entry["expression"])
        license_obj = entry.get("license") or {}
        value = license_obj.get("id") or license_obj.get("name")
        if value:
            ids.add(value)
    evidence = ((component.get("evidence") or {}).get("licenses") or [])
    for entry in evidence:
        license_obj = entry.get("license") or {}
        value = license_obj.get("id") or license_obj.get("name")
        if value:
            ids.add(value)
    return ids


def main(argv):
    strict = "--strict" in argv
    args = [arg for arg in argv[1:] if not arg.startswith("--")]
    path = Path(args[0] if args else "consilens-dist/target/bom.json")
    if path.suffix.lower() == ".xml":
        print(f"SBOM invalid: {path} is XML; this checker reads CycloneDX JSON (use bom.json)",
              file=sys.stderr)
        return 2
    try:
        bom = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"SBOM invalid: {error}", file=sys.stderr)
        return 2

    errors = []
    warnings = []
    if bom.get("bomFormat") != "CycloneDX":
        errors.append("bomFormat is not CycloneDX")
    spec = str(bom.get("specVersion", ""))
    if spec not in {"1.4", "1.5", "1.6"}:
        errors.append(f"unsupported specVersion: {spec or '<missing>'}")
    components = bom.get("components") or []
    if not components:
        errors.append("components is empty")

    purls = Counter()
    license_counts = Counter()
    for component in components:
        label = f"{component.get('group', '')}:{component.get('name', '?')}"
        for field in REQUIRED_FIELDS:
            if not component.get(field):
                errors.append(f"{label}: missing {field}")
        version = component.get("version")
        if version and version in {"latest", "unknown"}:
            errors.append(f"{label}: unsafe version '{version}'")
        purl = component.get("purl")
        if not purl:
            warnings.append(f"{label}: missing purl")
        else:
            purls[purl] += 1
        licenses = license_ids(component)
        if not licenses:
            warnings.append(f"{label}: no license metadata")
        for license_id in licenses:
            license_counts[license_id] += 1
            normalized = license_id.upper()
            if any(denied.upper() == normalized for denied in DENY_LICENSES):
                errors.append(f"{label}: denied license {license_id}")
            elif any(warned.upper() == normalized for warned in WARN_LICENSES):
                warnings.append(f"{label}: review license {license_id}")

    duplicate_purls = [purl for purl, count in purls.items() if count > 1]
    for purl in duplicate_purls:
        warnings.append(f"duplicate purl: {purl}")

    print(f"SBOM: {path}")
    print(f"  specVersion : {spec or '<missing>'}")
    print(f"  components  : {len(components)}")
    print(f"  licenses    : {dict(license_counts.most_common())}")
    if warnings:
        print(f"  warnings    : {len(warnings)}")
        for warning in warnings[:20]:
            print(f"    - {warning}")
        if len(warnings) > 20:
            print(f"    ... {len(warnings) - 20} more")
    if errors:
        print(f"  errors      : {len(errors)}", file=sys.stderr)
        for error in errors:
            print(f"    - {error}", file=sys.stderr)
        return 1
    if strict and warnings:
        print("  strict mode : warnings treated as errors", file=sys.stderr)
        return 1
    print("  result      : PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
