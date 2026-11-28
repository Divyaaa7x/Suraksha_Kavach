package org.kavach.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.kavach.PackState
import org.kavach.graph.Node
import org.kavach.graph.RoadGraph
import org.kavach.graph.haversineM
import org.kavach.graph.Status

private enum class Page(val title: String) {
    BROWSE(""), PLACE(""), DIRECTIONS("Directions"), REPORT("Report"), STATUS("Field status"),
    FIELD_TASKS("Field tasks"), ANALYSIS("Analysis"), SYNC("Sync"), AUDIT("Audit"), REGIONS("Regions")
}

private data class SearchHit(val key: String, val title: String, val subtitle: String,
    val lat: Double, val lon: Double, val crossingId: String? = null)

/**
 * Map-first home: full-screen map, floating controls, and a three-stop sheet holding search, place
 * cards and every field tool. Icons and colours are our own; map data is OpenStreetMap.
 */
@Composable
fun AtlasHome(vm: KavachViewModel, onRequestLocation: () -> Unit, onStartNearby: () -> Unit) {
    val packState by vm.packState.collectAsState()
    val ready = packState as? PackState.Ready ?: return
    val graph = vm.graph() ?: return
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val sheet = remember { AtlasSheetState() }

    var page by remember { mutableStateOf(Page.BROWSE) }
    var query by remember { mutableStateOf("") }
    var place by remember { mutableStateOf<SearchHit?>(null) }
    var focusPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var resetSignal by remember { mutableIntStateOf(0) }
    var awaitingFix by remember { mutableStateOf(false) }
    val recents = remember { mutableStateListOf<SearchHit>() }
    val online = rememberOnline()
    val context = LocalContext.current
    val vector = online || indiaSaved(context)
    var previewing by remember { mutableStateOf(false) }
    var webHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var webNote by remember { mutableStateOf("") }
    var tilted by remember { mutableStateOf(false) }
    var bearing by remember { mutableStateOf(0f) }
    var backdrop by remember { mutableStateOf<Backdrop?>(null) }
    var credit by remember { mutableStateOf(true) }
    // Map data credit shows briefly at launch, then stays available at the bottom of the sheet.
    LaunchedEffect(Unit) {
        delay(5000)
        credit = false
    }

    val index = remember(graph) {
        graph.nodes.values.filter { it.kind != "junction" && it.name.isNotBlank() }
            .map { SearchHit(it.id, it.name, it.kind.replaceFirstChar(Char::uppercase), it.lat, it.lon) }
            // OSM often maps one site twice; merge same-name points within about 100 m.
            .distinctBy { Triple(it.title, "%.3f".format(it.lat), "%.3f".format(it.lon)) }
            .sortedBy { it.title }
    }
    val here = vm.fix?.let { it.lat to it.lon }
    val results = remember(query, index, here) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else {
            // Nearest first, and say from where: a name list in A-Z order is useless in an emergency.
            val (lat, lon) = here ?: (18.05 to 78.26)
            val from = if (here != null) "you" else "Medak"
            index.filter { it.title.contains(q, true) }
                .map { hit -> hit to haversineM(Node("", "", lat, lon, "", null), Node("", "", hit.lat, hit.lon, "", null)) }
                .sortedBy { it.second }.take(20)
                .map { (hit, metres) -> hit.copy(subtitle = "%s · %.1f km from %s".format(hit.subtitle, metres / 1000, from)) } +
                graph.crossings.values.filter { it.id.contains(q, true) || it.kind.contains(q, true) }
                    .take(10).mapNotNull { crossingHit(graph, it.id) }
        }
    }

    LaunchedEffect(query.isNotEmpty()) {
        if (query.isNotEmpty() && page == Page.BROWSE) sheet.settle(Detent.FULL)
    }
    LaunchedEffect(query, online) {
        webHits = emptyList()
        webNote = ""
        val q = query.trim()
        if (!online || q.length < 3) return@LaunchedEffect
        delay(400)
        webNote = "Searching India…"
        try {
            webHits = withContext(Dispatchers.IO) { searchIndia(q, vm.fix?.let { it.lat to it.lon } ?: (18.05 to 78.26)) }.map {
                SearchHit("web:${it.lat},${it.lon}", it.name, it.detail, it.lat, it.lon)
            }.distinctBy { it.key }
            webNote = if (webHits.isEmpty()) "Nothing more found in India" else "More places in India"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            webNote = "India search unavailable right now"
        }
    }

    fun open(next: Page, detent: Detent) {
        page = next
        vm.showLocalDetail = next != Page.REGIONS
        focus.clearFocus()
        scope.launch { sheet.settle(detent) }
    }

    fun dismiss() {
        vm.clearRoute()
        previewing = false
        vm.selectedCrossing = null
        place = null
        focusPoint = null
        query = ""
        open(Page.BROWSE, Detent.PEEK)
    }

    fun select(hit: SearchHit) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        place = hit
        vm.selectedCrossing = hit.crossingId
        focusPoint = hit.lat to hit.lon
        recents.removeAll { it.key == hit.key }
        recents.add(0, hit)
        if (recents.size > 5) recents.removeAt(recents.lastIndex)
        query = ""
        open(Page.PLACE, Detent.HALF)
    }

    LaunchedEffect(Unit) { vm.refresh() }
    // A crossing tapped on the map opens its card.
    LaunchedEffect(vm.selectedCrossing) {
        val id = vm.selectedCrossing ?: return@LaunchedEffect
        if (page != Page.REPORT && place?.crossingId != id) {
            place = crossingHit(graph, id)
            open(Page.PLACE, Detent.HALF)
        }
    }
    LaunchedEffect(vm.fix, awaitingFix) {
        val fix = vm.fix
        if (awaitingFix && fix != null) {
            focusPoint = fix.lat to fix.lon
            awaitingFix = false
        }
    }
    LaunchedEffect(vm.highlightedRouteEdges) {
        if (vm.highlightedRouteEdges.isNotEmpty()) sheet.settle(Detent.HALF)
    }
    BackHandler(enabled = page != Page.BROWSE || sheet.detent != Detent.PEEK) { dismiss() }

    CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { vector }) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        val peek = 72.dp + CAPSULE_MARGIN // capsule height plus its gap from the bottom edge
        val containerPx = with(LocalDensity.current) { height.toPx() }

        val database = ready.offlineMapDatabase
        val telangana = vm.indiaAreas().firstOrNull { it.code == "IN-TG" }
        if (vector) {
            val markers = remember(graph, vm.statuses) {
                graph.edges.values.filter { it.crossingId != null }.distinctBy { it.crossingId }.mapNotNull { e ->
                    val a = graph.nodes[e.u] ?: return@mapNotNull null
                    val b = graph.nodes[e.v] ?: return@mapNotNull null
                    val status = vm.statuses[e.crossingId]?.status ?: Status.UNKNOWN
                    MapCrossing(e.crossingId!!, (a.lat + b.lat) / 2, (a.lon + b.lon) / 2,
                        "#%06X".format(0xFFFFFF and statusColor(status).toArgb()))
                }
            }
            // One continuous line from the ordered path: MapLibre drops tiny separate pieces when zoomed out.
            val route = remember(vm.route, vm.highlightedRouteCoordinates) {
                if (vm.highlightedRouteCoordinates.isNotEmpty()) listOf(vm.highlightedRouteCoordinates)
                else listOf(vm.route?.path.orEmpty().mapNotNull { id -> graph.nodes[id]?.let { it.lat to it.lon } })
            }
            val user = vm.fix?.let { it.lat to it.lon }
            AtlasMap(
                dark = dark, crossings = markers, route = route, focus = focusPoint,
                pin = focusPoint?.takeIf { it != user }, user = user, tilted = tilted, resetSignal = resetSignal,
                bottomPaddingPx = if (sheet.detent == Detent.PEEK) 0 else (containerPx * 0.48f).toInt(),
                onBearing = { bearing = it }, onSelectCrossing = { vm.selectedCrossing = it },
                previewing = previewing, onPreviewDone = { previewing = false }, onBackdrop = { backdrop = it },
                modifier = Modifier.fillMaxSize(),
            )
            AnimatedVisibility(credit, Modifier.align(Alignment.BottomCenter).padding(bottom = peek + 2.dp),
                enter = fadeIn(), exit = fadeOut()) {
                Text("© OpenStreetMap contributors", fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
            }
        } else if (database != null && telangana != null) {
            TelanganaOfflineMap(
                database = database, state = telangana, graph = graph, statuses = vm.statuses,
                selectedCrossing = vm.selectedCrossing, highlightedEdges = vm.highlightedRouteEdges,
                onSelectCrossing = { vm.selectedCrossing = it }, mapHeight = height,
                focusLocation = focusPoint, highlightedRouteCoordinates = vm.highlightedRouteCoordinates,
                chromeless = true, dark = dark, resetSignal = resetSignal, bottomInset = peek,
                userLocation = vm.fix?.let { it.lat to it.lon },
            )
        } else {
            Box(Modifier.fillMaxSize().padding(top = 96.dp)) {
                RealMap(graph, vm.features(), vm.statuses, vm.selectedCrossing,
                    onSelect = { vm.selectedCrossing = it }, highlightedEdges = vm.highlightedRouteEdges,
                    mapHeight = height - peek - 96.dp)
            }
        }

        WeatherChip(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 22.dp, top = 8.dp),
            online, vm.fix?.let { it.lat to it.lon })
        // Controls ride on top of the sheet and fade out as it covers the map.
        val rideSheet = Modifier
            .offset { IntOffset(0, (sheet.topPx - containerPx).roundToInt()) }
            .graphicsLayer { alpha = 1f - sheet.coverage }
        if (sheet.coverage < 0.5f) {
            GlassCircle(Modifier.align(Alignment.BottomStart).then(rideSheet).padding(start = 22.dp, bottom = 16.dp),
                "North is up. Show whole state", onClick = { focusPoint = null; resetSignal++ }) {
                Text("N", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Canvas(Modifier.matchParentSize()) {
                    val r = size.minDimension / 2f
                    for (i in 0 until 12) rotate(i * 30f - bearing) {
                        drawLine(if (i == 0) Color(0xFFFF453A) else Color.Gray, Offset(r, r * .16f),
                            Offset(r, r * .30f), (if (i == 0) 3.dp else 1.5.dp).toPx(), StrokeCap.Round)
                    }
                }
            }
            GlassPill(Modifier.align(Alignment.BottomEnd).then(rideSheet).padding(end = 22.dp, bottom = 16.dp),
                RoundedCornerShape(50)) {
                Column {
                    ControlButton(if (online) "Toggle 3D view" else "Map regions",
                        onClick = { if (online) tilted = !tilted else open(Page.REGIONS, Detent.FULL) }) { tint ->
                        val w = size.width / 3f // folded-map glyph
                        for (i in 0..2) {
                            val lift = if (i == 1) size.height * .12f else 0f
                            val drop = size.height * .12f - lift
                            drawPath(Path().apply {
                                moveTo(i * w + 1.5f, lift); lineTo((i + 1) * w - 1.5f, drop)
                                lineTo((i + 1) * w - 1.5f, size.height - lift)
                                lineTo(i * w + 1.5f, size.height - drop); close()
                            }, tint)
                        }
                    }
                    ControlButton("Show my location", onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        awaitingFix = true
                        onRequestLocation()
                    }) { tint ->
                        drawPath(Path().apply {
                            moveTo(size.width, 0f); lineTo(size.width * .56f, size.height)
                            lineTo(size.width * .46f, size.height * .54f); lineTo(0f, size.height * .44f); close()
                        }, tint)
                    }
                }
            }
        }

        AtlasSheet(
            state = sheet, containerHeight = height, peekHeight = peek,
            header = {
                if (page == Page.BROWSE) {
                    SearchField(
                        query = query,
                        onQuery = { query = it },
                        onFocus = { scope.launch { sheet.settle(Detent.FULL) } },
                        onCancel = ::dismiss,
                        showCancel = sheet.detent == Detent.FULL,
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (page == Page.PLACE) place?.title.orEmpty() else page.title,
                            Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(48.dp).clickable(onClick = ::dismiss), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Clear, contentDescription = "Close", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            },
        ) {
            val pageModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().imePadding().padding(horizontal = 16.dp)
            when (page) {
                Page.BROWSE -> if (query.isBlank()) {
                    Column(pageModifier) {
                        if (recents.isNotEmpty()) {
                            Heading("Recents")
                            CardGroup { recents.forEach { hit -> HitRow(hit) { select(hit) } } }
                        }
                        Heading("Find nearby")
                        NearbyRow { query = it }
                        Heading("Field tools")
                        CardGroup {
                            val tools = Page.entries.filter { it.title.isNotEmpty() }
                            tools.forEachIndexed { i, target ->
                                ToolRow(target) { open(target, Detent.FULL) }
                                if (i < tools.lastIndex) HorizontalDivider(Modifier.padding(start = 58.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            }
                        }
                        Text("Advisory only. A person approves every access decision.\n\n"
                            + "Map data © OpenStreetMap contributors (ODbL). Tiles: OpenFreeMap. Relief: AWS Terrain Tiles.\n"
                            + "Borders: Natural Earth (India view). Weather: Open-Meteo (CC BY 4.0). Search: Photon.",
                            Modifier.padding(top = 18.dp, start = 4.dp, bottom = 28.dp), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                        if (results.isEmpty() && !online) item { Text("No offline results", Modifier.padding(16.dp)) }
                        items(results, key = { it.key }) { hit -> HitRow(hit) { select(hit) } }
                        if (webNote.isNotEmpty()) item { Heading(webNote) }
                        items(webHits, key = { it.key }) { hit -> HitRow(hit) { select(hit) } }
                    }
                }
                Page.PLACE -> Column(pageModifier) {
                    place?.let { PlaceCard(vm, it, onDirections = { open(Page.DIRECTIONS, Detent.FULL) },
                        onReport = { open(Page.REPORT, Detent.FULL) }) }
                }
                Page.DIRECTIONS -> Column(pageModifier) {
                    if (vm.hasOfflineStatewideRoutes()) RoutesScreen(vm, destinationId = place?.key) {
                        focusPoint = null
                        open(Page.BROWSE, Detent.PEEK)
                    } else DirectionsPanel(vm, place?.key, previewing) {
                        previewing = it
                        if (it) scope.launch { sheet.settle(Detent.HALF) }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Page.REPORT -> Column(pageModifier) { ReportScreen(vm, onRequestLocation) }
                Page.STATUS -> Column(pageModifier) {
                    HomeScreen(vm, onGo = { tab ->
                        if (tab == 2) open(Page.REPORT, Detent.FULL) else open(Page.BROWSE, Detent.PEEK)
                    })
                }
                Page.ANALYSIS -> Column(pageModifier) { AnalysisScreen(vm) }
                Page.FIELD_TASKS -> Column(pageModifier) { FieldTasksScreen(vm, onRequestLocation) }
                Page.SYNC -> Column(pageModifier) { SyncScreen(vm, onStartNearby) }
                Page.AUDIT -> Column(pageModifier) { AuditScreen(vm) }
                Page.REGIONS -> Column(pageModifier) {
                    OfflineIndiaCard()
                    MapScreen(vm)
                }
            }
        }
    }
    }
}

private fun crossingHit(graph: RoadGraph, id: String): SearchHit? {
    val edge = graph.edges.values.firstOrNull { it.crossingId == id } ?: return null
    val a = graph.nodes[edge.u] ?: return null
    val b = graph.nodes[edge.v] ?: return null
    val kind = graph.crossings[id]?.kind ?: "crossing"
    return SearchHit("x:$id", id, kind.replaceFirstChar(Char::uppercase), (a.lat + b.lat) / 2, (a.lon + b.lon) / 2, id)
}

@Composable
private fun GlassPill(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp), content: @Composable () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.glass(shape, MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))) {
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

@Composable
private fun GlassCircle(modifier: Modifier, label: String, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    GlassPill(modifier, CircleShape) {
        Box(Modifier.size(48.dp).semantics { contentDescription = label }.pressable(onClick),
            contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(48.dp, 50.dp).semantics { contentDescription = label }.pressable(onClick),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(21.dp)) { glyph(tint) }
    }
}

private val NEARBY = listOf(
    Triple("🏥", "Hospital", 0xFFFF453A), Triple("👮", "Police", 0xFF0A84FF), Triple("⛽", "Petrol", 0xFF32ADE6),
    Triple("🍴", "Restaurant", 0xFFFF9F0A), Triple("🎓", "School", 0xFFAC8E68), Triple("🏧", "ATM", 0xFF5E5CE6),
    Triple("🚌", "Bus", 0xFF30D158),
)

@Composable
private fun NearbyRow(onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NEARBY.forEach { (icon, label, color) ->
            Column(Modifier.pressable { onPick(label) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).background(Color(color), CircleShape), contentAlignment = Alignment.Center) {
                    Text(icon, fontSize = 26.sp)
                }
                Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onFocus: () -> Unit, onCancel: () -> Unit,
                        showCancel: Boolean) {
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(40.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (query.isEmpty()) Text("Search Kavach Maps",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
                )
            }
            if (query.isNotEmpty()) Icon(Icons.Filled.Clear, contentDescription = "Clear search",
                modifier = Modifier.clickable { onQuery("") })
        }
        if (showCancel) TextButton(onClick = onCancel) { Text("Cancel") }
        else Box(Modifier.padding(start = 8.dp).size(40.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = "Field tools" }.clickable(onClick = onFocus),
            contentAlignment = Alignment.Center) {
            Text("K", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp), fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp)
}

@Composable
private fun CardGroup(content: @Composable () -> Unit) {
    // A lighter pane of glass over the sheet.
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
        contentColor = MaterialTheme.colorScheme.onSurface) { Column { content() } }
}

@Composable
private fun ToolRow(page: Page, onClick: () -> Unit) {
    val (icon, color) = when (page) {
        Page.DIRECTIONS -> "🧭" to 0xFF0A84FF
        Page.REPORT -> "📣" to 0xFFFF453A
        Page.STATUS -> "🛡️" to 0xFF30D158
        Page.ANALYSIS -> "📊" to 0xFF5E5CE6
        Page.FIELD_TASKS -> "📋" to 0xFF30A46C
        Page.SYNC -> "🔄" to 0xFF32ADE6
        Page.AUDIT -> "🧾" to 0xFFAC8E68
        else -> "🗺️" to 0xFFFF9F0A
    }
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(Color(color), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 17.sp)
        }
        Text(page.title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 16.sp)
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(hit.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hit.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionTile(icon: String, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val ink = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Column(modifier.pressable(onClick).background(fill, RoundedCornerShape(16.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 18.sp, color = ink)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
private fun PlaceCard(vm: KavachViewModel, hit: SearchHit, onDirections: () -> Unit, onReport: () -> Unit) {
    Text(hit.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val context = LocalContext.current
    val web = hit.key.startsWith("web:")
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!web) ActionTile("➜", "Directions", true, Modifier.weight(1f), onDirections)
        if (hit.crossingId != null) ActionTile("⚠", "Report", false, Modifier.weight(1f), onReport)
        ActionTile("⇪", "Share", web, Modifier.weight(1f)) {
            val text = "${hit.title}\nhttps://www.openstreetmap.org/?mlat=${hit.lat}&mlon=${hit.lon}#map=17/${hit.lat}/${hit.lon}"
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share place"))
        }
    }
    if (web) Text("Directions cover the Medak and Kamareddy pilot area only.",
        Modifier.padding(bottom = 10.dp), fontSize = 13.sp)
    CardGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val id = hit.crossingId
            if (id != null) {
                val status = vm.statuses[id]?.status ?: Status.UNKNOWN
                Text("Status: $status", color = statusColor(status), fontWeight = FontWeight.SemiBold)
                Text("From an OpenStreetMap tag, not an inspection.", fontSize = 13.sp)
                vm.inspection.firstOrNull { it.crossingId == id }?.let {
                    Text("${it.cutOff} settlement(s) lose facility access if closed.", fontSize = 13.sp)
                }
            }
            Text("%.5f, %.5f".format(hit.lat, hit.lon), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
