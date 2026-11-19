package org.kavach.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kavach.KavachApp
import org.kavach.PackState
import org.kavach.core.Signed
import org.kavach.data.EventEntity
import org.kavach.graph.InspectionRow
import org.kavach.graph.Isolated
import org.kavach.graph.Policy
import org.kavach.graph.RoadGraph
import org.kavach.graph.RouteResult
import org.kavach.graph.ShortestPath
import org.kavach.graph.StatusView
import org.kavach.location.Fix
import org.kavach.location.Gps
import org.kavach.service.KavachNode
import org.kavach.sync.NearbyRelay

/** Screen state and actions. Heavy work runs on Dispatchers.IO; screens only read plain state. */
class KavachViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KavachApp
    val packState: kotlinx.coroutines.flow.StateFlow<PackState> = app.packState

    private val ready: PackState.Ready? get() = app.packState.value as? PackState.Ready
    val node: KavachNode? get() = ready?.node
    private val lanSync get() = ready?.sync

    var statuses by mutableStateOf<Map<String, StatusView>>(emptyMap())
        private set
    var counts by mutableStateOf(0 to 0)
        private set
    var events by mutableStateOf<List<EventEntity>>(emptyList())
        private set
    var route by mutableStateOf<RouteResult?>(null)
        private set
    var shortest by mutableStateOf<ShortestPath?>(null)
        private set
    var isolated by mutableStateOf<List<Isolated>>(emptyList())
        private set
    var inspection by mutableStateOf<List<InspectionRow>>(emptyList())
        private set
    var message by mutableStateOf("")
        private set
    var fix by mutableStateOf<Fix?>(null)
        private set
    var selectedCrossing by mutableStateOf<String?>(null)
    var scenario by mutableStateOf("")
    var hubUrl by mutableStateOf("http://192.168.1.1:8000")
    var nearby: NearbyRelay? = null
        private set

    fun graph(): RoadGraph? = ready?.pack?.graph

    fun features(): List<org.kavach.pack.MapFeature> = ready?.pack?.features ?: emptyList()

    fun packInfo(): Pair<String, String> = ready?.pack?.let { it.packVersion to it.createdAt } ?: ("none" to "")

    private fun scenarioSet(): Set<String> =
        scenario.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun refresh() {
        val n = node ?: return
        val scen = scenarioSet()
        viewModelScope.launch {
            statuses = withContext(Dispatchers.IO) { n.statuses(scen) }
            counts = withContext(Dispatchers.IO) { n.counts() }
            events = withContext(Dispatchers.IO) { n.allEvents() }.asReversed()
            isolated = withContext(Dispatchers.IO) { n.isolated("light", Policy.STRICT, scen) }
            inspection = withContext(Dispatchers.IO) { n.inspection(scen) }
        }
    }

    fun computeRoute(origin: String, dest: String, vehicle: String, policy: String) {
        val n = node ?: return
        val scen = scenarioSet()
        viewModelScope.launch {
            route = withContext(Dispatchers.IO) { n.route(origin, dest, vehicle, policy, scen) }
            shortest = withContext(Dispatchers.IO) { n.shortest(origin, dest) }
        }
    }

    fun requestFix(context: Context) {
        viewModelScope.launch {
            val f = Gps.oneFix(context)
            fix = f
            message = if (f == null) "No GNSS fix. Move outdoors and try again." else "Fix acquired"
        }
    }

    /** Stores a signed observation locally. Refuses a stale fix (dossier T-02). */
    fun submitObservation(assetId: String, type: String, note: String) {
        val n = node ?: return
        val f = fix
        if (f == null) {
            message = "Get a GPS fix first."
            return
        }
        if (f.ageS > 120) {
            message = "GPS fix is ${f.ageS}s old. Get a fresh fix."
            return
        }
        viewModelScope.launch {
            val wire = Signed.observation(assetId, type, f.lat, f.lon, f.accuracyM, note, n.packVersion)
            val res = withContext(Dispatchers.IO) { n.submit(wire) }
            message = when (res.status) {
                "accepted" -> "Saved on this phone as a signed claim. It syncs later and is not verified."
                "duplicate" -> "Already saved."
                else -> "Rejected: ${res.reason}"
            }
            refresh()
        }
    }

    fun syncHub() {
        val sync = lanSync ?: return
        val url = hubUrl
        viewModelScope.launch {
            message = "Syncing with hub..."
            message = try {
                withContext(Dispatchers.IO) { sync.sync(url).message }
            } catch (e: Exception) {
                "Hub not reachable on this LAN: ${e.message}"
            }
            refresh()
        }
    }

    fun startNearby(context: Context, deviceName: String) {
        val n = node ?: return
        val relay = nearby ?: NearbyRelay(context, n, deviceName).also { nearby = it }
        relay.start()
    }

    fun stopNearby() {
        nearby?.stop()
    }
}
