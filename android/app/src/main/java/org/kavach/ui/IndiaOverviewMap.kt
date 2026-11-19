package org.kavach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kavach.pack.IndiaAdminArea
import kotlin.math.cos

private const val MAP_WIDTH = 1000f
private const val MIN_LAT = 6.0
private const val MAX_LAT = 38.0
private const val MIN_LON = 68.0
private const val MAX_LON = 98.0
private val LON_CORRECTION = cos(Math.toRadians(22.0)).toFloat()
private val MAP_HEIGHT = (MAP_WIDTH * (MAX_LAT - MIN_LAT) /
    ((MAX_LON - MIN_LON) * LON_CORRECTION)).toFloat()

private data class AreaShape(val area: IndiaAdminArea, val rings: List<List<Offset>>, val path: Path)

private fun project(lat: Double, lon: Double) = Offset(
    ((lon - MIN_LON) / (MAX_LON - MIN_LON) * MAP_WIDTH * LON_CORRECTION).toFloat(),
    ((MAX_LAT - lat) / (MAX_LON - MIN_LON) * MAP_WIDTH).toFloat(),
)

private fun shapes(areas: List<IndiaAdminArea>): List<AreaShape> = areas.map { area ->
    val rings = area.rings.map { ring -> ring.map { (lat, lon) -> project(lat, lon) } }
    val path = Path().apply {
        rings.forEach { ring ->
            ring.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
    }
    AreaShape(area, rings, path)
}

private fun contains(ring: List<Offset>, point: Offset): Boolean {
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.y > point.y) != (b.y > point.y) &&
            point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** Offline national context map. State geometry is reference data, not a routable road graph. */
@Composable
fun IndiaOverviewMap(areas: List<IndiaAdminArea>, selectedCode: String?, onSelect: (String) -> Unit) {
    val geometry = remember(areas) { shapes(areas) }
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxWidth().height(460.dp)
            .background(Color(0xFFEAF0F2))
            .onSizeChanged { size = it }
            .pointerInput(size) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (scale > 0f) {
                        val next = (scale * zoom).coerceIn(size.width / MAP_WIDTH * 0.55f, 120f)
                        val factor = next / scale
                        offset = centroid - (centroid - offset) * factor + pan
                        scale = next
                    }
                }
            }
            .pointerInput(size, geometry, scale, offset) {
                detectTapGestures { tap ->
                    if (scale > 0f) {
                        val point = (tap - offset) / scale
                        geometry.firstOrNull { shape -> shape.rings.any { contains(it, point) } }
                            ?.area?.code?.let(onSelect)
                    }
                }
            }
    ) {
        if (scale == 0f && size.width > 0) {
            val fit = minOf(size.width / MAP_WIDTH, size.height / MAP_HEIGHT) * 0.94f
            scale = fit
            offset = Offset((size.width - MAP_WIDTH * fit) / 2f,
                (size.height - MAP_HEIGHT * fit) / 2f)
        }
        Canvas(Modifier.fillMaxWidth().height(460.dp)) {
            if (scale <= 0f) return@Canvas
            withTransform({ translate(offset.x, offset.y); scale(scale, scale, pivot = Offset.Zero) }) {
                geometry.forEach { shape ->
                    val fill = if (shape.area.code == selectedCode) Color(0xFFD1E7F2) else Color(0xFFF8FAFB)
                    drawPath(shape.path, fill)
                    drawPath(shape.path, Color(0xFF65747D), style = Stroke(width = 1.4f / scale))
                }
            }
            if (scale > 1f || selectedCode != null) {
                val labelStyle = TextStyle(fontSize = 8.sp, color = Color(0xFF33434C))
                geometry.forEach { shape ->
                    if (scale <= 1f && shape.area.code != selectedCode) return@forEach
                    val largest = shape.rings.maxByOrNull { it.size } ?: return@forEach
                    val center = largest.dropLast(1).reduceOrNull { a, b -> a + b }
                        ?.div((largest.size - 1).coerceAtLeast(1).toFloat())
                        ?: return@forEach
                    val screen = center * scale + offset
                    if (screen.x in 0f..this.size.width && screen.y in 0f..this.size.height) {
                        drawText(measurer.measure(shape.area.name, labelStyle),
                            topLeft = Offset(screen.x, screen.y))
                    }
                }
            }
        }
        Text(
            "India state reference · Natural Earth · not an authoritative boundary",
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                .background(Color(0xDDFFFFFF)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
