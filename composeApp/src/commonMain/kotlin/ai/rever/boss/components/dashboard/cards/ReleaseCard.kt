package ai.rever.boss.components.dashboard.cards

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.updater.VersionInfo
import ai.rever.boss.updater.parseReleaseNotes
import ai.rever.boss.updater.summarizeReleaseNotes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Card for one release in the Dashboard's "What's New" feed (BossConsole#149).
 *
 * Same idiom as [ProjectCard]/[FileCard] - hover scale, click to open - but wider, since a
 * release needs room for a one-line summary of what it contains, not just a name.
 */
@Composable
fun ReleaseCard(
    release: VersionInfo,
    isNew: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
    )

    val backgroundColor = if (isHovered) BossTheme.colors.signalWash else BossTheme.colors.raised
    val cardShape = RoundedCornerShape(12.dp)

    // Best-effort, matching UpdateAvailableDialog's own fallback: a release whose notes don't
    // parse into anything summarizable just shows no summary line rather than an error.
    val summary =
        remember(release.releaseNotes) {
            runCatching { summarizeReleaseNotes(parseReleaseNotes(release.releaseNotes)) }.getOrNull()
        }

    Box(
        modifier =
            modifier
                .width(220.dp)
                .scale(scale)
                .clip(cardShape)
                .background(color = backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "v${release.version}",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isNew) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BossTheme.colors.signal)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "NEW",
                            color = BossTheme.colors.onSignal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Text(
                text = formatReleaseDate(release.releaseDate),
                color = BossTheme.colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
            )

            if (summary != null) {
                Text(
                    text = summary,
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** GitHub-style ISO timestamp ("2024-01-15T10:30:00Z") to just its date part. */
private fun formatReleaseDate(dateString: String): String = dateString.substringBefore("T")
