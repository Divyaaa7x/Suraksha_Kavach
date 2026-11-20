package org.kavach.sync

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kavach.core.Json
import org.kavach.service.KavachNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Phone-to-phone exchange over Nearby Connections (dossier sections 13 and 38).
 *
 * Pairing: both phones show the same 4-digit code. A person confirms the codes match before the
 * connection is accepted, so a wrong peer cannot silently join (T-09). The exchange is a one-shot
 * bundle of signed events; each side validates every event before storing it.
 *
 * Needs Google Play services on both phones. Its range is radio range only: nothing here guarantees
 * multi-hop delivery. This code has NOT been tested on two physical devices yet (see docs/test-report.md).
 */
class NearbyRelay(
    context: Context,
    private val node: KavachNode,
    private val deviceName: String,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Peer(val endpointId: String, val name: String, val code: String?, val connected: Boolean)

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _log = MutableStateFlow("idle")
    val log: StateFlow<String> = _log.asStateFlow()

    private val pendingAccept = HashMap<String, String>() // endpointId -> code shown for confirmation
    private val sentByEndpoint = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var started = false

    private fun setPeer(p: Peer) {
        _peers.value = _peers.value.filter { it.endpointId != p.endpointId } + p
    }

    private fun removePeer(id: String) {
        _peers.value = _peers.value.filter { it.endpointId != id }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingAccept[endpointId] = info.authenticationDigits
            setPeer(Peer(endpointId, info.endpointName, info.authenticationDigits, connected = false))
            _log.value = "Confirm code ${info.authenticationDigits} matches the other phone"
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = _peers.value.firstOrNull { it.endpointId == endpointId }?.name ?: endpointId
                setPeer(Peer(endpointId, name, pendingAccept[endpointId], connected = true))
                _log.value = "Connected to $name; sending unsynced events"
                scope.launch { sendPending(endpointId) }
            } else {
                _log.value = "Connection not established (${result.status.statusCode})"
                sentByEndpoint.remove(endpointId)
                removePeer(endpointId)
            }
            pendingAccept.remove(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            sentByEndpoint.remove(endpointId)
            removePeer(endpointId)
            _log.value = "Peer disconnected"
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.size > NearbyPayloads.MAX_BYTES) {
                _log.value = "Nearby transfer rejected: payload exceeds size limit"
                return
            }
            scope.launch {
                try {
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.startsWith(ACK_PREFIX)) {
                        // Peer confirms it stored these events, so they are no longer pending here.
                        val sent = sentByEndpoint[endpointId] ?: ConcurrentHashMap.newKeySet<String>()
                        val ids = NearbyPayloads.acknowledgedEvents(bytes, ACK_PREFIX, sent)
                        node.markSynced(ids)
                        sent.removeAll(ids.toSet())
                        _log.value = "Peer stored ${ids.size} events"
                        return@launch
                    }
                    val list = NearbyPayloads.decodeEvents(bytes)
                    val storedIds = ArrayList<String>()
                    for (item in list) {
                        val res = node.submit(item, receivedAt = item["received_at"] as? String)
                        if (res.status == "accepted" || res.status == "duplicate") storedIds.add(res.eventId!!)
                    }
                    // Only after the local write succeeds do we acknowledge, so the sender keeps anything unconfirmed.
                    val ack = ACK_PREFIX + Json.canonical(storedIds)
                    client.sendPayload(endpointId, Payload.fromBytes(ack.toByteArray(Charsets.UTF_8)))
                    _log.value = "Received ${list.size} events from peer, stored ${storedIds.size}"
                } catch (e: Exception) {
                    _log.value = "Invalid nearby transfer: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            setPeer(Peer(endpointId, info.endpointName, null, connected = false))
        }

        override fun onEndpointLost(endpointId: String) {
            removePeer(endpointId)
        }
    }

    fun start() {
        if (started) return
        started = true
        _log.value = "Starting nearby discovery…"
        client.startAdvertising(
            deviceName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener {
            if (started) _log.value = "Nearby advertising failed: ${it.message}"
        }
        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener {
            if (started) _log.value = "Nearby discovery failed: ${it.message}"
        }
    }

    fun requestConnection(endpointId: String) {
        client.requestConnection(deviceName, endpointId, lifecycle)
            .addOnFailureListener { _log.value = "request failed: ${it.message}" }
    }

    /** Called after the person confirms the displayed code matches the other phone. */
    fun confirmPeer(endpointId: String) {
        client.acceptConnection(endpointId, payloads)
    }

    fun rejectPeer(endpointId: String) {
        client.rejectConnection(endpointId)
        sentByEndpoint.remove(endpointId)
        removePeer(endpointId)
    }

    private suspend fun sendPending(endpointId: String) {
        val wire = node.pendingWire()
        if (wire.isEmpty()) {
            _log.value = "nothing to send"
            return
        }
        // Not marked synced here: the peer's ACK does that (see onPayloadReceived).
        val batches = NearbyPayloads.eventBatches(wire)
        val sent = sentByEndpoint.computeIfAbsent(endpointId) { ConcurrentHashMap.newKeySet() }
        _log.value = "Sending ${wire.size} events in ${batches.size} Nearby batch(es)"
        batches.forEachIndexed { index, bytes ->
            val batchIds = NearbyPayloads.decodeEvents(bytes).map {
                it["event_id"] as? String ?: throw IllegalArgumentException("Nearby event is missing event_id")
            }
            sent.addAll(batchIds)
            try {
                Tasks.await(client.sendPayload(endpointId, Payload.fromBytes(bytes)), 15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                sent.removeAll(batchIds.toSet())
                _log.value = "Nearby batch ${index + 1} failed: ${e.message}"
            }
        }
    }

    fun stop() {
        started = false
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        sentByEndpoint.clear()
        _peers.value = emptyList()
        _log.value = "stopped"
    }

    companion object {
        const val SERVICE_ID = "org.kavach.relay"
        private const val ACK_PREFIX = "ACK:"
    }
}
