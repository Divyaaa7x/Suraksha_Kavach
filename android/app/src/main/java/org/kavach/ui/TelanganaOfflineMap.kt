package org.kavach.ui

import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kavach.graph.Node
import org.kavach.graph.RoadGraph
import org.kavach.graph.Status
import org.kavach.graph.StatusView
import org.kavach.pack.IndiaAdminArea
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

private data class OfflineRoad(val id: Long, val highway: String, val name: String, val inState: Boolean,
    val minZoom: Int, val points: List<Pair<Double, Double>>)
private data class OfflinePlace(val kind: String, val name: String, val lat: Double, val lon: Double)
private data class MapBounds(val south: Double, val north: Double, val west: Double, val east: Double, val zoom: Int)
private data class TelanganaSnapshot(val roads: Map<Pair<Int, Boolean>, Path>, val route: Path,
    val water: Path, val boundaries: Map<Int, Path>, val places: List<OfflinePlace>)

/** Reads only geometries intersecting the current viewport from the signed offline map database. */
private class TelanganaMapStore(file: File) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private fun cell(value: Double) = floor(value * 10.0).toInt()

    fun query(bounds: MapBounds, highlightedWays: Set<Long>): Pair<List<OfflineRoad>, List<OfflinePlace>> {
        val roads = ArrayList<OfflineRoad>()
        val ids = highlightedWays.take(500)
        val highlightClause = if (ids.isEmpty()) "" else " OR r.id IN (${ids.joinToString(",")})"
        db.rawQuery(
            "SELECT r.id,r.highway,r.name,r.in_state,r.min_zoom,r.geometry FROM roads r WHERE " +
                "(r.id IN (SELECT item_id FROM spatial_cells WHERE layer='road' AND min_zoom BETWEEN 6 AND ? " +
                "AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?)" + highlightClause +
                ") AND r.min_zoom<=? ORDER BY r.min_zoom LIMIT 15000",
            arrayOf(bounds.zoom.toString(), cell(bounds.west).toString(), cell(bounds.east).toString(),
                cell(bounds.south).toString(), cell(bounds.north).toString(), bounds.zoom.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val bytes = c.getBlob(5)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val points = ArrayList<Pair<Double, Double>>(bytes.size / 8)
                while (buffer.remaining() >= 8) points += buffer.float.toDouble() to buffer.float.toDouble()
                roads += OfflineRoad(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4), points)
            }
        }
        val places = ArrayList<OfflinePlace>()
        db.rawQuery(
            "SELECT kind,name,lat,lon FROM places WHERE min_zoom<=? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 1200",
            arrayOf(bounds.zoom.toString(), bounds.south.toString(), bounds.north.toString(), bounds.west.toString(), bounds.east.toString()),
        ).use { c -> while (c.moveToNext()) places += OfflinePlace(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)) }
        return roads to places
    }

    fun queryWater(bounds: MapBounds): List<List<Pair<Double, Double>>> {
        val lines = ArrayList<List<Pair<Double, Double>>>()
        db.rawQuery(
            "SELECT w.geometry FROM waterways w WHERE w.id IN (SELECT item_id FROM spatial_cells " +
                "WHERE layer='water' AND min_zoom=0 AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?) LIMIT 8000",
            arrayOf(cell(bounds.west).toString(), cell(bounds.east).toString(),
                cell(bounds.south).toString(), cell(bounds.north).toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val bytes = c.getBlob(0)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val line = ArrayList<Pair<Double, Double>>(bytes.size / 8)
                while (b.remaining() >= 8) line += b.float.toDouble() to b.float.toDouble()
                lines += line
            }
        }
        return lines
    }

    fun queryBoundaries(bounds: MapBounds): Map<Int, List<List<Pair<Double, Double>>>> {
        val lines = LinkedHashMap<Int, MutableList<List<Pair<Double, Double>>>>()
        db.rawQuery(
            "SELECT b.admin_level,b.geometry FROM boundaries b WHERE b.id IN (SELECT item_id FROM spatial_cells " +
                "WHERE layer='boundary' AND min_zoom BETWEEN 6 AND ? AND cell_x BETWEEN ? AND ? " +
                "AND cell_y BETWEEN ? AND ?) AND b.min_zoom<=?",
            arrayOf(bounds.zoom.toString(), cell(bounds.west).toString(), cell(bounds.east).toString(),
                cell(bounds.south).toString(), cell(bounds.north).toString(), bounds.zoom.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val bytes = c.getBlob(1)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val line = ArrayList<Pair<Double, Double>>(bytes.size / 8)
                while (b.remaining() >= 8) line += b.float.toDouble() to b.float.toDouble()
                lines.getOrPut(c.getInt(0)) { ArrayList() } += line
            }
        }
        return lines
    }

    override fun close() = db.close()
}

@Composable
fun TelanganaOfflineMap(
    database: File,
    state: IndiaAdminArea,
    graph: RoadGraph,
    statuses: Map<String, StatusView>,
    selectedCrossing: String?,
    highlightedEdges: Set<String>,
    onSelectCrossing: (String?) -> Unit,
    mapHeight: androidx.compose.ui.unit.Dp = 500.dp,
) {
    val store = remember(database) { TelanganaMapStore(database) }
    DisposableEffect(store) { onDispose { store.close() } }
    val stateRing = remember(state) { state.rings.first() }
    val centerLat = remember(stateRing) { stateRing.map { it.first }.average() }
    val centerLon = remember(stateRing) { stateRing.map { it.second }.average() }
    val lonScale = cos(Math.toRadians(centerLat))
    val xValues = remember(stateRing) { stateRing.map { it.second * lonScale } }
    val yValues = remember(stateRing) { stateRing.map { -it.first } }
    val statePath = remember(stateRing, lonScale) {
        Path().apply {
            stateRing.forEachIndexed { i, (lat, lon) ->
                val x = (lon * lonScale).toFloat(); val y = (-lat).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
    val crossings = remember(graph) {
        graph.edges.values.mapNotNull { edge ->
            val id = edge.crossingId ?: return@mapNotNull null
            val a = graph.nodes[edge.u] ?: return@mapNotNull null
            val b = graph.nodes[edge.v] ?: return@mapNotNull null
            id to Node(id, "crossing", (a.lat + b.lat) / 2, (a.lon + b.lon) / 2, "", null)
        }.distinctBy { it.first }
    }
    val routeWays = remember(graph, highlightedEdges) {
        highlightedEdges.mapNotNull { graph.edges[it]?.id?.let(::routeWayId) }.toSet()
    }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var reset by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(TelanganaSnapshot(emptyMap(), Path(), Path(), emptyMap(), emptyList())) }
    var bounds by remember { mutableStateOf<MapBounds?>(null) }
    var mapUnavailable by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    val stateWidth = xValues.max() - xValues.min()
    val stateHeight = yValues.max() - yValues.min()

    LaunchedEffect(size, reset, state) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        scale = minOf(size.width / stateWidth.toFloat(), size.height / stateHeight.toFloat()) * .90f
        offset = Offset((size.width / 2f - centerLon * lonScale * scale).toFloat(),
            (size.height / 2f + centerLat * scale).toFloat())
    }
    LaunchedEffect(size, highlightedEdges, state) {
        if (highlightedEdges.isEmpty() || size.width == 0 || size.height == 0) return@LaunchedEffect
        val points = highlightedEdges.flatMap { id ->
            graph.edges[id]?.let { edge -> listOfNotNull(graph.nodes[edge.u], graph.nodes[edge.v]) }.orEmpty()
        }
        if (points.isEmpty()) return@LaunchedEffect
        val minX = points.minOf { it.lon * lonScale }.toFloat()
        val maxX = points.maxOf { it.lon * lonScale }.toFloat()
        val minY = points.minOf { -it.lat }.toFloat()
        val maxY = points.maxOf { -it.lat }.toFloat()
        val routeWidth = (maxX - minX).coerceAtLeast(.005f)
        val routeHeight = (maxY - minY).coerceAtLeast(.005f)
        scale = (minOf(size.width / routeWidth, size.height / routeHeight) * .72f).coerceAtMost(18000f)
        offset = Offset(size.width / 2f - (minX + maxX) / 2f * scale,
            size.height / 2f - (minY + maxY) / 2f * scale)
    }
    LaunchedEffect(size, scale, offset, routeWays) {
        if (scale <= 0f || size.width == 0 || size.height == 0) return@LaunchedEffect
        val west = ((-offset.x / scale) / lonScale).toDouble()
        val east = (((size.width - offset.x) / scale) / lonScale).toDouble()
        val north = (offset.y / scale).toDouble()
        val south = ((offset.y - size.height) / scale).toDouble()
        val zoom = (7 + ln((scale / (minOf(size.width / stateWidth.toFloat(), size.height / stateHeight.toFloat()) * .90f)).coerceAtLeast(.01f)) / ln(2.0)).toInt().coerceIn(6, 16)
        bounds = MapBounds(south, north, west, east, zoom)
    }
    LaunchedEffect(bounds, routeWays) {
        val area = bounds ?: return@LaunchedEffect
        delay(90)
        val result = try { withContext(Dispatchers.IO) {
            val (roads, places) = store.query(area, routeWays)
            val paths = LinkedHashMap<Pair<Int, Boolean>, Path>()
            val routePath = Path()
                val roadLabels = ArrayList<OfflinePlace>()
            val adminLines = store.queryBoundaries(area).mapValues { (_, lines) ->
                Path().apply {
                    lines.forEach { line -> line.forEachIndexed { i, (lat, lon) ->
                        val x = (lon * lonScale).toFloat(); val y = (-lat).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    } }
                }
            }
            roads.forEach { road ->
                val klass = when (road.highway) {
                    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link" -> 2
                    "secondary", "secondary_link", "tertiary", "tertiary_link" -> 1
                    else -> 0
                }
                val p = paths.getOrPut(klass to road.inState) { Path() }
                road.points.forEachIndexed { i, (lat, lon) ->
                    val x = (lon * lonScale).toFloat(); val y = (-lat).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    if (road.id in routeWays) {
                        if (i == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
                    }
                    val labelZoom = when (klass) { 2 -> 9; 1 -> 11; else -> 14 }
                    if (road.inState && road.name.isNotBlank() && area.zoom >= labelZoom && roadLabels.size < 400) {
                        val point = road.points[road.points.size / 2]
                        roadLabels += OfflinePlace("road", road.name, point.first, point.second)
                    }
                }
            }
            val water = Path()
            store.queryWater(area).forEach { line ->
                line.forEachIndexed { i, (lat, lon) ->
                    val x = (lon * lonScale).toFloat(); val y = (-lat).toFloat()
                    if (i == 0) water.moveTo(x, y) else water.lineTo(x, y)
                }
            }
                TelanganaSnapshot(paths, routePath, water, adminLines, places + roadLabels)
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mapUnavailable = true
            return@LaunchedEffect
        }
        snapshot = result
        mapUnavailable = false
    }

    Box(
        Modifier.fillMaxWidth().height(mapHeight).onSizeChanged { size = it }
            .background(Color(0xFFF1F3ED))
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (currentScale <= 0f) return@detectTransformGestures
                    val fitScale = minOf(size.width / stateWidth.toFloat(), size.height / stateHeight.toFloat()) * .90f
                    val next = (currentScale * zoom).coerceIn(fitScale * .55f, 18000f)
                    val factor = next / currentScale
                    offset = centroid - (centroid - currentOffset) * factor + pan
                    scale = next
                }
            }
            .pointerInput(crossings) {
                detectTapGestures { tap ->
                    val fitScale = minOf(size.width / stateWidth.toFloat(), size.height / stateHeight.toFloat()) * .90f
                    if (currentScale < fitScale * 4f) {
                        onSelectCrossing(null)
                        return@detectTapGestures
                    }
                    val hit = crossings.minByOrNull { (_, p) ->
                        val at = Offset((p.lon * lonScale).toFloat() * currentScale + currentOffset.x, -p.lat.toFloat() * currentScale + currentOffset.y)
                        (at - tap).getDistanceSquared()
                    }
                    if (hit != null) {
                        val p = hit.second
                        val at = Offset((p.lon * lonScale).toFloat() * currentScale + currentOffset.x, -p.lat.toFloat() * currentScale + currentOffset.y)
                        onSelectCrossing(if ((at - tap).getDistanceSquared() < 44f * density.density * 44f * density.density) hit.first else null)
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (scale <= 0f) return@Canvas
            clipRect {
                withTransform({
                    translate(offset.x, offset.y)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    val strokeScale = 1f / scale
                    drawPath(statePath, Color(0xFFFAFBF8))
                    drawPath(statePath, Color(0xFFCAD6C8), style = Stroke(1.8f * strokeScale))
                    snapshot.boundaries.forEach { (level, path) ->
                        val width = when (level) { 5 -> 1.7f; 6 -> 1.25f; else -> .8f } * strokeScale
                        drawPath(path, Color(0xFFADBCB0), style = Stroke(width,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * strokeScale, 3f * strokeScale))))
                    }
                    drawPath(snapshot.water, Color(0xFF9CCBDD), style = Stroke(2.8f * strokeScale, cap = StrokeCap.Round))
                    snapshot.roads.toSortedMap(compareBy<Pair<Int, Boolean>> { if (it.second) 0 else 1 }.thenBy { it.first }).forEach { (key, path) ->
                        val (klass, inState) = key
                        val color = if (!inState) Color(0xFFD7DCD7) else when (klass) {
                            2 -> Color(0xFFEACB91)
                            1 -> Color(0xFFD5D7D0)
                            else -> Color(0xFFE0E2DC)
                        }
                        val fill = if (!inState) Color(0xFFF7F8F4) else when (klass) {
                            2 -> Color(0xFFFFE5AE)
                            1 -> Color(0xFFFFFEFA)
                            else -> Color(0xFFFEFEFB)
                        }
                        val outer = when (klass) { 2 -> 8f; 1 -> 5f; else -> 3f } * strokeScale
                        drawPath(path, color, style = Stroke(outer, cap = StrokeCap.Round))
                        drawPath(path, fill, style = Stroke((outer * .62f).coerceAtLeast(.5f * strokeScale), cap = StrokeCap.Round))
                    }
                    drawPath(snapshot.route, Color.White, style = Stroke(10f * strokeScale, cap = StrokeCap.Round))
                    drawPath(snapshot.route, Color(0xFF087E68), style = Stroke(6f * strokeScale, cap = StrokeCap.Round))
                }
                val occupied = ArrayList<Rect>()
                val textStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF34443D))
                snapshot.places.sortedBy { when (it.kind) { "city" -> 0; "town" -> 1; "hospital", "clinic", "doctors" -> 2; "village" -> 3; "road" -> 4; else -> 5 } }.forEach { place ->
                    if (place.name.isBlank()) return@forEach
                    val labelZoom = when (place.kind) {
                        "city" -> 7; "town" -> 9; "village" -> 11; "hamlet", "suburb", "neighbourhood", "locality" -> 13
                        "hospital", "clinic", "doctors" -> 11; "road" -> 9; else -> 16
                    }
                    if ((bounds?.zoom ?: 0) < labelZoom) return@forEach
                    val world = Offset((place.lon * lonScale).toFloat(), -place.lat.toFloat())
                    val screen = world * scale + offset
                    if (screen.x !in 4f..size.width.toFloat() - 80f || screen.y !in 4f..size.height.toFloat() - 10f) return@forEach
                    val isRoad = place.kind == "road"
                    val medical = place.kind == "hospital" || place.kind == "clinic" || place.kind == "doctors"
                    val labelStyle = if (isRoad) textStyle.copy(fontSize = 9.sp, color = Color(0xFF56645C)) else textStyle
                    val label = measurer.measure(place.name, labelStyle)
                    val topLeft = Offset(if (isRoad) screen.x + 2f else screen.x + 5f,
                        screen.y - label.size.height / 2f)
                    val box = Rect(topLeft.x, topLeft.y, topLeft.x + label.size.width, topLeft.y + label.size.height)
                    if (occupied.any { box.left < it.right + 6f && box.right + 6f > it.left &&
                            box.top < it.bottom + 4f && box.bottom + 4f > it.top }) return@forEach
                    occupied += box
                    if (!isRoad) drawCircle(if (medical) Color(0xFFC4655C) else Color(0xFF486B5C),
                        radius = if (place.kind == "city" || place.kind == "town") 3.2f else 2.2f, center = screen)
                    drawText(label, topLeft = topLeft)
                }
                val fitScale = minOf(size.width / stateWidth.toFloat(), size.height / stateHeight.toFloat()) * .90f
                if (scale >= fitScale * 4f) {
                    crossings.forEach { (id, point) ->
                        val screen = Offset((point.lon * lonScale).toFloat() * scale + offset.x, -point.lat.toFloat() * scale + offset.y)
                        val color = statusColor(statuses[id]?.status ?: Status.UNKNOWN)
                        drawCircle(color, if (id == selectedCrossing) 6f else 4f, screen)
                        drawCircle(Color.White, 1.2f, screen)
                    }
                }
            }
        }
        Text(if (mapUnavailable) "Offline map data unavailable" else "© OpenStreetMap contributors · ODbL", fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(Color(0xDDFFFFFF)).padding(horizontal = 5.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton(onClick = { zoomMap(scope, 1.5f, size, scale, offset) { s, o -> scale = s; offset = o } },
                modifier = Modifier.semantics { contentDescription = "Zoom in" }) { Text("+") }
            FilledTonalIconButton(onClick = { zoomMap(scope, .67f, size, scale, offset) { s, o -> scale = s; offset = o } },
                modifier = Modifier.semantics { contentDescription = "Zoom out" }) { Text("−") }
            FilledTonalIconButton(onClick = { reset++; scale = 0f },
                modifier = Modifier.semantics { contentDescription = "Show all Telangana" }) { Text("◎") }
        }
    }
}

private fun zoomMap(scope: kotlinx.coroutines.CoroutineScope, factor: Float, size: IntSize, scale: Float, offset: Offset, update: (Float, Offset) -> Unit) {
    if (scale <= 0f || size.width == 0 || size.height == 0) return
    scope.launch {
        val initial = scale
        val center = Offset(size.width / 2f, size.height / 2f)
        val next = (initial * factor).coerceIn(1f, 18000f)
        animate(initial, next, animationSpec = tween(180)) { value, _ ->
            val ratio = value / initial
            update(value, center - (center - offset) * ratio)
        }
    }
}

private fun routeWayId(edge: String): Long? = Regex("^R(\\d+)\\.").find(edge)?.groupValues?.get(1)?.toLongOrNull()
