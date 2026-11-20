package org.kavach.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kavach.KavachApp
import org.kavach.PackState
import org.kavach.core.Signed
import org.kavach.data.EventEntity
import org.kavach.graph.InspectionRow
import org.kavach.graph.Isolated
import org.kavach.graph.OfflineRoutePlace
import org.kavach.graph.OfflineRouteStore
import org.kavach.graph.Policy
import org.kavach.graph.RoadGraph
import org.kavach.graph.RouteResult
import org.kavach.graph.ShortestPath
import org.kavach.graph.StatusView
import org.kavach.location.Fix
import org.kavach.location.Gps
import org.kavach.service.KavachNode
import org.kavach.sync.NearbyRelay
import java.io.File

/** Screen state and actions. Heavy work runs on Dispatchers.IO; screens only read plain state. */
class KavachViewModel(application: Application) : AndroidViewModel(application) {

    private data class RefreshSnapshot(
        val statuses: Map<String, StatusView>,
        val counts: Pair<Int, Int>,
        val events: List<EventEntity>,
        val isolated: List<Isolated>,
        val inspection: List<InspectionRow>,
    )

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
    var highlightedRouteEdges by mutableStateOf<Set<String>>(emptySet())
        private set
    var highlightedRouteCoordinates by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
        private set
    var statewideOriginSearchResults by mutableStateOf<List<OfflineRoutePlace>>(emptyList())
        private set
    var statewideDestinationSearchResults by mutableStateOf<List<OfflineRoutePlace>>(emptyList())
        private set
    var statewideOrigin by mutableStateOf<OfflineRoutePlace?>(null)
        private set
    var statewideDestination by mutableStateOf<OfflineRoutePlace?>(null)
        private set
    var statewideRouteBusy by mutableStateOf(false)
        private set
    private var originSearchJob: Job? = null
    private var destinationSearchJob: Job? = null
    var showLocalDetail by mutableStateOf(true)
    var isolated by mutableStateOf<List<Isolated>>(emptyList())
        private set
    var inspection by mutableStateOf<List<InspectionRow>>(emptyList())
        private set
    var message by mutableStateOf("")
        private set
    var fix by mutableStateOf<Fix?>(null)
        private set
    var selectedCrossing by mutableStateOf<String?>(null)
    var selectedMapArea by mutableStateOf("IN-TG")
    var scenario by mutableStateOf("")
    var hubUrl by mutableStateOf("http://192.168.1.1:8000")
    var hubToken by mutableStateOf("")
    var nearby by mutableStateOf<NearbyRelay?>(null)
        private set

    fun graph(): RoadGraph? = ready?.pack?.graph

    fun features(): List<org.kavach.pack.MapFeature> = ready?.pack?.features ?: emptyList()

    fun indiaAreas(): List<org.kavach.pack.IndiaAdminArea> = ready?.pack?.indiaAreas ?: emptyList()

    fun offlineMapDatabase(): File? = ready?.offlineMapDatabase

    fun hasOfflineStatewideRoutes(): Boolean = ready?.offlineRouteDatabase?.isFile == true

    fun searchStatewidePlaces(query: String, forOrigin: Boolean) {
        val db = ready?.offlineRouteDatabase ?: return
        val selected = if (forOrigin) statewideOrigin else statewideDestination
        if (selected?.name != query) {
            if (forOrigin) statewideOrigin = null else statewideDestination = null
            route = null
            shortest = null
            highlightedRouteEdges = emptySet()
            highlightedRouteCoordinates = emptyList()
        }
        val job = if (forOrigin) originSearchJob else destinationSearchJob
        job?.cancel()
        if (forOrigin) statewideOriginSearchResults = emptyList() else statewideDestinationSearchResults = emptyList()
        val search = viewModelScope.launch {
            delay(180)
            val results = try {
                withContext(Dispatchers.IO) { OfflineRouteStore(db).searchPlaces(query) }
            } catch (e: Exception) {
                message = "Offline place search failed: ${e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
            if (forOrigin) statewideOriginSearchResults = results else statewideDestinationSearchResults = results
        }
        if (forOrigin) originSearchJob = search else destinationSearchJob = search
    }

    fun selectStatewidePlace(place: OfflineRoutePlace, forOrigin: Boolean) {
        if (forOrigin) statewideOrigin = place else statewideDestination = place
        if (forOrigin) statewideOriginSearchResults = emptyList() else statewideDestinationSearchResults = emptyList()
    }

    fun computeStatewideRoute(vehicle: String, policy: String) {
        val db = ready?.offlineRouteDatabase ?: return
        val origin = statewideOrigin
        val destination = statewideDestination
        if (origin == null || destination == null) {
            message = "Choose both an origin and destination."
            return
        }
        val n = node ?: return
        statewideRouteBusy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val trustedStatuses = n.statuses().mapValues { it.value.status }
                    val store = OfflineRouteStore(db)
                    store.route(origin, destination, vehicle, policy, trustedStatuses) to store
                }
                route = result.first
                shortest = null
                highlightedRouteEdges = result.first.edges.toSet()
                highlightedRouteCoordinates = withContext(Dispatchers.IO) { result.second.coordinates(result.first.path) }
                message = ""
            } catch (e: Exception) {
                route = null
                shortest = null
                highlightedRouteEdges = emptySet()
                highlightedRouteCoordinates = emptyList()
                message = "Statewide route could not be computed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                statewideRouteBusy = false
            }
        }
    }

    fun packInfo(): Pair<String, String> = ready?.pack?.let { it.packVersion to it.createdAt } ?: ("none" to "")

    private fun scenarioSet(): Set<String> =
        scenario.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun refresh() {
        val n = node ?: return
        val scen = scenarioSet()
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    RefreshSnapshot(
                        n.statuses(scen), n.counts(), n.allEvents(),
                        n.isolated("light", Policy.STRICT, scen), n.inspection(scen),
                    )
                }
                statuses = snapshot.statuses
                counts = snapshot.counts
                events = snapshot.events.asReversed()
                isolated = snapshot.isolated
                inspection = snapshot.inspection
            } catch (e: Exception) {
                message = "Could not refresh local data: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun computeRoute(origin: String, dest: String, vehicle: String, policy: String) {
        val n = node ?: return
        val scen = scenarioSet()
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    n.route(origin, dest, vehicle, policy, scen) to n.shortest(origin, dest)
                }
                route = results.first
                shortest = results.second
                highlightedRouteEdges = results.first.edges.toSet()
                highlightedRouteCoordinates = emptyList()
                message = ""
            } catch (e: Exception) {
                route = null
                shortest = null
                message = "Route could not be computed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun requestFix(context: Context) {
        viewModelScope.launch {
            message = "Waiting for a GNSS fix…"
            val f = try { Gps.oneFix(context) } catch (_: SecurityException) {
                fix = null
                message = "Precise location permission is required. Tap Get GPS fix and allow it."
                return@launch
            } catch (e: Exception) {
                fix = null
                message = "Could not read GNSS: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            fix = f
            message = if (f == null) "No GNSS fix yet. Check location is enabled, move outdoors and try again."
                else "Fix acquired (±${f.accuracyM.toInt()} m)"
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
            try {
                val wire = Signed.observation(assetId, type, f.lat, f.lon, f.accuracyM, note, n.packVersion)
                val res = withContext(Dispatchers.IO) { n.submit(wire) }
                message = when (res.status) {
                    "accepted" -> "Saved on this phone as a signed claim. It syncs later and is not verified."
                    "duplicate" -> "Already saved."
                    else -> "Rejected: ${res.reason}"
                }
                refresh()
            } catch (e: Exception) {
                message = "Could not save report: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun syncHub() {
        val sync = lanSync ?: return
        val url = hubUrl
        viewModelScope.launch {
            message = "Syncing with hub..."
            message = try {
                withContext(Dispatchers.IO) { sync.sync(url, hubToken).message }
            } catch (e: Exception) {
                "Hub not reachable on this LAN: ${e.message}"
            }
            refresh()
        }
    }

    fun startNearby(context: Context, deviceName: String) {
        val n = node ?: return
        try {
            val relay = nearby ?: NearbyRelay(context, n, deviceName).also { nearby = it }
            relay.start()
        } catch (e: Exception) {
            message = "Nearby could not start: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun stopNearby() {
        nearby?.stop()
    }

    fun showMessage(value: String) {
        message = value
    }
}
