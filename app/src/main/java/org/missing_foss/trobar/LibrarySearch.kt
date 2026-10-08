// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import java.text.Normalizer

/** The library search's matching rule, the same as the web UI's
 * (foldForSearch in the server's page script): a keyboard types U+002D and
 * plain letters, while tags written by MusicBrainz carry U+2010 HYPHEN and
 * the accents. Both the query and the text are folded before comparing;
 * what's shown stays as stored. */
internal object LibrarySearch {
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212\\uFE63\\uFF0D]")
    private val MARKS = Regex("\\p{Mn}+")

    /** Lower case, every dash-like character to '-', accents dropped. */
    fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(DASHES.replace(text.lowercase(), "-"), Normalizer.Form.NFD), "")

    /** Whether [text] contains the already-folded [foldedQuery]. */
    fun matches(foldedQuery: String, text: String): Boolean = foldedQuery in fold(text)
}
