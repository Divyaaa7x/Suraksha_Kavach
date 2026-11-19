package org.kavach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kavach.graph.RoadGraph
import org.kavach.graph.StatusView
import org.kavach.graph.Status
import org.kavach.pack.MapFeature
import kotlin.math.cos

/**
 * Real map drawn from the OpenStreetMap road network in the offline pack (no tiles, no network).
 * Geometry is projected once into cached paths; pan and zoom only change a transform.
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
private const val W = 1000f  // map width in projected units

private class Projection(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    private val lonRange = (maxLon - minLon).coerceAtLeast(1e-9)
    private val latRange = (maxLat - minLat).coerceAtLeast(1e-9)
    val h: Float = (W * (latRange / (lonRange * cos(Math.toRadians((minLat + maxLat) / 2))))).toFloat()
    fun x(lon: Double): Float = ((lon - minLon) / lonRange * W).toFloat()
    fun y(lat: Double): Float = ((maxLat - lat) / latRange * h).toFloat()
    fun at(lat: Double, lon: Double) = Offset(x(lon), y(lat))
}

private class Geometry(
    val roads: Path,
    val crossings: List<Pair<String, Path>>,      // crossing id -> its segments
    val water: List<Pair<String, Path>>,          // kind -> line
    val settlements: List<Pair<Offset, String>>,
    val facilities: List<Pair<Offset, String>>,
    val crossingMid: Map<String, Offset>,
)

private fun buildGeometry(graph: RoadGraph, features: List<MapFeature>, p: Projection): Geometry {
    val roads = Path()
    val byCrossing = LinkedHashMap<String, Path>()
    val mids = HashMap<String, Offset>()
    for (e in graph.edges.values) {
        val a = graph.nodes[e.u] ?: continue
        val b = graph.nodes[e.v] ?: continue
        val pa = p.at(a.lat, a.lon)
        val pb = p.at(b.lat, b.lon)
        val cid = e.crossingId
        if (cid == null) {
            roads.moveTo(pa.x, pa.y); roads.lineTo(pb.x, pb.y)
        } else {
            val path = byCrossing.getOrPut(cid) { Path() }
            path.moveTo(pa.x, pa.y); path.lineTo(pb.x, pb.y)
            val prev = mids[cid]
            mids[cid] = if (prev == null) Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2) else prev
        }
    }
    val water = features.map { f ->
        val path = Path()
        f.coords.forEachIndexed { i, (lat, lon) ->
            val pt = p.at(lat, lon)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        f.kind to path
    }
    val settlements = graph.nodes.values.filter { it.kind == "settlement" }
        .map { p.at(it.lat, it.lon) to it.name }
    val facilities = graph.nodes.values.filter { it.kind == "facility" }
        .map { p.at(it.lat, it.lon) to it.name }
    return Geometry(roads, byCrossing.toList(), water, settlements, facilities, mids)
}

@Composable
fun RealMap(
    graph: RoadGraph,
    features: List<MapFeature>,
    statuses: Map<String, StatusView>,
    selected: String?,
    onSelect: (String?) -> Unit,
    highlightedEdges: Set<String> = emptySet(),
) {
    val p = remember(graph) {
        val lats = graph.nodes.values.map { it.lat }
        val lons = graph.nodes.values.map { it.lon }
        Projection(lats.min(), lats.max(), lons.min(), lons.max())
    }
    val geo = remember(graph, features) { buildGeometry(graph, features, p) }
    val routePath = remember(graph, highlightedEdges) {
        Path().apply {
            highlightedEdges.forEach { id ->
                val edge = graph.edges[id] ?: return@forEach
                val a = graph.nodes[edge.u] ?: return@forEach
                val b = graph.nodes[edge.v] ?: return@forEach
                val start = p.at(a.lat, a.lon)
                val end = p.at(b.lat, b.lon)
                moveTo(start.x, start.y)
                lineTo(end.x, end.y)
            }
        }
    }
    val measurer = rememberTextMeasurer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) }        // px per projected unit; 0 = not fitted yet
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    LaunchedEffect(graph, highlightedEdges, size) {
        if (highlightedEdges.isEmpty() || size.width == 0 || size.height == 0) return@LaunchedEffect
        val points = highlightedEdges.mapNotNull { id ->
            val edge = graph.edges[id] ?: return@mapNotNull null
            val a = graph.nodes[edge.u] ?: return@mapNotNull null
            val b = graph.nodes[edge.v] ?: return@mapNotNull null
            p.at(a.lat, a.lon) to p.at(b.lat, b.lon)
        }.flatMap { listOf(it.first, it.second) }
        if (points.isEmpty()) return@LaunchedEffect
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val routeW = (maxX - minX).coerceAtLeast(1f)
        val routeH = (maxY - minY).coerceAtLeast(1f)
        val focusScale = (minOf(size.width / routeW, size.height / routeH) * 0.72f)
            .coerceIn(size.width / W * 0.5f, 200f)
        val center = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        scale = focusScale
        offset = Offset(size.width / 2f - center.x * focusScale, size.height / 2f - center.y * focusScale)
    }

    Box(
        Modifier.fillMaxWidth().height(420.dp)
            .onSizeChanged { size = it }
            .background(Color(0xFFEEF1EE))
            .pointerInput(graph) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(size.width / W * 0.5f, 200f)
                    val k = newScale / scale
                    offset = centroid - (centroid - offset) * k + pan
                    scale = newScale
                }
            }
            .pointerInput(graph, statuses) {
                detectTapGestures(onTap = { tap ->
                    var best: String? = null
                    var bd = 40f * density.density * 40f * density.density
                    for ((cid, mid) in geo.crossingMid) {
                        val s = mid * scale + offset
                        val d = (s - tap).getDistanceSquared()
                        if (d < bd) { bd = d; best = cid }
                    }
                    onSelect(best)
                })
            }
    ) {
        if (scale == 0f && size.width > 0) {
            // First layout: fit the whole district on screen.
            val fit = minOf(size.width / W, size.height / p.h) * 0.95f
            scale = fit
            offset = Offset((size.width - W * fit) / 2f, (size.height - p.h * fit) / 2f)
        }
        Canvas(Modifier.fillMaxWidth().height(420.dp)) {
            if (scale == 0f) return@Canvas
            clipRect {
                withTransform({
                    translate(offset.x, offset.y)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    val lw = 1f / scale
                    // Water first, under roads.
                    for ((kind, path) in geo.water) {
                        drawPath(path, waterColor(kind), style = Stroke(width = (if (kind == "river") 3f else 1.5f) * lw, cap = StrokeCap.Round))
                    }
                    drawPath(geo.roads, Color(0xFF8A929C), style = Stroke(width = lw))
                    for ((cid, path) in geo.crossings) {
                        val st = statuses[cid]?.status ?: Status.UNKNOWN
                        val w = (if (cid == selected) 7f else 4f) * lw
                        drawPath(path, statusColor(st), style = Stroke(width = w, cap = StrokeCap.Round))
                    }
                    for ((pt, _) in geo.settlements) {
                        drawCircle(Color.White, 4f * lw, pt)
                        drawCircle(Color(0xFF14171C), 4f * lw, pt, style = Stroke(width = 1.5f * lw))
                    }
                    for ((pt, _) in geo.facilities) {
                        drawRect(Color(0xFF14171C), pt - Offset(5f * lw, 5f * lw), Size(10f * lw, 10f * lw))
                    }
                    // Draw last so crossings, settlements, and facilities cannot hide a candidate route.
                    if (highlightedEdges.isNotEmpty()) {
                        drawPath(routePath, Color.White,
                            style = Stroke(width = 22f * lw, cap = StrokeCap.Round))
                        drawPath(routePath, Color(0xFF00695C),
                            style = Stroke(width = 14f * lw, cap = StrokeCap.Round))
                    }
                }
                if (scale > 6f && highlightedEdges.isEmpty()) drawLabels(geo, measurer, scale, offset)
            }
        }
        Text(
            "© OpenStreetMap contributors · ODbL",
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(Color(0xDDFFFFFF)).padding(horizontal = 4.dp),
        )
    }
}

private fun DrawScope.drawLabels(geo: Geometry, measurer: TextMeasurer, scale: Float, offset: Offset) {
    val style = TextStyle(fontSize = 10.sp, color = Color(0xFF14171C))
    val visible = { p: Offset ->
        val s = p * scale + offset
        s.x in 0f..size.width && s.y in 0f..size.height
    }
    for ((pt, name) in geo.settlements + geo.facilities) {
        if (name.isEmpty() || !visible(pt)) continue
        val layout = measurer.measure(name, style)
        val s = pt * scale + offset
        drawText(layout, topLeft = Offset(s.x + 6f, s.y - 14f))
    }
}

private fun waterColor(kind: String): Color = when (kind) {
    "river" -> Color(0xFF4A90C2)
    "canal" -> Color(0xFF3F7FB0)
    "stream" -> Color(0xFF7FB4D9)
    else -> Color(0xFF9CC3E0)
}
