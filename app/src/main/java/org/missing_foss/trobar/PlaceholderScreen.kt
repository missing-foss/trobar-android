// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** What a destination shows until its real screen lands. Carries the top
 * bar the real screen will have (a back arrow when pushed, the basket
 * action on a tab) so the navigation can be exercised and tested now and
 * the screens dropped in later without touching the shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    onOpenBasket: (() -> Unit)? = null,
    basketCount: Int? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                        }
                    }
                },
                actions = {
                    if (onOpenBasket != null) {
                        IconButton(onClick = onOpenBasket) {
                            BadgedBox(badge = { if (basketCount != null && basketCount > 0) Badge { Text(basketCount.toString()) } }) {
                                Icon(Icons.Filled.ShoppingBasket, contentDescription = stringResource(R.string.basket_title))
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.placeholder_empty), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
