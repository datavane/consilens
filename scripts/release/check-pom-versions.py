#!/usr/bin/env python3
"""Verify every module inherits the root project version.

A module whose <parent><version> differs from the root version can still
build locally when an older parent POM happens to be installed, but fails
on a clean machine with "parent.relativePath points at wrong local POM".

Usage:
  scripts/release/check-pom-versions.py [project-root]

Exit codes: 0 consistent, 1 mismatch, 2 malformed input.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
IGNORED_DIRS = {".git", "target", "node_modules"}


def text(node, path):
    return node.findtext(path, default="", namespaces=NS).strip()


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".").resolve()
    root_pom = root / "pom.xml"
    try:
        root_model = ET.parse(root_pom).getroot()
    except (OSError, ET.ParseError) as error:
        print(f"cannot read {root_pom}: {error}", file=sys.stderr)
        return 2

    version = text(root_model, "m:version")
    if not version:
        print(f"{root_pom}: missing project version", file=sys.stderr)
        return 2

    problems = []
    modules = 0
    for pom in sorted(root.rglob("pom.xml")):
        relative = pom.relative_to(root)
        if any(part in IGNORED_DIRS for part in relative.parts):
            continue
        if pom == root_pom:
            continue
        modules += 1
        try:
            model = ET.parse(pom).getroot()
        except ET.ParseError as error:
            problems.append(f"{relative}: malformed POM ({error})")
            continue
        parent_version = text(model, "m:parent/m:version")
        if parent_version != version:
            problems.append(
                f"{relative}: parent version {parent_version or '<missing>'} != root {version}"
            )
        own_version = text(model, "m:version")
        if own_version and own_version != version:
            problems.append(
                f"{relative}: module version {own_version} != root {version}"
            )

    print(f"POM versions: root={version}, modules={modules}")
    if problems:
        sys.stdout.flush()
        print(f"  mismatches: {len(problems)}", file=sys.stderr)
        for problem in problems:
            print(f"    - {problem}", file=sys.stderr)
        return 1
    print("  result      : PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
