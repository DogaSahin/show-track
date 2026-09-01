package com.anarky.showtrack.feature.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DetailScreen(
    mediaId: String,
    modifier: Modifier = Modifier,
) {
    Text(text = "Detail: $mediaId", modifier = modifier)
}
