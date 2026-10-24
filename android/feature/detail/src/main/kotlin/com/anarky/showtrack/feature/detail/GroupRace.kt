package com.anarky.showtrack.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anarky.showtrack.core.designsystem.component.UserAvatar
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.UserMediaStatus

private val TrackHeight = 118.dp
private val TrackLineTop = 58.dp
private val TrackLineHeight = 4.dp
private val TickTop = 52.dp
private val TickSize = 16.dp
private val PinAvatarSize = 26.dp
private val PinRing = 2.dp
private val PinUpTop = 4.dp
private val PinDownTop = 66.dp
private val StackOffset = 6.dp
private val EndLabelTop = 96.dp
private val MemberAvatarSize = 32.dp
private val PinLabelSize = 10.sp
private val PinSlotWidth = 72.dp

/**
 * One line from the first episode to the last, everyone placed on it. A drawing only: TalkBack
 * reads the member list under it instead, which says the same thing in words.
 */
@Composable
internal fun RaceTrack(
    members: List<MemberProgress>,
    meId: String?,
    total: Int?,
    list: EpisodeList?,
    modifier: Modifier = Modifier,
) {
    val shown = RaceRules.trackMembers(members, meId)
    val end = RaceRules.trackEnd(total, members)
    val mine = members.firstOrNull { it.member.id == meId }?.progress
    val ticks = RaceRules.seasonTicks(list)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .padding(horizontal = 16.dp)
                .clearAndSetSemantics { },
    ) {
        val usable = maxWidth - PinAvatarSize

        fun xOf(episodes: Int): Dp = PinAvatarSize / 2 + usable * (episodes.toFloat() / end).coerceIn(0f, 1f)

        TrackLine(usable = usable, mineX = mine?.takeIf { it > 0 }?.let { xOf(it) })
        val outline = MaterialTheme.colorScheme.outline
        ticks.filter { it in 1 until end }.forEach { tick ->
            Box(
                modifier =
                    Modifier
                        .offset(x = xOf(tick) - 1.dp, y = TickTop)
                        .size(width = 2.dp, height = TickSize)
                        .background(outline),
            )
        }
        RaceRules.pins(shown, meId).forEach { pin ->
            Pin(
                pin = pin,
                // A fixed-width slot centred on the spot, so a wide label never shifts the avatar.
                modifier =
                    Modifier
                        .offset(
                            x = xOf(pin.progress.progress) - PinSlotWidth / 2 + StackOffset * pin.stackIndex,
                            y = if (pin.above) PinUpTop else PinDownTop,
                        ).width(PinSlotWidth),
            )
        }
        EndLabel(text = stringResource(R.string.detail_group_number, 1), modifier = Modifier.offset(y = EndLabelTop))
        EndLabel(
            text = stringResource(R.string.detail_group_number, end),
            modifier = Modifier.align(Alignment.TopEnd).offset(y = EndLabelTop),
        )
    }
}

/** The line itself, filled in primary up to [mineX] (your position), when you are on it. */
@Composable
private fun TrackLine(
    usable: Dp,
    mineX: Dp?,
) {
    Box(
        modifier =
            Modifier
                .offset(x = PinAvatarSize / 2, y = TrackLineTop)
                .width(usable)
                .height(TrackLineHeight)
                .clip(RoundedCornerShape(TrackLineHeight / 2))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
    if (mineX != null) {
        Box(
            modifier =
                Modifier
                    .offset(x = PinAvatarSize / 2, y = TrackLineTop)
                    .width(mineX - PinAvatarSize / 2)
                    .height(TrackLineHeight)
                    .clip(RoundedCornerShape(TrackLineHeight / 2))
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Pin(
    pin: RacePin,
    modifier: Modifier = Modifier,
) {
    val ring = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(PinAvatarSize + PinRing * 2)
                    .then(if (pin.isMe) Modifier.border(PinRing, primary, CircleShape) else Modifier)
                    .padding(PinRing)
                    .border(PinRing, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            UserAvatar(
                userId = pin.progress.member.id,
                name = pin.progress.member.username,
                size =
                    PinAvatarSize - PinRing,
            )
        }
        Text(
            text =
                when {
                    pin.isMe -> stringResource(R.string.detail_group_you, pin.progress.progress)
                    pin.progress.status == UserMediaStatus.COMPLETED -> stringResource(R.string.detail_group_done)
                    else -> stringResource(R.string.detail_group_number, pin.progress.progress)
                },
            fontSize = PinLabelSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun EndLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text = text, fontSize = PinLabelSize, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** "doga (you)" / "Watching · S2 E8", with "2 eps ahead", "3 eps behind" or "Finished" on the right. */
@Composable
internal fun MemberRow(
    member: MemberProgress,
    isMe: Boolean,
    mine: Int?,
    list: EpisodeList?,
    modifier: Modifier = Modifier,
) {
    val name =
        if (isMe) {
            stringResource(
                R.string.detail_group_you_name,
                member.member.username,
            )
        } else {
            member.member.username
        }
    val (position, positionSpoken) = positionTexts(member, list)
    val detail = stringResource(R.string.detail_group_member_status, member.status.label(), position)
    val standing = if (isMe || mine == null) null else RaceRules.standing(member, mine)
    val standingText = standing?.let { standingLabel(it) }
    val status = member.status.label()
    val spoken =
        if (standingText != null) {
            stringResource(R.string.detail_group_member_a11y_standing, name, status, positionSpoken, standingText)
        } else {
            stringResource(R.string.detail_group_member_a11y, name, status, positionSpoken)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(userId = member.member.id, name = member.member.username, size = MemberAvatarSize)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (standing != null && standingText != null) {
            Text(text = standingText, style = MaterialTheme.typography.labelLarge, color = standingColor(standing))
        }
    }
}

/** Where a member is, shown ("S2 E8", "Ep 8") and spoken ("season 2, episode 8"). */
@Composable
private fun positionTexts(
    member: MemberProgress,
    list: EpisodeList?,
): Pair<String, String> {
    val at = RaceRules.episodeAt(member.progress, list)
    val season = at?.first
    val episode = at?.second ?: member.progress
    return if (season != null) {
        stringResource(R.string.detail_group_position_season, season, episode) to
            stringResource(R.string.detail_group_position_season_spoken, season, episode)
    } else {
        stringResource(R.string.detail_group_position_episode, episode) to
            stringResource(R.string.detail_group_position_episode_spoken, episode)
    }
}

@Composable
private fun standingLabel(standing: Standing): String =
    when (standing) {
        Standing.Finished -> stringResource(R.string.detail_group_finished)
        Standing.Level -> stringResource(R.string.detail_group_level)
        is Standing.Ahead -> pluralStringResource(R.plurals.detail_group_ahead, standing.episodes, standing.episodes)
        is Standing.Behind -> pluralStringResource(R.plurals.detail_group_behind, standing.episodes, standing.episodes)
    }

/** Ahead in teal, behind in steel, finished and level muted. */
@Composable
private fun standingColor(standing: Standing) =
    when (standing) {
        is Standing.Ahead -> UserMediaStatus.COMPLETED.markColor()
        is Standing.Behind -> MaterialTheme.colorScheme.secondary
        Standing.Finished, Standing.Level -> MaterialTheme.colorScheme.onSurfaceVariant
    }
