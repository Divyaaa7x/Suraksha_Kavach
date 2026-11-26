package org.kavach.ui

import android.content.Context
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** True while the phone has validated internet; the online basemap is used only then. */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var online by remember {
        mutableStateOf(manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                online = false
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return online
}

data class MapCrossing(val id: String, val lat: Double, val lon: Double, val color: String)

private fun point(lat: Double, lon: Double, props: String = "") =
    """{"type":"Feature","properties":{$props},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

private fun collection(features: List<String>) =
    """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

/** Route pieces, each a list of (lat, lon), as GeoJSON (which orders coordinates lon, lat). */
internal fun linesGeoJson(lines: List<List<Pair<Double, Double>>>): String = collection(
    lines.filter { it.size > 1 }.map { line ->
        val coords = line.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    },
)

/**
 * Online vector map (MapLibre + OpenFreeMap): India and all of Asia with water, parks, buildings and
 * names, native pan, zoom, rotate, tilt and fling, plus 3D buildings and hill shading.
 */
@Composable
fun AtlasMap(
    dark: Boolean,
    crossings: List<MapCrossing>,
    route: List<List<Pair<Double, Double>>>,
    focus: Pair<Double, Double>?,
    pin: Pair<Double, Double>?,
    user: Pair<Double, Double>?,
    tilted: Boolean,
    resetSignal: Int,
    bottomPaddingPx: Int,
    onBearing: (Float) -> Unit,
    onSelectCrossing: (String) -> Unit,
    previewing: Boolean,
    onPreviewDone: () -> Unit,
    onBackdrop: (Backdrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val bearing by rememberUpdatedState(onBearing)
    val select by rememberUpdatedState(onSelectCrossing)
    val previewDone by rememberUpdatedState(onPreviewDone)
    val backdrop by rememberUpdatedState(onBackdrop)
    var settled by remember { mutableStateOf(0) } // bumps whenever the picture behind the glass changed

    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        view.getMapAsync { m ->
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // the screen draws attribution above the sheet
            m.cameraPosition = CameraPosition.Builder().target(LatLng(23.0, 80.5)).zoom(3.7).build()
            m.addOnCameraMoveListener { bearing(m.cameraPosition.bearing.toFloat()) }
            m.addOnMapClickListener { at ->
                val p = m.projection.toScreenLocation(at)
                val hit = m.queryRenderedFeatures(RectF(p.x - 36, p.y - 36, p.x + 36, p.y + 36), "kv-crossings")
                    .firstOrNull()?.getStringProperty("id")
                if (hit != null) select(hit)
                hit != null
            }
            m.addOnCameraIdleListener { settled++ }
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri("asset://atlas/${if (dark) "night" else "day"}.json")) { s ->
            listOf("kv-route", "kv-crossings", "kv-pin", "kv-user")
                .forEach { s.addSource(GeoJsonSource(it, collection(emptyList()))) }
            fun line(id: String, color: String, width: Float) = LineLayer(id, "kv-route").withProperties(
                PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            s.addLayer(line("kv-route-casing", "#FFFFFF", 9f))
            s.addLayer(line("kv-route-line", "#0A8F76", 6f))
            val markers = CircleLayer("kv-crossings", "kv-crossings").withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(5f), PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f),
            )
            markers.minZoom = 10f
            s.addLayer(markers)
            fun dot(id: String, color: String, radius: Float) = CircleLayer(id, id).withProperties(
                PropertyFactory.circleColor(color), PropertyFactory.circleRadius(radius),
                PropertyFactory.circleStrokeColor("#D8D8DC"), PropertyFactory.circleStrokeWidth(3f),
            )
            s.addLayer(dot("kv-pin", "#E0453A", 11f))
            s.addLayer(dot("kv-user", "#3D8BFF", 8f))
            addPlaceBadges(s, context.resources.displayMetrics.density)
            style = s
        }
    }

    // Snapshot once the map has been still for a moment, and again later for tiles that arrive late.
    LaunchedEffect(settled, style, route, pin, user) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        for (wait in longArrayOf(220, 1800, 5000)) {
            delay(wait)
            m.snapshot { backdrop(frost(it)) }
        }
    }

    fun set(id: String, json: String) = style?.getSourceAs<GeoJsonSource>(id)?.setGeoJson(json)
    LaunchedEffect(style, crossings) {
        set("kv-crossings", collection(crossings.map { point(it.lat, it.lon, """"id":"${it.id}","color":"${it.color}"""") }))
    }
    LaunchedEffect(style, pin) { set("kv-pin", collection(listOfNotNull(pin?.let { point(it.first, it.second) }))) }
    LaunchedEffect(style, user) { set("kv-user", collection(listOfNotNull(user?.let { point(it.first, it.second) }))) }

    // Camera moves keep the target in the part of the map the sheet does not cover.
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val target = focus ?: return@LaunchedEffect
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(target.first, target.second))
                    .zoom(maxOf(m.cameraPosition.zoom, 14.0))
                    .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble()).build(),
            ),
            1100,
        )
    }
    LaunchedEffect(map, style, route) {
        val m = map ?: return@LaunchedEffect
        val points = route.flatten()
        if (points.size < 2) set("kv-route", linesGeoJson(emptyList()))
        if (points.size > 1) {
            val box = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(box, 90, 220, 90, bottomPaddingPx + 60), 900)
            // The line grows from the start to the destination.
            for (frame in 1..30) {
                set("kv-route", linesGeoJson(listOf(points.take(maxOf(2, points.size * frame / 30)))))
                delay(26)
            }
        }
    }
    // Route preview: the camera travels along the route, tilted and facing the direction of travel.
    LaunchedEffect(map, previewing) {
        val m = map ?: return@LaunchedEffect
        val points = route.flatten()
        if (!previewing || points.size < 2) return@LaunchedEffect
        val step = maxOf(1, points.size / 40)
        for (i in points.indices step step) {
            val here = points[i]
            val next = points[minOf(i + step, points.lastIndex)]
            val heading = Math.toDegrees(
                atan2((next.second - here.second) * cos(Math.toRadians(here.first)), next.first - here.first))
            val camera = CameraPosition.Builder().target(LatLng(here.first, here.second)).zoom(15.2).tilt(55.0)
                .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble())
            if (next != here) camera.bearing(heading)
            m.animateCamera(CameraUpdateFactory.newCameraPosition(camera.build()), 700)
            delay(690)
        }
        previewDone()
    }
    LaunchedEffect(map, tilted) {
        val m = map ?: return@LaunchedEffect
        val current = m.cameraPosition
        val zoom = if (tilted) maxOf(current.zoom, 15.5) else current.zoom // buildings extrude from zoom 15
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(current).tilt(if (tilted) 58.0 else 0.0).zoom(zoom).build(),
            ),
            800,
        )
    }
    LaunchedEffect(map, resetSignal) {
        val m = map ?: return@LaunchedEffect
        if (resetSignal == 0) return@LaunchedEffect
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()),
            600,
        )
    }

    AndroidView(factory = { view }, modifier = modifier)
}
