#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 missing-foss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates the app's brand colours from brand/trobar-themes.json (a copy of
trobar-server's brand/tokens/trobar-themes.json, the single source):

- app/src/main/res/values/colors_trobar.xml: each theme's six brand roles,
  which the kit's theme overlays and themable drawables read;
- app/src/main/java/org/missing_foss/trobar/BrandTokens.kt: the same roles
  for Compose, and each theme's derived light/dark ColorScheme.

    python3 dev/gen_brand_tokens.py          # rewrite both
    python3 dev/gen_brand_tokens.py --check  # dev/verify.sh: stale or under WCAG AA = exit 1

The derivation is trobar-server's (dev/gen_brand_css.py there, the table
agreed in trobar-server#141), copied verbatim between the markers below so
web and Android cannot drift. Android adds the Material 3 roles the web has
no use for: secondaryContainer (navigation indicator, selected chips) and the
surfaceContainer levels (cards, sheets, bars); see with_secondary_container.
"""
import colorsys
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOKENS = ROOT / "brand/trobar-themes.json"
COLORS_XML = ROOT / "app/src/main/res/values/colors_trobar.xml"
TOKENS_KT = ROOT / "app/src/main/java/org/missing_foss/trobar/BrandTokens.kt"

WHITE, BLACK = "#FFFFFF", "#000000"
ERROR_LIGHT = "#B3261E"
ERROR_DARK_HUE = "#E2586E"
CONTRASTE_YELLOW = "#FFD400"
CONTRASTE_ERROR = "#FF6B6B"
DARK_ONLY = ("nuech", "contraste")

# --- BEGIN shared with trobar-server dev/gen_brand_css.py ---
# --- colour arithmetic -------------------------------------------------------

def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def hexc(c):
    return "#" + "".join(f"{round(max(0.0, min(1.0, v)) * 255):02X}" for v in c)


def mix(a, b, t):
    """a moved a fraction t of the way to b."""
    return hexc(tuple(x + (y - x) * t for x, y in zip(rgb(a), rgb(b))))


def at_lightness(h, light):
    r, g, b = rgb(h)
    hue, _, sat = colorsys.rgb_to_hls(r, g, b)
    return hexc(colorsys.hls_to_rgb(hue, light, sat))


def alpha(h, a):
    r, g, b = (round(v * 255) for v in rgb(h))
    return f"rgba({r},{g},{b},{a})"


def over(fg, a, bg):
    """fg at opacity a composited over bg, as the browser paints it."""
    return mix(bg, fg, a)


def luminance(h):
    def channel(v):
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(v) for v in rgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


# --- the derivation (the table in trobar-server#141) --------------------------

def light_roles(t):
    return {
        "background": t["ground"], "surface": WHITE,
        "surfaceVariant": mix(t["ground"], t["text"], 0.06),
        "onSurface": t["text"], "onSurfaceVariant": mix(t["text"], t["ground"], 0.30),
        "primary": t["disc"], "onPrimary": t["star"],
        "primaryContainer": mix(t["disc"], WHITE, 0.85), "onPrimaryContainer": mix(t["disc"], BLACK, 0.6),
        "accent": t["accent"], "tertiary": t["dot"],
        "outline": mix(t["text"], t["ground"], 0.50), "error": ERROR_LIGHT, "onError": WHITE,
    }


def dark_roles(t, key):
    night = key == "nuech"
    base = t["watch"]
    ink = t["text"] if night else t["ground"]
    return {
        "background": base, "surface": mix(base, ink, 0.08), "surfaceVariant": mix(base, ink, 0.14),
        "onSurface": ink, "onSurfaceVariant": mix(ink, base, 0.30),
        "primary": t["star"] if night else at_lightness(t["disc"], 0.72),
        "onPrimary": t["ground"] if night else mix(t["disc"], BLACK, 0.7),
        "primaryContainer": t["disc"] if night else mix(t["disc"], BLACK, 0.35),
        "onPrimaryContainer": mix(t["disc"], WHITE, 0.82),
        "accent": t["accent"] if night else at_lightness(t["accent"], 0.70), "tertiary": t["dot"],
        "outline": mix(ink, base, 0.50), "error": at_lightness(ERROR_DARK_HUE, 0.72), "onError": BLACK,
    }


def contraste_roles():
    return {
        "background": BLACK, "surface": BLACK, "surfaceVariant": BLACK,
        "onSurface": WHITE, "onSurfaceVariant": WHITE,
        "primary": CONTRASTE_YELLOW, "onPrimary": BLACK,
        "primaryContainer": BLACK, "onPrimaryContainer": CONTRASTE_YELLOW,
        "accent": CONTRASTE_YELLOW, "tertiary": CONTRASTE_YELLOW,
        "outline": WHITE, "error": CONTRASTE_ERROR, "onError": BLACK,
    }


def variants(themes):
    """(theme key, light|dark, roles, theme tokens) for every variant shipped."""
    out = []
    for key, t in themes.items():
        if key == "contraste":
            out.append((key, "dark", contraste_roles(), t))
            continue
        if key not in DARK_ONLY:
            out.append((key, "light", light_roles(t), t))
        out.append((key, "dark", dark_roles(t, key), t))
    return out


# --- END shared with trobar-server dev/gen_brand_css.py ---

ROLES = ("ground", "disc", "star", "dot", "text", "accent", "watch")


def with_secondary_container(mode, r):
    """The Android-only additions: Material 3 draws the navigation indicator
    and selected chips in secondaryContainer, so it follows the accent the
    way primaryContainer follows primary; and its cards and bars in the
    surfaceContainer levels, which follow the theme's surfaces."""
    r = dict(r)
    if r["primaryContainer"] == BLACK:  # Contraste
        r["secondaryContainer"], r["onSecondaryContainer"] = BLACK, CONTRASTE_YELLOW
    elif mode == "light":
        r["secondaryContainer"] = mix(r["accent"], WHITE, 0.85)
        r["onSecondaryContainer"] = mix(r["accent"], BLACK, 0.6)
    else:
        r["secondaryContainer"] = mix(r["accent"], BLACK, 0.55)
        r["onSecondaryContainer"] = mix(r["accent"], WHITE, 0.82)
    # Cards, sheets and bars draw on the surfaceContainer levels; left at
    # Material's defaults they were a neutral grey-lavender in every theme.
    # Five even steps from the surface to surfaceVariant, the lowest the
    # page itself in dark mode.
    lowest = r["surface"] if mode == "light" else r["background"]
    for name, t in (("surfaceContainerLowest", None), ("surfaceContainerLow", 0.25), ("surfaceContainer", 0.5),
                    ("surfaceContainerHigh", 0.75), ("surfaceContainerHighest", 1.0)):
        r[name] = lowest if t is None else mix(r["surface"], r["surfaceVariant"], t)
    # Dividers and outlined-button borders: the theme's outline, softened
    # toward the surface (decorative, so no contrast minimum).
    r["outlineVariant"] = r["outline"] if r["outline"] == WHITE else mix(r["outline"], r["surface"], 0.45)
    return r


PAIRS = [
    ("onSurface", "surface", 4.5), ("onSurface", "background", 4.5),
    ("onSurfaceVariant", "surface", 4.5), ("onSurfaceVariant", "background", 4.5),
    ("onPrimary", "primary", 4.5), ("onPrimaryContainer", "primaryContainer", 4.5),
    ("onSecondaryContainer", "secondaryContainer", 4.5),
    ("primary", "surface", 4.5), ("accent", "surface", 4.5),
    ("outline", "surface", 3.0), ("error", "surface", 4.5), ("onError", "error", 4.5),
    ("onSurface", "surfaceContainerHighest", 4.5), ("onSurfaceVariant", "surfaceContainerHighest", 4.5),
    ("onSurfaceVariant", "surfaceContainer", 4.5),
]

SCHEME_ROLES = [
    ("primary", "primary"), ("onPrimary", "onPrimary"),
    ("primaryContainer", "primaryContainer"), ("onPrimaryContainer", "onPrimaryContainer"),
    ("secondary", "accent"), ("secondaryContainer", "secondaryContainer"),
    ("onSecondaryContainer", "onSecondaryContainer"), ("tertiary", "tertiary"),
    ("background", "background"), ("onBackground", "onSurface"),
    ("surface", "surface"), ("onSurface", "onSurface"),
    ("surfaceVariant", "surfaceVariant"), ("onSurfaceVariant", "onSurfaceVariant"),
    ("outline", "outline"), ("outlineVariant", "outlineVariant"), ("error", "error"), ("onError", "onError"),
    ("surfaceContainerLowest", "surfaceContainerLowest"), ("surfaceContainerLow", "surfaceContainerLow"),
    ("surfaceContainer", "surfaceContainer"), ("surfaceContainerHigh", "surfaceContainerHigh"),
    ("surfaceContainerHighest", "surfaceContainerHighest"),
]

XML_HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
SPDX-FileCopyrightText: 2026 missing-foss

SPDX-License-Identifier: GPL-3.0-or-later
-->
<!-- GENERATED by dev/gen_brand_tokens.py from brand/trobar-themes.json. Do not edit. -->
<resources>
"""

KT_HEADER = """// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED by dev/gen_brand_tokens.py from brand/trobar-themes.json. Do not edit:
// change the tokens or the derivation there and rerun it.
// A generated colour table: long literal lines, colour values as numbers, one
// constructor taking every role, and the BrandTheme enum living in a file named
// for what it holds. detekt's style rules are for hand-written code.
@file:Suppress("LongParameterList", "LongMethod", "MatchingDeclarationName", "MaxLineLength", "MagicNumber")

package org.missing_foss.trobar

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
"""


def argb(h):
    return "Color(0xFF" + h.lstrip("#").upper() + ")"


def scheme_call(fn, r):
    args = ",\n".join(f"        {name} = {argb(r[role])}" for name, role in SCHEME_ROLES)
    return f"{fn}(\n{args},\n    )"


def render():
    data = json.loads(TOKENS.read_text())
    themes = data["themes"]
    xml = [XML_HEADER]
    for key, t in themes.items():
        for role in ROLES[:-1]:
            xml.append(f'    <color name="trobar_{key}_{role}">{t[role]}</color>\n')
    xml.append("</resources>\n")

    kt = [KT_HEADER, "", "/** The five brand themes (brand/BRAND.md). [overlay] is the kit's theme overlay, read by the",
          " * themable drawables; [darkOnly] themes ignore the light/dark setting. */",
          "enum class BrandTheme(", "    val key: String,", "    val displayName: String,",
          "    val ground: Color, val disc: Color, val star: Color, val dot: Color, val text: Color, val accent: Color,",
          "    val overlay: Int,", "    val darkOnly: Boolean,", ") {"]
    for key, t in themes.items():
        cap = key[0].upper() + key[1:]
        cols = ", ".join(argb(t[r]) for r in ROLES[:-1])
        kt.append(f'    {key.upper()}("{key}", "{t["name"]}", {cols}, R.style.ThemeOverlay_Trobar_{cap}, {str(key in DARK_ONLY).lower()}),')
    kt[-1] = kt[-1].rstrip(",") + ";"
    kt += ["", "    companion object {",
           f'        val DEFAULT = {data["default"].upper()}',
           "        fun of(key: String?): BrandTheme = entries.firstOrNull { it.key == key } ?: DEFAULT",
           "    }", "}", ""]
    schemes = {}
    for key, mode, r, t in variants(themes):
        schemes[(key, mode)] = with_secondary_container(mode, r)
    kt.append("/** Each theme's derived ColorScheme, light or dark; a dark-only theme returns its dark scheme either way. */")
    kt.append("internal fun brandColorScheme(theme: BrandTheme, dark: Boolean): ColorScheme = when (theme) {")
    for key in themes:
        light = schemes.get((key, "light")); dark = schemes[(key, "dark")]
        if light is None:
            kt.append(f"    BrandTheme.{key.upper()} -> {scheme_call('darkColorScheme', dark)}")
        else:
            kt.append(f"    BrandTheme.{key.upper()} -> if (dark) {scheme_call('darkColorScheme', dark)} else {scheme_call('lightColorScheme', light)}")
    kt.append("}")
    return "".join(xml), "\n".join(kt) + "\n", schemes


def main():
    xml, kt, schemes = render()
    failures = []
    for (key, mode), r in schemes.items():
        for fg, bg, minimum in PAIRS:
            got = ratio(r[fg], r[bg])
            if got < minimum:
                failures.append(f"{key} {mode}: {fg} on {bg} is {got:.2f}:1, needs {minimum}:1")
    if "--check" in sys.argv:
        stale = [p.relative_to(ROOT) for p, want in ((COLORS_XML, xml), (TOKENS_KT, kt)) if not p.exists() or p.read_text() != want]
        for p in stale:
            print(f"{p} is stale: run python3 dev/gen_brand_tokens.py")
        for f in failures:
            print("FAIL", f)
        if stale or failures:
            sys.exit(1)
        print(f"ok ({len(PAIRS) * len(schemes)} pairs across {len(schemes)} theme variants, all at WCAG AA)")
        return
    for f in failures:
        print("FAIL", f)
    if failures:
        sys.exit(1)
    COLORS_XML.write_text(xml); TOKENS_KT.write_text(kt)
    print("wrote", COLORS_XML.relative_to(ROOT), "and", TOKENS_KT.relative_to(ROOT))


if __name__ == "__main__":
    main()
