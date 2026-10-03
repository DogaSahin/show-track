package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anarky.showtrack.core.designsystem.theme.AvatarPalette

private const val INITIAL_SCALE = 0.42f

/**
 * A stable colour for [id]: the same id always lands on the same [AvatarPalette] entry, on every
 * screen and every device. `String.hashCode` is specified by the JVM, so it cannot drift between
 * runs the way an identity hash would.
 */
fun avatarColorFor(id: String): Color = AvatarPalette[Math.floorMod(id.hashCode(), AvatarPalette.size)]

/**
 * A member's avatar: their first letter on their own colour. Decorative to accessibility — every
 * place that shows one also shows the name, so announcing the letter would only repeat it.
 */
@Composable
fun UserAvatar(
    userId: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    AvatarCircle(initial = name, background = SolidColor(avatarColorFor(userId)), size = size, modifier = modifier)
}

/**
 * A group's avatar: its first letter on a diagonal blend of two palette colours, so a group never
 * looks like one of its members.
 */
@Composable
fun GroupAvatar(
    groupId: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val first = Math.floorMod(groupId.hashCode(), AvatarPalette.size)
    val second = (first + 1) % AvatarPalette.size
    AvatarCircle(
        initial = name,
        background = Brush.linearGradient(listOf(AvatarPalette[first], AvatarPalette[second])),
        size = size,
        modifier = modifier,
    )
}

@Composable
private fun AvatarCircle(
    initial: String,
    background: Brush,
    size: Dp,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.take(1).uppercase(),
            color = Color.White,
            style = TextStyle(fontSize = (size.value * INITIAL_SCALE).sp, fontWeight = FontWeight.SemiBold),
        )
    }
}
