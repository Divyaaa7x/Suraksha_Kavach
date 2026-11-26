package org.kavach.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kavach.core.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Round place badges drawn on the device with the phone's own emoji font. */
fun addPlaceBadges(style: Style, density: Float) {
    val badges = mapOf(
        "kv-health" to ("🏥" to 0xFFE8656A), "kv-school" to ("🎓" to 0xFFB98A66), "kv-park" to ("🌳" to 0xFF55B96A),
        "kv-transit" to ("🚌" to 0xFF4A90F0), "kv-food" to ("🍴" to 0xFFF29A3A), "kv-shop" to ("🛍" to 0xFFE9B93C),
        "kv-fuel" to ("⛽" to 0xFF4A90F0), "kv-worship" to ("🛕" to 0xFF9A8CC8), "kv-civic" to ("🏛" to 0xFF7C8CA8),
        "kv-other" to ("📍" to 0xFF9A8CC8),
    )
    val size = (24 * density).toInt()
    badges.forEach { (name, badge) ->
        val (icon, color) = badge
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val half = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF1B2A33.toInt()
        canvas.drawCircle(half, half, half, paint)
        paint.color = color.toInt()
        canvas.drawCircle(half, half, half - 1.5f * density, paint)
        paint.textSize = size * 0.52f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(icon, half, half - (paint.ascent() + paint.descent()) / 2, paint)
        style.addImage(name, bitmap)
    }
}

data class WebPlace(val name: String, val detail: String, val lat: Double, val lon: Double)

/**
 * India-wide place search through the public Photon geocoder (OpenStreetMap data). The typed text
 * is sent to photon.komoot.io; callers use this only while online. Blocking: call on Dispatchers.IO.
 */
fun searchIndia(query: String, near: Pair<Double, Double>? = null): List<WebPlace> {
    val bias = near?.let { "&lat=%.2f&lon=%.2f".format(java.util.Locale.US, it.first, it.second) }.orEmpty()
    val url = "https://photon.komoot.io/api/?limit=12&lang=en&bbox=68.0,6.0,97.5,37.5$bias&q=" +
        URLEncoder.encode(query, "UTF-8")
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("User-Agent", "kavach-prototype")
    }
    val text = try {
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
    return parsePhoton(text)
}

internal fun parsePhoton(text: String): List<WebPlace> {
    val root = Json.parse(text) as Map<*, *>
    return (root["features"] as List<*>).mapNotNull { item ->
        val feature = item as Map<*, *>
        val props = feature["properties"] as Map<*, *>
        val coords = (feature["geometry"] as Map<*, *>)["coordinates"] as List<*>
        val name = props["name"] as? String ?: return@mapNotNull null
        val detail = listOfNotNull(props["city"] as? String, props["state"] as? String, props["country"] as? String)
            .distinct().joinToString(", ")
        WebPlace(name, detail, (coords[1] as Number).toDouble(), (coords[0] as Number).toDouble())
    }
}

private const val PREFS = "atlas"
private const val SAVED = "indiaSaved"

fun indiaSaved(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean(SAVED, false)

/** Downloads an all-India overview (zoom 0-8: states, cities, highways, water) for use without internet. */
@Composable
fun OfflineIndiaCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(if (indiaSaved(context)) "Saved on this phone" else "") }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("India for offline use", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text("Overview detail: states, cities, highways, rivers. Not street level. Uses roughly 50–100 MB of data.",
            fontSize = 13.sp)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
        if (status.isNotEmpty()) Text(status, fontSize = 13.sp)
        Button(enabled = progress == null, onClick = {
            progress = 0f
            status = "Starting…"
            MapLibre.getInstance(context)
            val definition = OfflineTilePyramidRegionDefinition(
                "asset://atlas/night.json",
                LatLngBounds.Builder().include(LatLng(6.0, 68.0)).include(LatLng(37.5, 97.5)).build(),
                0.0, 8.0, context.resources.displayMetrics.density,
            )
            OfflineManager.getInstance(context).createOfflineRegion(
                definition, "india".toByteArray(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status0: OfflineRegionStatus) {
                                val need = status0.requiredResourceCount.coerceAtLeast(1)
                                progress = (status0.completedResourceCount.toFloat() / need).coerceIn(0f, 1f)
                                status = "%.0f MB downloaded".format(status0.completedResourceSize / 1_000_000.0)
                                if (status0.isComplete) {
                                    context.getSharedPreferences(PREFS, 0).edit().putBoolean(SAVED, true).apply()
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    progress = null
                                    status = "Saved on this phone"
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                status = "Waiting: ${error.message}"
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                status = "Tile limit reached ($limit)"
                                progress = null
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        status = "Could not start: $error"
                        progress = null
                    }
                },
            )
        }, modifier = Modifier.padding(top = 6.dp)) { Text("Download India") }
    }
}
