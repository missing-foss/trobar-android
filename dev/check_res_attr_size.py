#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Every attribute in the app's resource XML fits aapt2's 32,767 bytes.

A longer attribute (a vector's pathData, say) compiles to STRING_TOO_LARGE
with only a warning, and the drawable then fails to inflate at runtime.
Run by dev/verify.sh and by the forge's CI, from the repository root."""
import pathlib
import re
import sys

LIMIT = 32767


def main() -> int:
    files = sorted(pathlib.Path("app/src/main/res").rglob("*.xml"))
    bad = [(str(f), len(v.encode()))
           for f in files
           for v in re.findall(r'="([^"]*)"', f.read_text(encoding="utf-8"))
           if len(v.encode()) > LIMIT]
    for f, n in bad:
        print(f"FAIL: {f}: an attribute of {n} bytes")
    if not files:
        print("FAIL: no resource XML found")
    if bad or not files:
        return 1
    print(f"ok ({len(files)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
