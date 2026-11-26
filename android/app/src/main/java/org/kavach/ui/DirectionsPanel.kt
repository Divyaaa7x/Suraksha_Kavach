package org.kavach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kavach.graph.Policy
import org.kavach.graph.haversineM
import kotlin.math.roundToInt

private val VEHICLES = listOf(Triple("light", "🚗", "Car"), Triple("medium", "🚐", "Van"), Triple("heavy", "🚚", "Truck"))

/** Directions for the pilot road graph: pick ends and vehicle, the route computes itself. */
@Composable
fun DirectionsPanel(vm: KavachViewModel, destinationId: String?, previewing: Boolean, onPreview: (Boolean) -> Unit) {
    val graph = vm.graph() ?: return
    val places = remember(graph) {
        graph.nodes.values.filter { it.kind != "junction" }.map { it.id to it.name.ifEmpty { it.id } }.sortedBy { it.second }
    }
    if (places.isEmpty()) {
        Text("This pack has no places to route between.")
        return
    }
    var origin by remember(graph) {
        mutableStateOf(places.firstOrNull { it.second.startsWith("Kamareddy") }?.first ?: places.first().first)
    }
    var dest by remember(graph, destinationId) {
        mutableStateOf(places.firstOrNull { it.first == destinationId }?.first
            ?: places.firstOrNull { it.second.contains("Hospital") }?.first ?: places.first().first)
    }
    var vehicle by remember { mutableStateOf("light") }
    var allowUnverified by remember { mutableStateOf(false) }
    var showScenario by remember { mutableStateOf(false) }

    LaunchedEffect(origin, dest, vehicle, allowUnverified, vm.scenario) {
        onPreview(false)
        vm.computeRoute(origin, dest, vehicle, if (allowUnverified) Policy.UNVERIFIED_WARNING else Policy.STRICT)
    }

    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            NodePicker("From", places, origin) { origin = it }
            NodePicker("To", places, dest) { dest = it }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(3.dp),
    ) {
        VEHICLES.forEach { (id, icon, label) ->
            val on = vehicle == id
            Text(
                "$icon  $label",
                Modifier.weight(1f)
                    .background(if (on) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(50))
                    .clickable { vehicle = id }.padding(vertical = 9.dp),
                textAlign = TextAlign.Center, fontSize = 14.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }

    val route = vm.route?.takeIf { it.graphVersion != "osm-statewide" }
    when {
        route == null -> Text(if (vm.message.startsWith("Route could not")) vm.message else "Finding a route…",
            Modifier.padding(8.dp))
        route.path.isEmpty() -> Card {
            Column(Modifier.padding(16.dp)) {
                Text("No verified route", color = Color(0xFFFF6B61), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Ask for verification. Do not navigate on an unchecked road.", fontSize = 13.sp)
            }
        }
        else -> Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val km = remember(route) {
                        route.path.zipWithNext { a, b ->
                            val from = graph.nodes[a]
                            val to = graph.nodes[b]
                            if (from == null || to == null) 0.0 else haversineM(from, to)
                        }.sum() / 1000.0
                    }
                    val minutes = ((route.travelTimeS ?: 0.0) / 60.0).roundToInt()
                    Text(if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("%.1f km · class speeds, not live traffic".format(km), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val skipped = route.excluded.mapNotNull { it.crossingId }.distinct().size
                    if (skipped > 0) Text("$skipped unverified crossings in the area are not used", fontSize = 13.sp,
                        color = Color(0xFFE8A33D))
                }
                Button(
                    onClick = { onPreview(!previewing) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2FBF4F), contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(if (previewing) "Stop" else "Preview", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
    route?.let { Text(it.caveat, Modifier.padding(top = 8.dp), fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
    vm.shortest?.travelTimeS?.let {
        Text("Shortest path ignoring crossing status: ${(it / 60).roundToInt()} min (comparison only)",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Allow unverified roads (with warning)", fontSize = 15.sp)
        Switch(checked = allowUnverified, onCheckedChange = { allowUnverified = it })
    }
    TextButton(onClick = { showScenario = !showScenario }) { Text("Flood scenario (demo)") }
    if (showScenario) OutlinedTextField(
        value = vm.scenario, onValueChange = { vm.scenario = it },
        label = { Text("Crossings at risk, e.g. OSM-W123,OSM-W456") }, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content)
}
