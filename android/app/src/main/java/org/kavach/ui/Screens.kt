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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import org.kavach.PackState
import org.kavach.graph.Policy
import org.kavach.graph.RoadGraph
import org.kavach.graph.Status
import org.kavach.sync.NearbyRelay
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val TABS = listOf("Home", "Map", "Report", "Routes", "More")

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
    var moreTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("KAVACH", fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.primary)
                Text("Field support", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("●  OFFLINE", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(100)) },
            label = "primary navigation",
        ) { destination ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                when (destination) {
                    0 -> HomeScreen(vm, onGo = { tab = it })
                    1 -> MapScreen(vm)
                    2 -> ReportScreen(vm, onRequestLocation)
                    3 -> RoutesScreen(vm) {
                        vm.selectedMapArea = "IN-TG"
                        vm.showLocalDetail = true
                        tab = 1
                    }
                    else -> MoreScreen(vm, moreTab, onSelect = { moreTab = it }, onStartNearby = onStartNearby)
                }
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            val icons = listOf(Icons.Filled.Home, Icons.Filled.Place, Icons.Filled.AddCircle,
                Icons.AutoMirrored.Filled.ArrowForward, Icons.Filled.MoreVert)
            TABS.forEachIndexed { i, title ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Icon(icons[i], contentDescription = null) },
                    label = { Text(title) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(tween(180)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun HomeScreen(vm: KavachViewModel, onGo: (Int) -> Unit) {
    val graph = vm.graph() ?: return
    val (packVersion, packCreated) = vm.packInfo()
    Section("Ready for the field") {
        Text("Offline map available", color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold)
        Text("Route graph · ${graph.edges.size} segments · ${graph.crossings.size} crossings")
        Text("Map updated ${ageText(packCreated)} ago")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(onClick = { onGo(1) }) { Text("Open map") }
            OutlinedButton(onClick = { onGo(2) }) { Text("Report") }
        }
    }
    val counts = vm.statuses.values.groupingBy { it.status }.eachCount()
    Section("Crossing status") {
        Text("${counts[Status.UNKNOWN] ?: graph.crossings.size} awaiting field verification",
            color = statusColor(Status.UNKNOWN), fontWeight = FontWeight.SemiBold)
        Text("OSM bridge and ford tags are not safety checks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val other = counts.filterKeys { it != Status.UNKNOWN }
        if (other.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(other.entries.toList()) { (status, count) ->
                    Text("$status · $count", color = statusColor(status),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Section("This device") {
        Text("Field responder · demo key", style = MaterialTheme.typography.bodyMedium)
        Text("${vm.counts.first} reports · ${vm.counts.second} approvals on this phone",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Pack $packVersion", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Decision support only · human review required",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
}

@Composable
private fun MoreScreen(vm: KavachViewModel, selected: Int, onSelect: (Int) -> Unit,
                      onStartNearby: () -> Unit) {
    val labels = listOf("Analysis", "Sync", "Audit")
    Section("Tools") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(labels.indices.toList()) { i ->
                FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(labels[i]) })
            }
        }
    }
    when (selected) {
        0 -> AnalysisScreen(vm)
        1 -> SyncScreen(vm, onStartNearby)
        else -> AuditScreen(vm)
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
        Section("India · ${areas.size} states and union territories") {
            IndiaOverviewMap(areas, vm.selectedMapArea, onSelect = { vm.selectedMapArea = it })
            if (areas.isNotEmpty()) {
                NodePicker("State or UT", areas.map { it.code to it.name },
                    vm.selectedMapArea) { vm.selectedMapArea = it }
            }
            Text(if (selectedArea?.code == "IN-TG") "Telangana offline road map · routes for Medak and Kamareddy"
                else "Reference boundary only · no local road map installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selectedArea?.code == "IN-TG") {
                Button(onClick = { vm.showLocalDetail = true }, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Open Telangana map")
                }
            }
        }
    } else {
        Section("Telangana · offline map") {
            OutlinedButton(onClick = { vm.showLocalDetail = false }) { Text("India") }
            val mapDatabase = vm.offlineMapDatabase()
            val telangana = areas.firstOrNull { it.code == "IN-TG" }
            if (mapDatabase != null && telangana != null) {
                TelanganaOfflineMap(
                    database = mapDatabase,
                    state = telangana,
                    graph = graph,
                    statuses = vm.statuses,
                    selectedCrossing = vm.selectedCrossing,
                    highlightedEdges = vm.highlightedRouteEdges,
                    onSelectCrossing = { vm.selectedCrossing = it },
                )
                Text("Pinch to zoom · drag to explore · OSM map",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Text("Route analysis: Medak and Kamareddy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                RealMap(graph, vm.features(), vm.statuses, vm.selectedCrossing,
                    onSelect = { vm.selectedCrossing = it }, highlightedEdges = vm.highlightedRouteEdges)
            }
            if (vm.highlightedRouteEdges.isNotEmpty()) {
                val matched = vm.highlightedRouteEdges.count { it in graph.edges }
                Text(
                    if (matched == vm.highlightedRouteEdges.size)
                        "Candidate route · $matched segments"
                    else "Map mismatch · $matched of ${vm.highlightedRouteEdges.size} segments",
                    style = MaterialTheme.typography.labelSmall,
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
    if (vm.showLocalDetail) Section("Map key") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(listOf(Status.UNKNOWN, Status.REPORTED_CLOSED, Status.VERIFIED_OPEN, Status.VERIFIED_CLOSED)) {
                Text(it.replace("_", " "), color = statusColor(it),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Blue · water   □ facility   ○ settlement", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Section("New report") {
        Text("Saved as an unverified field claim.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        NodePicker("Crossing", crossingOptions, asset) {
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
            label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
        val f = vm.fix
        Text(
            if (f == null) "Location not added"
            else "Location · ±${f.accuracyM.toInt()} m · ${f.ageS}s ago",
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
