package org.kavach.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Detent { PEEK, HALF, FULL }

val CAPSULE_MARGIN = 26.dp

/** Where a drag released at [top] with [velocity] px/s comes to rest. [anchors] is indexed by Detent.ordinal. */
internal fun restingDetent(top: Float, velocity: Float, anchors: FloatArray): Detent {
    val projected = top + velocity * 0.12f // about 0.12 s of travel
    return Detent.entries.minBy { abs(anchors[it.ordinal] - projected) }
}

/** Position of the sheet's top edge in px, with spring snapping that keeps the release velocity. */
@Stable
class AtlasSheetState {
    var detent by mutableStateOf(Detent.PEEK)
        private set
    internal val top = Animatable(0f)
    internal var anchors = FloatArray(3)
    internal var ready by mutableStateOf(false)

    /** Top edge of the sheet in px. */
    val topPx: Float get() = top.value
    /** 0 while resting as a capsule, 1 from half height upwards. */
    val expansion: Float get() = if (!ready) 0f else ((anchors[0] - top.value) / (anchors[0] - anchors[1])).coerceIn(0f, 1f)
    /** 0 up to half height, 1 when fully open. */
    val coverage: Float get() = if (!ready) 0f else ((anchors[1] - top.value) / (anchors[1] - anchors[2])).coerceIn(0f, 1f)

    suspend fun settle(to: Detent, velocity: Float = 0f) {
        detent = to
        if (ready) top.animateTo(anchors[to.ordinal], spring(dampingRatio = 0.86f, stiffness = 380f), velocity)
    }

    internal suspend fun release(velocity: Float) = settle(restingDetent(top.value, velocity, anchors), velocity)

    /** Drag with rubber-band resistance past the outermost detents. */
    internal suspend fun dragBy(delta: Float) {
        val next = top.value + delta
        val outside = next < anchors[Detent.FULL.ordinal] || next > anchors[Detent.PEEK.ordinal]
        top.snapTo(if (outside) top.value + delta * 0.3f else next)
    }
}

// shortcut: spring and detent values are by eye; replace with values measured from screen recordings.
@Composable
fun AtlasSheet(
    state: AtlasSheetState,
    containerHeight: Dp,
    peekHeight: Dp,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val containerPx = with(density) { containerHeight.toPx() }
    val peekPx = with(density) { peekHeight.toPx() }
    val topGap = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.toPx() }

    LaunchedEffect(containerPx, peekPx, topGap) {
        state.anchors = floatArrayOf(containerPx - peekPx, containerPx * 0.52f, topGap)
        state.top.snapTo(state.anchors[state.detent.ordinal])
        state.ready = true
    }

    // Hand-off: content scrolls only once fully open; pulling down at the top drags the sheet.
    val nested = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0 || state.top.value <= state.anchors[Detent.FULL.ordinal] + 1f) return Offset.Zero
                scope.launch { state.dragBy(available.y) }
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                scope.launch { state.dragBy(available.y) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(state.top.value - state.anchors[state.detent.ordinal]) < 1f) return Velocity.Zero
                state.release(available.y)
                return available
            }
        }
    }

    if (!state.ready) return
    val open = state.expansion
    // At rest the sheet floats as an inset capsule; pulling it up docks it to the screen edges.
    val shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp,
        bottomStart = 34.dp * (1f - open), bottomEnd = 34.dp * (1f - open))
    Surface(
        modifier = Modifier
            .offset { IntOffset(0, state.top.value.roundToInt()) }
            .layout { measurable, constraints ->
                val margin = (CAPSULE_MARGIN.toPx() * (1f - state.expansion)).roundToInt()
                val width = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)
                val height = (containerPx - state.top.value - margin).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, height) { placeable.place(margin, 0) }
            }
            .glass(shape, MaterialTheme.colorScheme.surface.copy(alpha = 0.86f + 0.12f * open)),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.nestedScroll(nested)) {
            Column(
                Modifier.fillMaxWidth().draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> scope.launch { state.dragBy(delta) } },
                    onDragStopped = { velocity -> state.release(velocity) },
                ),
            ) {
                Box(
                    Modifier.padding(top = 6.dp, bottom = 7.dp).align(Alignment.CenterHorizontally)
                        .size(width = 38.dp, height = 5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), RoundedCornerShape(50)),
                )
                header()
            }
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
        }
    }
}
