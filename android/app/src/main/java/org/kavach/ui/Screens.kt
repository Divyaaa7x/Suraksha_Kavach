package org.kavach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kavach.PackState
import org.kavach.graph.Policy
import org.kavach.graph.RoadGraph
import org.kavach.graph.Status
import org.kavach.sync.NearbyRelay
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val TABS = listOf("Home", "Map", "Report", "Routes", "Analysis", "Sync", "Audit")

/** Status colours. Colour is never the only signal: every status also has a text label. */
fun statusColor(status: String): Color = when (status) {
    Status.VERIFIED_OPEN -> Color(0xFF1B7F3B)
    Status.VERIFIED_CLOSED -> Color(0xFFB3261E)
    Status.REPORTED_OPEN, Status.REPORTED_CLOSED, Status.RESTRICTED -> Color(0xFFA15C00)
    Status.DISPUTED -> Color(0xFF8A2BE2)
    Status.STALE -> Color(0xFF5B6470)
    Status.PREDICTED_RISK -> Color(0xFF0B6E99)
    else -> Color(0xFF6B4FBB) // UNKNOWN
}

@Composable
fun AppScreen(vm: KavachViewModel, onRequestLocation: () -> Unit, onStartNearby: () -> Unit) {
    val state by vm.packState.collectAsState()
    when (val s = state) {
        is PackState.Loading -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Verifying the offline map pack…", Modifier.padding(top = 12.dp))
        }
        is PackState.Failed -> Column(Modifier.padding(16.dp)) {
            Text("Pack failed verification", fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
            Text(s.message)
            Text("The app will not show routes from an unverified pack.")
        }
        is PackState.Ready -> ReadyApp(vm, onRequestLocation, onStartNearby)
    }
}

@Composable
private fun ReadyApp(vm: KavachViewModel, onRequestLocation: () -> Unit, onStartNearby: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        Banner()
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(scroll)) {
            when (tab) {
                0 -> HomeScreen(vm)
                1 -> MapScreen(vm)
                2 -> ReportScreen(vm, onRequestLocation)
                3 -> RoutesScreen(vm) {
                    vm.selectedMapArea = "IN-TG"
                    vm.showLocalDetail = true
                    tab = 1
                }
                4 -> AnalysisScreen(vm)
                5 -> SyncScreen(vm, onStartNearby)
                else -> AuditScreen(vm)
            }
        }
    }
}

@Composable
private fun Banner() {
    Box(Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(10.dp)) {
        Text(
            "Decision support only. A human approves every access decision. Map data: OpenStreetMap (ODbL). Works offline.",
            fontSize = 12.sp,
            color = Color(0xFF533F03),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun HomeScreen(vm: KavachViewModel) {
    val graph = vm.graph() ?: return
    val (packVersion, packCreated) = vm.packInfo()
    val crossingCount = graph.crossings.size
    Section("Readiness") {
        Text("Pack: $packVersion")
        Text("Pack created (UTC): $packCreated")
        Text("Graph: ${graph.version}")
        Text("Road network: ${graph.edges.size} segments, $crossingCount bridges/culverts/fords (from OSM tags)")
        Text("Role on this device: field responder (demo key). Approvals happen on the hub.")
        Text("Events on this phone: ${vm.counts.first} reports, ${vm.counts.second} approvals")
    }
    Section("Data age") {
        Text("Pack age: ${ageText(packCreated)}. Map data is from OpenStreetMap; it is not a live road survey.")
    }
    Section("Crossing status") {
        Text("Every crossing starts UNKNOWN. Only a report or an authorised approval changes it.",
            fontSize = 13.sp)
        val counts = vm.statuses.values.groupingBy { it.status }.eachCount()
        counts.forEach { (st, n) -> Text("$st: $n", color = statusColor(st)) }
        if (counts.isEmpty()) Text("Loading statuses…")
    }
}

private fun ageText(createdAt: String): String = try {
    val secs = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond() - OffsetDateTime.parse(createdAt).toEpochSecond()
    when {
        secs < 3600 -> "${secs / 60} min"
        secs < 86400 -> "${secs / 3600} h"
        else -> "${secs / 86400} days"
    }
} catch (e: Exception) {
    "unknown"
}

@Composable
private fun MapScreen(vm: KavachViewModel) {
    val graph = vm.graph() ?: return
    val areas = vm.indiaAreas()
    val selectedArea = areas.firstOrNull { it.code == vm.selectedMapArea }
    if (!vm.showLocalDetail) {
        Section("India offline overview · ${areas.size} states and union territories") {
            IndiaOverviewMap(areas, vm.selectedMapArea) { vm.selectedMapArea = it }
            if (areas.isNotEmpty()) {
                NodePicker("Select a state or union territory", areas.map { it.code to it.name },
                    vm.selectedMapArea) { vm.selectedMapArea = it }
            }
            Text(
                if (selectedArea?.code == "IN-TG")
                    "Detailed OSM roads are installed for Medak / Kamareddy, Telangana."
                else "${selectedArea?.name ?: "This area"} has overview boundaries only; no detailed road pack is installed.",
                fontSize = 13.sp,
            )
            if (selectedArea?.code == "IN-TG") {
                Button(onClick = { vm.showLocalDetail = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Open Telangana road detail")
                }
            }
        }
    } else {
        Section("Detailed offline road map · Medak / Kamareddy, Telangana") {
            OutlinedButton(onClick = { vm.showLocalDetail = false }) { Text("Back to India overview") }
            RealMap(
                graph = graph,
                features = vm.features(),
                statuses = vm.statuses,
                selected = vm.selectedCrossing,
                onSelect = { vm.selectedCrossing = it },
                highlightedEdges = vm.highlightedRouteEdges,
            )
            Text("Pinch to zoom, drag to pan, tap a thick line (bridge, culvert or ford) to inspect it.",
                fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (vm.highlightedRouteEdges.isNotEmpty()) {
                val matched = vm.highlightedRouteEdges.count { it in graph.edges }
                Text(
                    if (matched == vm.highlightedRouteEdges.size)
                        "Teal line with a light outline: the most recently computed candidate route (${matched} segments)."
                    else "Route map mismatch: $matched of ${vm.highlightedRouteEdges.size} route segments match this map. Recompute before using this view.",
                    fontSize = 12.sp,
                    color = if (matched == vm.highlightedRouteEdges.size) Color(0xFF00695C) else Color(0xFFB3261E),
                )
            }
        }
    }
    val sel = vm.selectedCrossing
    if (vm.showLocalDetail && sel != null) {
        val st = vm.statuses[sel]
        val dep = vm.inspection.firstOrNull { it.crossingId == sel }
        Section("Crossing $sel") {
            Text("Kind: ${graph.crossings[sel]?.kind ?: "unknown"} (from the OpenStreetMap tag, not an inspection)")
            Text("Status: ${st?.status ?: "UNKNOWN"}", color = statusColor(st?.status ?: Status.UNKNOWN),
                fontWeight = FontWeight.SemiBold)
            if (st?.conflictWithReports == true) Text("Conflicting reports exist. Shown, not auto-resolved.")
            Text(if (dep != null) "${dep.cutOff} settlement(s) lose facility access if this is closed (structural)."
                 else "No settlement depends on this crossing alone.", fontSize = 13.sp)
        }
    }
    Section("Legend") {
        listOf(Status.UNKNOWN, Status.REPORTED_OPEN, Status.REPORTED_CLOSED, Status.VERIFIED_OPEN,
            Status.VERIFIED_CLOSED, Status.DISPUTED, Status.STALE, Status.PREDICTED_RISK).forEach {
            Text(it, color = statusColor(it))
        }
        Text("Blue lines: rivers, streams and canals. Squares: hospitals and clinics. Rings: settlements.",
            fontSize = 12.sp)
    }
}

@Composable
private fun ReportScreen(vm: KavachViewModel, onRequestLocation: () -> Unit) {
    val graph = vm.graph() ?: return
    val crossings = remember(graph) { graph.crossings.keys.sorted() }
    val crossingOptions = remember(graph) {
        crossings.map { id -> id to "$id · ${graph.crossings[id]?.kind ?: "crossing"}" }
    }
    var type by remember { mutableStateOf("CLOSED") }
    var note by remember { mutableStateOf("") }
    val asset = vm.selectedCrossing ?: crossings.firstOrNull() ?: ""
    Section("New observation (saved as a claim, not verified)") {
        NodePicker("Crossing (tap one on the Map tab, or choose here)", crossingOptions, asset) {
            vm.selectedCrossing = it
        }
        Text("Observed", Modifier.padding(top = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("WATER", "BLOCKAGE", "CLOSED", "OPEN", "UNKNOWN")) { t ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) })
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(200) },
            label = { Text("Short note (template text)") }, modifier = Modifier.fillMaxWidth(),
        )
        val f = vm.fix
        Text(
            if (f == null) "GPS: no fix yet"
            else "GPS: ${"%.5f".format(f.lat)}, ${"%.5f".format(f.lon)}, ±${f.accuracyM.toInt()} m, fix ${f.ageS}s old",
            Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = onRequestLocation) { Text("Get GPS fix") }
            Button(onClick = { vm.submitObservation(asset, type, note) }, enabled = asset.isNotBlank()) {
                Text("Save report")
            }
        }
        if (vm.message.isNotEmpty()) Text(vm.message, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NodePicker(label: String, nodes: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, Modifier.padding(top = 6.dp))
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(nodes.firstOrNull { it.first == selected }?.second ?: selected)
    }
    if (open) {
        LazyColumn(Modifier.fillMaxWidth().height(200.dp).border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(8.dp))) {
            items(nodes) { (id, name) ->
                Text(name, Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(10.dp))
            }
        }
    }
}

@Composable
private fun RoutesScreen(vm: KavachViewModel, onShowMap: () -> Unit) {
    val graph = vm.graph() ?: return
    val places = remember(graph) {
        graph.nodes.values.filter { it.kind != "junction" }
            .map { it.id to (it.name.ifEmpty { it.id }) }
            .sortedBy { it.second }
    }
    if (places.isEmpty()) {
        Section("Route planning unavailable") { Text("This pack has no routable settlements or facilities.") }
        return
    }
    var origin by remember(graph) { mutableStateOf(places.firstOrNull { it.second.startsWith("Kamareddy") }?.first ?: places.first().first) }
    var dest by remember(graph) {
        mutableStateOf(places.firstOrNull { it.second.contains("Hospital") }?.first ?: places.first().first)
    }
    var vehicle by remember { mutableStateOf("light") }
    var policy by remember { mutableStateOf(Policy.STRICT) }
    Section("Compare routes") {
        NodePicker("From", places, origin) { origin = it }
        NodePicker("To (hospital or settlement)", places, dest) { dest = it }
        Text("Vehicle", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("light", "medium", "heavy").forEach { v ->
                FilterChip(selected = vehicle == v, onClick = { vehicle = v }, label = { Text(v) })
            }
        }
        Text("Policy", Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = policy == Policy.STRICT, onClick = { policy = Policy.STRICT }, label = { Text("strict") })
            FilterChip(selected = policy == Policy.UNVERIFIED_WARNING, onClick = { policy = Policy.UNVERIFIED_WARNING },
                label = { Text("unverified + warning") })
        }
        OutlinedTextField(
            value = vm.scenario, onValueChange = { vm.scenario = it },
            label = { Text("Scenario overlay: crossings at risk (demo, e.g. OSM-W123,OSM-W456)") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Button(onClick = { vm.computeRoute(origin, dest, vehicle, policy) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Compute route")
        }
        if (vm.message.startsWith("Route could not")) Text(vm.message, color = Color(0xFFB3261E))
    }
    vm.route?.let { r ->
        Section("Result: ${r.result}") {
            if (r.path.isNotEmpty()) Text("Road nodes on route: ${r.path.size}; segments: ${r.edges.size}")
            if (r.path.isNotEmpty()) OutlinedButton(onClick = onShowMap) { Text("Show route on map") }
            r.travelTimeS?.let { Text("Estimated travel time: ${it.toInt()} s at class speeds (not measured)") }
            Text(r.caveat, fontSize = 13.sp)
            Text("Graph ${r.graphVersion} · policy ${r.policy}", fontSize = 12.sp)
            if (r.excluded.isNotEmpty()) {
                Text("Excluded (${r.excluded.size} segments):", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                r.excluded.take(15).forEach { Text("• ${it.crossingId}: ${it.reason}", fontSize = 13.sp) }
            }
            if (r.uncertain.isNotEmpty()) {
                Text("Uncertain (included with warning):", fontWeight = FontWeight.SemiBold)
                r.uncertain.take(15).forEach { Text("• ${it.crossingId}: ${it.reason}", fontSize = 13.sp) }
            }
        }
    }
    vm.shortest?.let { s ->
        Section("Baseline: plain shortest path (ignores crossing status)") {
            if (s.path.isEmpty()) Text("No path")
            else Text("${s.path.size} road nodes · ${s.travelTimeS?.toInt()} s. Compare with the constrained route above.")
        }
    }
}

@Composable
private fun AnalysisScreen(vm: KavachViewModel) {
    Section("Settlements with no verified path to a facility (strict)") {
        if (vm.isolated.isEmpty()) Text("None under the current statuses.")
        vm.isolated.take(60).forEach { s ->
            Text("• ${s.name} (population estimate ${s.population ?: "unknown"})")
        }
        if (vm.isolated.size > 60) Text("… and ${vm.isolated.size - 60} more")
    }
    Section("Inspect first (ranked by what depends on each crossing)") {
        if (vm.inspection.isEmpty()) Text("No unverified crossings.")
        vm.inspection.take(60).forEach { r ->
            Text("${r.crossingId}: ${r.status}, ${r.cutOff} settlement(s) cut off if closed")
        }
    }
    Text("Scores are structural, not proof of flooding. Verify in the field before acting.",
        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SyncScreen(vm: KavachViewModel, onStartNearby: () -> Unit) {
    Section("Local hub (Wi-Fi hotspot / router LAN)") {
        OutlinedTextField(
            value = vm.hubUrl, onValueChange = { vm.hubUrl = it },
            label = { Text("Hub address (private LAN only)") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.syncHub() }, modifier = Modifier.padding(top = 8.dp)) { Text("Sync with hub now") }
        if (vm.message.isNotEmpty()) Text(vm.message, Modifier.padding(top = 6.dp), fontSize = 13.sp)
    }
    Section("Nearby phones (no internet, needs Play services)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartNearby) { Text("Start") }
            Button(onClick = { vm.stopNearby() }) { Text("Stop") }
        }
        val relay = vm.nearby
        if (relay != null) NearbyPanel(relay)
    }
}

@Composable
private fun NearbyPanel(relay: NearbyRelay) {
    val peers by relay.peers.collectAsState()
    val log by relay.log.collectAsState()
    Text(log, Modifier.padding(top = 8.dp), fontSize = 13.sp)
    peers.forEach { p ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("${p.name}${if (p.connected) " · connected" else ""}", fontWeight = FontWeight.SemiBold)
                p.code?.let { Text("Pairing code: $it. Confirm it matches the other phone before accepting.") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!p.connected && p.code != null) {
                        Button(onClick = { relay.confirmPeer(p.endpointId) }) { Text("Codes match") }
                        Button(onClick = { relay.rejectPeer(p.endpointId) }) { Text("Reject") }
                    } else if (!p.connected) {
                        Button(onClick = { relay.requestConnection(p.endpointId) }) { Text("Connect") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditScreen(vm: KavachViewModel) {
    Section("Event log (append-only; newest first)") {
        if (vm.events.isEmpty()) Text("No events yet.")
        vm.events.take(200).forEach { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .border(1.dp, Color(0xFFC9CED6), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("${e.kind} · ${e.assetId}", fontWeight = FontWeight.SemiBold)
                Text("received ${e.receivedAt}", fontSize = 12.sp)
                Text("signed by ${e.keyId}", fontSize = 12.sp)
                Text(if (e.synced) "synced" else "pending sync", fontSize = 12.sp,
                    color = if (e.synced) Color(0xFF1B7F3B) else Color(0xFFA15C00))
            }
        }
    }
}
