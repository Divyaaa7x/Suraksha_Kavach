package org.kavach.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A blurred copy of the map, [width] x [height] px on screen, that glass panels sample from. */
class Backdrop(val image: ImageBitmap, val width: Int, val height: Int)

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/** Frost a map snapshot: shrink, then box-blur three times (close to a Gaussian). Runs in a few ms. */
fun frost(map: Bitmap): Backdrop {
    val small = Bitmap.createScaledBitmap(map, (map.width / 8).coerceAtLeast(1), (map.height / 8).coerceAtLeast(1), true)
    val w = small.width
    val h = small.height
    var pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    repeat(3) { pixels = boxBlur(boxBlur(pixels, w, h, 4, horizontal = true), w, h, 4, horizontal = false) }
    return Backdrop(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap(), map.width, map.height)
}

/** One-direction running-average blur with clamped edges. Output is opaque. */
internal fun boxBlur(source: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean): IntArray {
    val out = IntArray(source.size)
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val span = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val j = i.coerceIn(0, length - 1)
            return if (horizontal) source[line * w + j] else source[j * w + line]
        }
        var red = 0
        var green = 0
        var blue = 0
        for (i in -radius..radius) {
            val c = at(i)
            red += c shr 16 and 255
            green += c shr 8 and 255
            blue += c and 255
        }
        for (i in 0 until length) {
            val color = (255 shl 24) or (red / span shl 16) or (green / span shl 8) or (blue / span)
            if (horizontal) out[line * w + i] = color else out[i * w + line] = color
            val enter = at(i + radius + 1)
            val leave = at(i - radius)
            red += (enter shr 16 and 255) - (leave shr 16 and 255)
            green += (enter shr 8 and 255) - (leave shr 8 and 255)
            blue += (enter and 255) - (leave and 255)
        }
    }
    return out
}

/**
 * Frosted glass: the blurred map behind this element, a tint, a light wash from the top and a
 * bright rim. Without a snapshot (offline canvas map) the tint turns nearly opaque.
 */
fun Modifier.glass(shape: Shape, tint: Color): Modifier = composed {
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val vivid = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.7f) }) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (backdrop != null) {
                drawImage(
                    backdrop.image,
                    dstOffset = IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
                    dstSize = IntSize(backdrop.width, backdrop.height),
                    colorFilter = vivid,
                    filterQuality = FilterQuality.High,
                )
            }
            drawRect(tint.copy(alpha = if (backdrop != null) tint.alpha * 0.62f else tint.alpha))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent),
                endY = minOf(size.height, 120.dp.toPx())))
        }
        .border(0.9.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.07f))), shape)
}

/** Tappable that dips and springs back, like a physical control. */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
