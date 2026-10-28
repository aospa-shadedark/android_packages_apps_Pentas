/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.ui.kit

import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pentas UI kit: the Yasvara M3 Expressive language at compact density, shared by the
 * in-game sidebar and the app. Colours always come from the dynamic scheme.
 */
object Kit {
    val Gap = 6.dp
    val Card = 18.dp
    val Inner = 5.dp
    val Control = 30.dp

    /** Google Sans Flex from the ROM's named family; Roboto where the ROM lacks it. */
    val Sans: FontFamily by lazy {
        val tf = Typeface.create("google-sans-flex", Typeface.NORMAL)
        if (tf == Typeface.DEFAULT) FontFamily.Default else FontFamily(tf)
    }

    /** Numbers: Google Sans Flex at ROND 100 (fully round terminals). */
    val Round: FontFamily by lazy {
        // The font ships in /system on some ROMs and in /product on others.
        val file = listOf("/system/fonts", "/product/fonts")
            .map { File(it, "GoogleSansFlex-Regular.ttf") }.firstOrNull { it.exists() }
        if (file == null) Sans else FontFamily(
            listOf(400, 500, 600, 700).map { w ->
                Font(
                    file, FontWeight(w),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(w),
                        FontVariation.Setting("ROND", 100f),
                    ),
                )
            }
        )
    }

    /** Spring used for every press morph (fast spatial). */
    fun <T> fast() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)

    /** Spring for size/position changes (default spatial). */
    fun <T> default() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)

    /** Corners for item [index] of [count] in a connected vertical group. */
    fun connected(index: Int, count: Int, outer: Dp = Card, inner: Dp = Inner): Shape {
        val top = if (index == 0) outer else inner
        val bottom = if (index == count - 1) outer else inner
        return RoundedCornerShape(top, top, bottom, bottom)
    }
}

@Composable
fun label(size: TextUnit = 12.sp, weight: Int = 500) = TextStyle(
    fontFamily = Kit.Sans, fontSize = size, fontWeight = FontWeight(weight),
)

@Composable
fun num(size: TextUnit, weight: Int = 600) = TextStyle(
    fontFamily = Kit.Round, fontSize = size, fontWeight = FontWeight(weight),
)

/** A tonal surface block. [shape] lets callers join blocks into a connected group. */
@Composable
fun Block(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Kit.Card),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    padding: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(shape).background(color).padding(padding),
        verticalArrangement = Arrangement.spacedBy(Kit.Gap),
        content = content,
    )
}

/** Small primary-coloured heading inside a page. */
@Composable
fun Section(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = 4.dp, top = 4.dp),
        style = label(10.5.sp, 600), color = MaterialTheme.colorScheme.primary,
    )
}

/** Honest scope tag next to a control: when the change takes effect. */
@Composable
fun Tag(text: String, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (warn) cs.tertiaryContainer else cs.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = label(10.sp, 600),
        color = if (warn) cs.onTertiaryContainer else cs.onSurfaceVariant,
    )
}

@Composable
fun Glyph(@DrawableRes icon: Int, tint: Color = Color.Unspecified, size: Dp = 18.dp, desc: String? = null) {
    Icon(
        painterResource(icon), desc, Modifier.size(size),
        tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
    )
}

/** Round tonal icon button (header, rail). */
@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) size / 4 else size / 2, Kit.fast(), label = "r")
    val bg by animateColorAsState(if (active) cs.primary else cs.surfaceContainerHigh, label = "bg")
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(bg)
            .combinedClickable(src, null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (active) cs.onPrimary else cs.onSurface, size * 0.5f) }
}

/** Item [index] of [count] in a connected button group: the selected one morphs to a pill. */
@Composable
fun segmentShape(index: Int, count: Int, selected: Boolean, outer: Dp): Shape {
    val r by animateDpAsState(if (selected) outer else 6.dp, Kit.fast(), label = "seg")
    return when {
        selected -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(outer, r, r, outer)
        index == count - 1 -> RoundedCornerShape(r, outer, outer, r)
        else -> RoundedCornerShape(r)
    }
}

/**
 * Connected segmented control (M3E button group). The selected segment morphs to a pill;
 * the rest keep tight inner corners. [enabled] greys single options out without hiding them.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<String, T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    height: Dp = Kit.Control,
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(modifier.fillMaxWidth().height(height).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, (text, value) ->
            val on = value == selected
            val ok = enabled(value)
            val shape = segmentShape(i, options.size, on, height / 2)
            val bg by animateColorAsState(
                if (on) cs.secondaryContainer else cs.surfaceContainerHigh, label = "segbg",
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(shape).background(bg)
                    .selectable(selected = on, enabled = ok, role = Role.RadioButton) {
                        if (!on) {
                            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                            onSelect(value)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = label(11.5.sp, if (on) 650 else 500),
                    color = when {
                        !ok -> cs.onSurface.copy(alpha = 0.38f)
                        on -> cs.onSecondaryContainer
                        else -> cs.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Compact M3E switch: 40×24, thumb grows when on. */
@Composable
fun Toggle(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true, description: String? = null) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val x by animateDpAsState(if (checked) 18.dp else 4.dp, Kit.fast(), label = "tx")
    val d by animateDpAsState(if (checked) 16.dp else 12.dp, Kit.fast(), label = "td")
    val track by animateColorAsState(if (checked) cs.primary else cs.surfaceContainerHighest, label = "tt")
    Box(
        Modifier.size(40.dp, 24.dp).clip(CircleShape).background(track)
            .then(if (checked) Modifier else Modifier.background(Color.Transparent))
            .then(if (onChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { value ->
                view.performHapticFeedback(
                    if (value) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
                )
                onChange(value)
            } else Modifier)
            .semantics { description?.let { contentDescription = it } },
    ) {
        Box(
            Modifier.offset(x = x, y = (24.dp - d) / 2).size(d).clip(CircleShape)
                .background(if (checked) cs.onPrimary else cs.outline)
        )
    }
}

/** One settings row: label (+ optional tag/value) and a trailing control. */
@Composable
fun SettingRow(
    text: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    tag: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = label(12.5.sp), color = cs.onSurface, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        tag?.invoke()
        value?.let { Text(it, style = num(12.sp, 500), color = cs.onSurfaceVariant) }
        trailing()
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: (@Composable () -> Unit)? = null) {
    val view = LocalView.current
    SettingRow(text, modifier = Modifier.toggleable(checked, role = Role.Switch) { value ->
        view.performHapticFeedback(
            if (value) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
        )
        onChange(value)
    }, tag = tag) { Toggle(checked, null) }
}

/**
 * M3E slider: a thick active track, a bar handle, and a gap before the inactive track.
 * [steps] snaps to discrete stops; [onCommit] fires once on release.
 */
@Composable
fun Slider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onCommit: (Float) -> Unit = {},
    height: Dp = 24.dp,
    @DrawableRes icon: Int = 0,
    marker: Float? = null,
    description: String? = null,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val activeColor = if (enabled) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f)
    val view = LocalView.current
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val currentValue by rememberUpdatedState(value)
    var drag by remember { mutableFloatStateOf(Float.NaN) }
    var lastTickTime by remember { mutableLongStateOf(0L) }
    fun tickPosition(f: Float): Int {
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
        val tickCount = if (steps > 0) steps + 1 else 20
        return (((f - range.start) / span) * tickCount).roundToInt()
    }
    fun snap(f: Float): Float {
        val v = range.start + f.coerceIn(0f, 1f) * (range.endInclusive - range.start)
        if (steps <= 0) return v
        val step = (range.endInclusive - range.start) / (steps + 1)
        return range.start + ((v - range.start) / step).roundToInt() * step
    }
    fun updateFromGesture(next: Float) {
        val snapped = snap(next)
        val now = SystemClock.uptimeMillis()
        if (tickPosition(snapped) != tickPosition(currentValue) && now - lastTickTime >= 40L) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            lastTickTime = now
        }
        change(snapped)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height)
            .progressSemantics(value, range, steps)
            .semantics {
                if (!enabled) disabled()
                description?.let { contentDescription = it }
                setProgress { requested ->
                    val next = snap((requested - range.start) / (range.endInclusive - range.start))
                    if (!enabled || next == value) false else {
                        change(next)
                        commit(next)
                        true
                    }
                }
            }
            .pointerInput(range, steps, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    val v = snap(o.x / size.width)
                    updateFromGesture(o.x / size.width)
                    commit(v)
                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                }
            }
            .pointerInput(range, steps, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = o.x / size.width; updateFromGesture(drag) },
                    onDragEnd = {
                        if (!drag.isNaN()) {
                            commit(snap(drag))
                            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        }
                        drag = Float.NaN
                    },
                    onDragCancel = { drag = Float.NaN },
                ) { c, d ->
                    drag = (drag + d / size.width).coerceIn(0f, 1f)
                    updateFromGesture(drag); c.consume()
                }
            },
    ) {
        val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val w = maxWidth
        val track = height * 2 / 3
        val handleX = (w - 3.dp) * f
        val r = track / 2
        Box(
            Modifier.offset(y = (height - track) / 2).width((handleX - 4.dp).coerceAtLeast(0.dp)).height(track)
                .clip(RoundedCornerShape(r, 3.dp, 3.dp, r)).background(activeColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (icon != 0 && handleX > 30.dp) {
                Box(Modifier.padding(start = 6.dp)) { Glyph(icon, cs.onPrimary, 14.dp) }
            }
        }
        Box(Modifier.offset(x = handleX).size(3.dp, height).clip(CircleShape).background(activeColor))
        Box(
            Modifier.offset(x = handleX + 7.dp, y = (height - track) / 2)
                .width((w - handleX - 7.dp).coerceAtLeast(0.dp)).height(track)
                .clip(RoundedCornerShape(3.dp, r, r, 3.dp)).background(cs.surfaceContainerHighest),
        )
        marker?.let { m ->
            val mf = ((m - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(
                Modifier.offset(x = (w - 4.dp) * mf, y = height / 2 - 2.dp).size(4.dp)
                    .clip(CircleShape).background(cs.tertiary)
            )
        }
    }
}

/** Two-handle slider over [0, count); the handle nearest the touch moves. */
@Composable
fun RangeSlider(
    low: Int,
    high: Int,
    count: Int,
    onChange: (Int, Int) -> Unit,
    onCommit: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    marker: Int? = null,
    height: Dp = 24.dp,
    enabled: Boolean = true,
    description: String = "Range",
    valueText: (Int) -> String = { it.toString() },
) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val lo by rememberUpdatedState(low)
    val hi by rememberUpdatedState(high)
    val currentRange by rememberUpdatedState(low to high)
    val last = (count - 1).coerceAtLeast(1)
    var dragLow by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(Float.NaN) }
    var lastTickTime by remember { mutableLongStateOf(0L) }
    fun index(f: Float) = (f.coerceIn(0f, 1f) * last).roundToInt()
    fun move(i: Int): Pair<Int, Int> = if (dragLow) i.coerceAtMost(hi) to hi else lo to i.coerceAtLeast(lo)
    fun updateFromGesture(i: Int): Pair<Int, Int> {
        val next = move(i)
        val now = SystemClock.uptimeMillis()
        if (next != currentRange && now - lastTickTime >= 40L) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            lastTickTime = now
        }
        change(next.first, next.second)
        return next
    }
    fun pickLow(f: Float): Boolean {
        val i = f * last
        return if (lo == hi) i < lo else abs(i - lo) < abs(i - hi)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height)
            .pointerInput(count, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    val f = o.x / size.width
                    dragLow = pickLow(f)
                    val (a, b) = updateFromGesture(index(f))
                    commit(a, b)
                    view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                }
            }
            .pointerInput(count, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = o.x / size.width; dragLow = pickLow(drag) },
                    onDragEnd = {
                        if (!drag.isNaN()) {
                            val (a, b) = move(index(drag))
                            commit(a, b)
                            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        }
                        drag = Float.NaN
                    },
                    onDragCancel = { drag = Float.NaN },
                ) { c, d ->
                    drag = (drag + d / size.width).coerceIn(0f, 1f)
                    updateFromGesture(index(drag)); c.consume()
                }
            },
    ) {
        val w = maxWidth - 3.dp
        val track = height * 2 / 3
        val r = track / 2
        val activeColor = if (enabled) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.48f)
        val x0 = w * (low.toFloat() / last)
        val x1 = w * (high.toFloat() / last)
        val y = (height - track) / 2
        if (x0 > 7.dp) Box(
            Modifier.offset(y = y).width(x0 - 4.dp).height(track)
                .clip(RoundedCornerShape(r, 3.dp, 3.dp, r)).background(cs.surfaceContainerHighest),
        )
        val start = if (x0 > 7.dp) x0 + 7.dp else 0.dp
        Box(
            Modifier.offset(x = start, y = y).width((x1 - 4.dp - start).coerceAtLeast(0.dp)).height(track)
                .clip(RoundedCornerShape(if (x0 > 7.dp) 3.dp else r, 3.dp, 3.dp, if (x0 > 7.dp) 3.dp else r))
                .background(activeColor),
        )
        Box(Modifier.offset(x = x0).size(3.dp, height).clip(CircleShape).background(activeColor)
            .semantics {
                contentDescription = "$description minimum"
                progressBarRangeInfo = ProgressBarRangeInfo(low.toFloat(), 0f..high.toFloat(), (high - 1).coerceAtLeast(0))
                stateDescription = valueText(low)
                if (!enabled) disabled() else setProgress { requested ->
                    val next = requested.roundToInt().coerceIn(0, high)
                    if (next == low) false else { change(next, hi); commit(next, hi); true }
                }
            })
        Box(Modifier.offset(x = x1).size(3.dp, height).clip(CircleShape).background(activeColor)
            .semantics {
                contentDescription = "$description maximum"
                progressBarRangeInfo = ProgressBarRangeInfo(high.toFloat(), low.toFloat()..last.toFloat(), (last - low - 1).coerceAtLeast(0))
                stateDescription = valueText(high)
                if (!enabled) disabled() else setProgress { requested ->
                    val next = requested.roundToInt().coerceIn(low, last)
                    if (next == high) false else { change(lo, next); commit(lo, next); true }
                }
            })
        Box(
            Modifier.offset(x = x1 + 7.dp, y = y).width((w - x1 - 4.dp).coerceAtLeast(0.dp)).height(track)
                .clip(RoundedCornerShape(3.dp, r, r, 3.dp)).background(cs.surfaceContainerHighest),
        )
        marker?.let { m ->
            Box(
                Modifier.offset(x = w * (m.toFloat() / last), y = height / 2 - 2.dp).size(4.dp)
                    .clip(CircleShape).background(cs.tertiary)
            )
        }
    }
}

/** Page header inside the sidebar or a sheet: back, title, optional trailing actions. */
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    @DrawableRes back: Int,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(back, "Back", onBack)
        Text(title, style = label(15.sp, 600), color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Generic list item used by the detail pages (Bluetooth, Wi-Fi, apps). */
@Composable
fun ListItem(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.secondaryContainer else cs.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title, style = label(13.sp, if (selected) 600 else 550), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) cs.onSecondaryContainer else cs.onSurface,
            )
            subtitle?.let {
                Text(it, style = label(11.sp), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}
