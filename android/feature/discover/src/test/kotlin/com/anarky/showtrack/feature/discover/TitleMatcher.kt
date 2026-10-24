package com.anarky.showtrack.feature.discover

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider

/**
 * Finds a title wherever it is on screen. A shelf poster shows it as text; the top pick card reads
 * as one spoken sentence (its content description, starting "Top pick for you") with no separate
 * text node, so a plain `onNodeWithText(title)` cannot see the top pick at all. Only that sentence
 * counts, not any description that merely mentions the title ("Add Frieren to your library").
 */
internal fun hasTitle(title: String): SemanticsMatcher {
    val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.discover_top_pick_label)
    val topPick =
        SemanticsMatcher("top pick sentence naming '$title'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                it.startsWith(label) && title in it
            }
        }
    return hasText(title) or topPick
}
