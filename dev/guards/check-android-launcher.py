#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2026 missing-foss
#
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fail when an Android app loses themed icons or changes a launcher component.

    dev/guards/check-android-launcher.py

Run from the repository root with NO arguments: that is how both gates call
it (dev-guards rule 1). It finds the one application module itself and reads
dev/launcher-components.txt. --module and --components exist for a human
checking something by hand; a gate never passes them.

Two things a launcher depends on, and both break without anyone noticing:

  1. THEMED ICONS. Android 13 and later draw a themed icon from an adaptive
     icon's <monochrome> layer. An adaptive icon without one shows the plain
     icon in a themed grid. Every mipmap-anydpi*/*.xml adaptive icon under the
     module's src/main/res must have a <monochrome> element, and it must not
     reuse the <foreground> drawable: a full-colour foreground as the
     monochrome layer is tinted into a solid blob, not a themed icon.

  2. LAUNCHER COMPONENTS. Icon packs match an app on its exact component,
     applicationId/fully.qualified.Class. Renaming the launcher activity, an
     activity-alias or the applicationId silently drops every pack's icon for
     the app. Every activity or activity-alias in src/main/AndroidManifest.xml
     with action MAIN and category LAUNCHER counts, enabled or not, because a
     pack maps them all. The set must equal the checked-in list in
     dev/launcher-components.txt; updating the list in the same change is the
     acknowledgement.

The application module is the one directory, at most four levels down, with
both src/main/AndroidManifest.xml and a build.gradle(.kts) that applies
com.android.application. None, or more than one, is a failure.

A relative class name (.Foo, or Foo with no dot) resolves against the Gradle
`namespace`, the package being the `applicationId`. Only the main source set
is read: debug and profile variants and applicationIdSuffix are not what packs
see.

Anything that cannot be read is a failure, never a pass: no adaptive icons
found, no launcher component found, no applicationId, or one that differs
between flavours. A guard that cannot look must not report that it saw nothing.

A line `warn: monochrome` in the list reports icon problems without failing,
for a repository that adopts the guard before its icons are fixed. Delete the
line once they are. It lives in the list, not on a command line, so both gates
read the same setting.
"""
import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN = "android.intent.action.MAIN"
LAUNCHER = "android.intent.category.LAUNCHER"
GRADLE_FILES = ("build.gradle.kts", "build.gradle")
DEFAULT_LIST = pathlib.Path("dev/launcher-components.txt")
APP_PLUGIN = "com.android.application"
SKIP_DIRS = {".git", "build", "node_modules", ".gradle", ".dart_tool", "ios", "web"}
WARNINGS = {"monochrome"}
ASSIGN = r"""\b{key}\b\s*=?\s*["']([A-Za-z0-9_.]+)["']"""


class GuardError(Exception):
    """The guard could not read what it is meant to check."""


def gradle_value(text, key):
    found = sorted(set(re.findall(ASSIGN.format(key=key), text)))
    if len(found) > 1:
        raise GuardError(f"{key} has more than one value ({', '.join(found)}); "
                         "this guard reads a single-flavour app")
    return found[0] if found else None


def read_gradle(module):
    for name in GRADLE_FILES:
        path = module / name
        if path.is_file():
            text = path.read_text(encoding="utf-8")
            app_id = gradle_value(text, "applicationId")
            if not app_id:
                raise GuardError(f"{path}: no applicationId")
            return app_id, gradle_value(text, "namespace")
    raise GuardError(f"{module}: no {' or '.join(GRADLE_FILES)}")


def qualify(name, package):
    if name.startswith("."):
        return package + name
    if "." not in name:
        return f"{package}.{name}"
    return name


def launcher_components(module):
    manifest = module / "src/main/AndroidManifest.xml"
    if not manifest.is_file():
        raise GuardError(f"{manifest}: not found")
    app_id, namespace = read_gradle(module)
    root = ET.parse(manifest).getroot()
    package = namespace or root.get("package")
    if not package:
        raise GuardError(f"{manifest}: no namespace in Gradle and no package attribute")
    app = root.find("application")
    if app is None:
        raise GuardError(f"{manifest}: no <application>")
    out = set()
    for node in app:
        if node.tag not in ("activity", "activity-alias"):
            continue
        for filt in node.findall("intent-filter"):
            actions = {a.get(ANDROID + "name") for a in filt.findall("action")}
            cats = {c.get(ANDROID + "name") for c in filt.findall("category")}
            if MAIN in actions and LAUNCHER in cats:
                out.add(f"{app_id}/{qualify(node.get(ANDROID + 'name'), package)}")
    if not out:
        raise GuardError(f"{manifest}: no launcher component found")
    return out


def find_module(root):
    """The one application module under root, at most four levels down."""
    found = []
    base_depth = len(root.parts)
    for path in sorted(root.rglob("src/main/AndroidManifest.xml")):
        module = path.parents[2]
        if len(module.parts) - base_depth > 4 or SKIP_DIRS & set(module.relative_to(root).parts):
            continue
        for name in GRADLE_FILES:
            gradle = module / name
            if gradle.is_file() and APP_PLUGIN in gradle.read_text(encoding="utf-8"):
                found.append(module)
                break
    if len(found) != 1:
        names = ", ".join(str(m.relative_to(root)) for m in found) or "none"
        raise GuardError(f"expected exactly one application module, found {len(found)} ({names})")
    return found[0]


def read_list(path):
    """Components and warnings. A line `warn: <check>` turns that check to a warning."""
    if not path.is_file():
        return None, set()
    comps, warn = set(), set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("warn:"):
            name = line[len("warn:"):].strip()
            if name not in WARNINGS:
                raise GuardError(f"{path}: unknown warning {name!r}")
            warn.add(name)
        else:
            comps.add(line)
    return comps, warn


def monochrome_problem(root):
    mono = root.find("monochrome")
    if mono is None:
        return "has no <monochrome> layer"
    fore = root.find("foreground")
    drawable = mono.get(ANDROID + "drawable")
    if fore is not None and drawable and drawable == fore.get(ANDROID + "drawable"):
        return f"has a <monochrome> layer that reuses the foreground ({drawable})"
    return None


def icons_without_monochrome(module):
    res = module / "src/main/res"
    icons = sorted(p for d in res.glob("mipmap-anydpi*") if d.is_dir() for p in d.glob("*.xml"))
    adaptive = [(p, ET.parse(p).getroot()) for p in icons]
    adaptive = [(p, root) for p, root in adaptive if root.tag == "adaptive-icon"]
    if not adaptive:
        raise GuardError(f"{res}: no adaptive icon in mipmap-anydpi*; is --module right?")
    problems = [(p, monochrome_problem(root)) for p, root in adaptive]
    return [p for p, _ in adaptive], [(p, why) for p, why in problems if why]


def check(module, components, warn=()):
    """Return (exit status, report lines). module None means find it."""
    lines, status = [], 0
    try:
        if module is None:
            module = find_module(pathlib.Path("."))
            lines.append(f"GUARD android: application module {module}")
        expected, listed_warn = read_list(components)
        warn = set(warn) | listed_warn
        adaptive, missing = icons_without_monochrome(module)
        for path, why in missing:
            level = "WARN" if "monochrome" in warn else "FAIL"
            lines.append(f"GUARD android: {level} {path} {why}")
            if level == "FAIL":
                status = 1
        if not missing:
            lines.append(f"GUARD android: OK {len(adaptive)} adaptive icon(s), each with its own <monochrome>")

        actual = launcher_components(module)
        if expected is None:
            status = 1
            lines.append(f"GUARD android: FAIL {components}: not found; create it with these lines:")
            lines.extend(sorted(actual))
            return status, lines
        for comp in sorted(actual - expected):
            lines.append(f"GUARD android: FAIL new launcher component {comp}")
        for comp in sorted(expected - actual):
            lines.append(f"GUARD android: FAIL launcher component gone {comp}")
        if actual != expected:
            status = 1
            lines.append(f"GUARD android: icon packs match these exactly; if the change is "
                         f"intended, update {components} in the same change")
        else:
            lines.append(f"GUARD android: OK {len(actual)} launcher component(s) match {components}")
    except (GuardError, ET.ParseError, OSError, UnicodeDecodeError) as e:
        lines.append(f"GUARD android: FAIL {e}")
        status = 1
    return status, lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--module", type=pathlib.Path, help="by hand only; a gate never passes it")
    ap.add_argument("--components", type=pathlib.Path, default=DEFAULT_LIST,
                    help="by hand only; a gate never passes it")
    ap.add_argument("--warn", action="append", default=[], choices=sorted(WARNINGS),
                    help="by hand only; a gate uses a `warn:` line in the list")
    args = ap.parse_args(argv)
    status, lines = check(args.module, args.components, args.warn)
    print("\n".join(lines))
    return status


if __name__ == "__main__":
    sys.exit(main())
