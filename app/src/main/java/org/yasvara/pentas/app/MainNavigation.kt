/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.app

import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.yasvara.pentas.R
import org.yasvara.pentas.data.fpsstats.SessionListItem
import org.yasvara.pentas.settings.fpsstats.FpsStatsActivity
import org.yasvara.pentas.settings.fpsstats.FpsStatsDetailActivity
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.ui.kit.Glyph
import org.yasvara.pentas.ui.kit.label
import org.yasvara.pentas.ui.kit.num
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class MainTab(val title: String, val icon: Int) {
    GAMES("Games", R.drawable.sym_videogame_asset),
    SESSIONS("Sessions", R.drawable.sym_monitoring),
    SETTINGS("Settings", R.drawable.sym_settings),
}

/** Keep the three main destinations in one activity without building up a screen stack. */
@Composable
fun PentasMain() {
    val pagerState = rememberPagerState(pageCount = { MainTab.entries.size })
    val tabState = rememberSaveableStateHolder()
    val navigationScope = rememberCoroutineScope()
    val hapticView = LocalView.current
    var cachedGames by remember { mutableStateOf(emptyList<String>()) }
    var cachedSessions by remember { mutableStateOf(emptyList<SessionListItem>()) }
    BackHandler(pagerState.currentPage != MainTab.GAMES.ordinal || pagerState.isScrollInProgress) {
        navigationScope.launch { pagerState.animateScrollToPage(MainTab.GAMES.ordinal) }
    }
    LaunchedEffect(pagerState, hapticView) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { hapticView.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK) }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val destination = MainTab.entries[page]
            tabState.SaveableStateProvider(destination.name) {
                when (destination) {
                    MainTab.GAMES -> Library(cachedGames) { cachedGames = it }
                    MainTab.SESSIONS -> SessionsTab(cachedSessions) { cachedSessions = it }
                    MainTab.SETTINGS -> PentasSettingsScreen(
                        onBack = { navigationScope.launch { pagerState.animateScrollToPage(MainTab.GAMES.ordinal) } },
                        showBack = false,
                    )
                }
            }
        }
        MainNavigationBar(pagerState) { destination ->
            navigationScope.launch { pagerState.animateScrollToPage(destination.ordinal) }
        }
    }
}

@Composable
private fun MainNavigationBar(pagerState: PagerState, onSelect: (MainTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(28.dp),
        color = cs.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp).clip(RoundedCornerShape(23.dp))) {
            val itemWidth = maxWidth / MainTab.entries.size
            val itemWidthPx = with(density) { itemWidth.toPx() }.coerceAtLeast(1f)
            val dragScale by rememberUpdatedState(
                pagerState.layoutInfo.pageSize / itemWidthPx *
                    if (layoutDirection == LayoutDirection.Rtl) -1f else 1f,
            )
            val dragState = remember(pagerState) {
                object : DraggableState {
                    override fun dispatchRawDelta(delta: Float) {
                        pagerState.dispatchRawDelta(delta * dragScale)
                    }

                    override suspend fun drag(
                        dragPriority: MutatePriority,
                        block: suspend DragScope.() -> Unit,
                    ) {
                        pagerState.scroll(dragPriority) {
                            val pagerScroll = this
                            val dragScope = object : DragScope {
                                override fun dragBy(pixels: Float) {
                                    pagerScroll.scrollBy(pixels * dragScale)
                                }
                            }
                            dragScope.block()
                        }
                    }
                }
            }
            val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                .coerceIn(0f, MainTab.entries.lastIndex.toFloat())
            val activeIndex = position.roundToInt()
            val stretch = (abs(position - activeIndex) * 2f).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        val target = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                            .roundToInt().coerceIn(0, MainTab.entries.lastIndex)
                        pagerState.animateScrollToPage(target)
                    },
                ),
            ) {
                Box(
                    Modifier.offset(x = itemWidth * position + 4.dp)
                        .width((itemWidth - 8.dp).coerceAtLeast(0.dp))
                        .height(58.dp)
                        .padding(vertical = 2.dp)
                        .graphicsLayer { scaleX = 1f + stretch * 0.12f }
                        .clip(RoundedCornerShape(22.dp))
                        .background(cs.secondaryContainer),
                )
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    MainTab.entries.forEachIndexed { index, tab ->
                        MainNavigationItem(
                            modifier = Modifier.weight(1f),
                            tab = tab,
                            active = activeIndex == index,
                            onSelect = { onSelect(tab) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainNavigationItem(
    modifier: Modifier,
    tab: MainTab,
    active: Boolean,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "navigation item press",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) cs.onSecondaryContainer else cs.onSurfaceVariant,
        label = "navigation item content color",
    )
    Column(
        modifier = modifier.clip(RoundedCornerShape(22.dp))
            .selectable(
                selected = active,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = { onSelect() },
            )
            .heightIn(min = 58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Glyph(tab.icon, foreground, 20.dp)
        Text(tab.title, style = label(11.sp, if (active) 650 else 500), color = foreground)
    }
}

@Composable
private fun SessionsTab(cachedSessions: List<SessionListItem>, onLoaded: (List<SessionListItem>) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var rev by rememberSaveable { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { rev++ }
    val sessions by produceState(cachedSessions, rev) {
        value = withContext(Dispatchers.IO) {
            FpsStatsRepository(ctx.applicationContext).listSessions()
        }
        onLoaded(value)
    }
    val recent = sessions.sortedByDescending { it.startTimeMs }
    Screen("Sessions", onBack = null, bottomBar = true) {
        Text("Your recorded play sessions", style = label(14.sp), color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        if (recent.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
                    .padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(R.drawable.sym_monitoring, cs.primary, 28.dp)
                Text("No sessions yet", style = label(19.sp, 600), color = cs.onSurface)
                Text("Record a session from the in-game panel to see its performance here.",
                    style = label(13.sp), color = cs.onSurfaceVariant)
            }
        } else {
            val last = recent.first()
            Column(
                Modifier.fillMaxWidth().pressable { openSession(ctx, last) }
                    .clip(RoundedCornerShape(28.dp)).background(cs.primaryContainer).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("LAST SESSION", style = label(11.sp, 650), color = cs.onPrimaryContainer)
                Text(duration(last.durationSec), style = num(35.sp, 620), color = cs.onPrimaryContainer)
                Text("${last.appName}  ·  ${"%.0f".format(last.fpsAvg)} fps average",
                    style = label(13.sp), color = cs.onPrimaryContainer)
                Text(date(last.startTimeMs), style = label(11.sp),
                    color = cs.onPrimaryContainer.copy(alpha = 0.75f))
            }
            Text("Past 7 days", style = label(20.sp, 600), color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            WeekActivity(recent)
            Text("Recent sessions", style = label(20.sp, 600), color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            recent.take(4).forEach { session ->
                Row(
                    Modifier.fillMaxWidth().pressable { openSession(ctx, session) }
                        .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Glyph(R.drawable.sym_videogame_asset, cs.primary, 24.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(session.appName, style = label(15.sp, 600), color = cs.onSurface,
                            maxLines = 1)
                        Text("${date(session.startTimeMs)}  ·  ${duration(session.durationSec)}",
                            style = label(11.sp), color = cs.onSurfaceVariant)
                    }
                    Text("%.0f fps".format(session.fpsAvg), style = label(13.sp, 600), color = cs.primary)
                }
            }
            if (recent.size > 4) Text("View all ${recent.size} sessions",
                Modifier.fillMaxWidth().pressable { ctx.startActivity(Intent(ctx, FpsStatsActivity::class.java)) }
                    .clip(RoundedCornerShape(18.dp)).background(cs.secondaryContainer).padding(16.dp),
                style = label(14.sp, 600), color = cs.onSecondaryContainer)
        }
    }
}

private fun openSession(context: android.content.Context, session: SessionListItem) {
    context.startActivity(Intent(context, FpsStatsDetailActivity::class.java)
        .putExtra("session_id", session.id))
}

private fun date(timeMs: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMs))

private fun duration(seconds: Int): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${seconds / 60 % 60} min"
}

@Composable
private fun WeekActivity(sessions: List<SessionListItem>) {
    val cs = MaterialTheme.colorScheme
    val days = remember(sessions) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        (6 downTo 0).map { offset ->
            val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -offset) }
            val next = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val seconds = sessions.sumOf { session ->
                val sessionEnd = session.startTimeMs + session.durationSec * 1_000L
                ((minOf(sessionEnd, next.timeInMillis) - maxOf(session.startTimeMs, day.timeInMillis))
                    .coerceAtLeast(0L) / 1_000L).toInt()
            }
            SimpleDateFormat("EEE", Locale.getDefault()).format(day.time) to seconds
        }
    }
    val total = days.sumOf { it.second }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (total > 0) "${duration(total)} played" else "No sessions this week",
            style = label(14.sp, 600), color = cs.onSurface)
        if (total > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val max = days.maxOf { it.second }.coerceAtLeast(1)
            days.forEach { (name, seconds) ->
                Column(Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "$name: ${if (seconds > 0) duration(seconds) else "No play time"}"
                }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.width(18.dp).height((seconds * 88f / max).coerceAtLeast(if (seconds > 0) 4f else 0f).dp)
                            .clip(RoundedCornerShape(7.dp)).background(cs.primary))
                    }
                    Text(name, style = label(11.sp), color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
