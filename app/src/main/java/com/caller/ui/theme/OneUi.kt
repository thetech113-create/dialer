package com.caller.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Samsung One UI look (Galaxy S10–S23 Phone app): grey page, white rounded cards, big centered
 * title that collapses on scroll, text-only bottom tabs, green round call button.
 */
@Immutable
data class OneUiPalette(
    val background: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val chip: Color
)

object OneUi {
    val Blue = Color(0xFF3E91FF)
    val CallGreen = Color(0xFF1FB45D)
    val EndRed = Color(0xFFF2463E)
    val Missed = Color(0xFFE5443F)

    val CardRadius = 26.dp

    val Light = OneUiPalette(
        background = Color(0xFFF6F6F6),
        card = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF000000),
        textSecondary = Color(0xFF737373),
        textTertiary = Color(0xFFA6A6A6),
        divider = Color(0xFFE6E6E6),
        chip = Color(0xFFEDEDED)
    )

    val Dark = OneUiPalette(
        background = Color(0xFF000000),
        card = Color(0xFF171717),
        textPrimary = Color(0xFFD0D0D0), // soft white: pure white on black is harsh on the eyes
        textSecondary = Color(0xFF9E9E9E),
        textTertiary = Color(0xFF6B6B6B),
        divider = Color(0xFF2B2B2B),
        chip = Color(0xFF2A2A2A)
    )
}

@Composable
fun oneUiColors(): OneUiPalette = if (isSystemInDarkTheme()) OneUi.Dark else OneUi.Light

/**
 * Background for one row of a One UI card group: only the first row gets the top corners and only
 * the last row the bottom corners, so consecutive rows read as a single rounded card.
 */
fun Modifier.oneUiGroupRow(isFirst: Boolean, isLast: Boolean, color: Color): Modifier {
    val r = OneUi.CardRadius
    val shape = RoundedCornerShape(
        topStart = if (isFirst) r else 0.dp,
        topEnd = if (isFirst) r else 0.dp,
        bottomStart = if (isLast) r else 0.dp,
        bottomEnd = if (isLast) r else 0.dp
    )
    return this.background(color, shape)
}

/**
 * One UI page: a large centered title (with optional subtitle) that shrinks into a small toolbar
 * title as the content scrolls, and grows back when the content is pulled down at the top.
 * [content] must contain the scrollable (e.g. LazyColumn); nested scrolling drives the collapse.
 */
@Composable
fun OneUiCollapsingPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    val palette = oneUiColors()
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val expandedHeight: Dp = (screenHeight * 0.26f).coerceIn(150f, 240f).dp
    val collapsedHeight: Dp = 56.dp
    val rangePx = with(LocalDensity.current) { (expandedHeight - collapsedHeight).toPx() }
    val currentRange by rememberUpdatedState(rangePx)
    var collapsedPx by remember { mutableFloatStateOf(0f) }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy >= 0f) return Offset.Zero
                // Content moving up: shrink the header before the list scrolls.
                val next = (collapsedPx - dy).coerceAtMost(currentRange)
                val used = next - collapsedPx
                collapsedPx = next
                return Offset(0f, -used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy <= 0f) return Offset.Zero
                // List already at its top and still pulled down: grow the header back.
                val next = (collapsedPx - dy).coerceAtLeast(0f)
                val used = collapsedPx - next
                collapsedPx = next
                return Offset(0f, used)
            }
        }
    }
    val progress = if (rangePx > 0f) (collapsedPx / rangePx).coerceIn(0f, 1f) else 0f
    val headerHeight = expandedHeight - (expandedHeight - collapsedHeight) * progress
    val bigAlpha by animateFloatAsState((1f - progress * 1.8f).coerceIn(0f, 1f), label = "oneUiBigTitle")
    val smallAlpha by animateFloatAsState(((progress - 0.6f) / 0.4f).coerceIn(0f, 1f), label = "oneUiSmallTitle")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .nestedScroll(connection)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
                    .alpha(bigAlpha),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    color = palette.textPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = palette.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(collapsedHeight)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = palette.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(smallAlpha)
                )
                actions()
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
    }
}

/** One UI text-only bottom tabs: selected label bold in primary text, the rest grey. */
@Composable
fun OneUiBottomTabs(
    tabs: List<Pair<Int, String>>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = oneUiColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (id, label) ->
            val selected = id == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                    // 14sp so five labels ("Voicemail" is the longest) fit on a narrow phone.
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
