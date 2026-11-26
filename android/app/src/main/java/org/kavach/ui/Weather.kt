package org.kavach.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kavach.core.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class Weather(val tempC: Int, val code: Int, val isDay: Boolean, val aqi: Int?, val atMs: Long)

internal fun weatherEmoji(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..67 -> "🌧️"
    in 71..77, 85, 86 -> "❄️"
    in 80..82 -> "🌦️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}

/** US AQI bands. */
internal fun aqiColor(aqi: Int): Long = when {
    aqi <= 50 -> 0xFF30D158
    aqi <= 100 -> 0xFFFFD60A
    aqi <= 150 -> 0xFFFF9F0A
    aqi <= 200 -> 0xFFFF453A
    else -> 0xFFBF5AF2
}

private fun getJson(url: String): Map<*, *> {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
    }
    return try {
        Json.parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) as Map<*, *>
    } finally {
        connection.disconnect()
    }
}

/**
 * Current weather and air quality from Open-Meteo (CC BY 4.0). Coordinates are rounded to about 1 km
 * before they leave the phone. Blocking: call on Dispatchers.IO.
 */
private fun fetchWeather(lat: Double, lon: Double): Weather {
    val where = "latitude=%.2f&longitude=%.2f".format(java.util.Locale.US, lat, lon)
    val now = getJson("https://api.open-meteo.com/v1/forecast?$where&current=temperature_2m,weather_code,is_day")["current"] as Map<*, *>
    val aqi = runCatching {
        val air = getJson("https://air-quality-api.open-meteo.com/v1/air-quality?$where&current=us_aqi")["current"] as Map<*, *>
        (air["us_aqi"] as Number).toInt()
    }.getOrNull()
    return Weather((now["temperature_2m"] as Number).toDouble().roundToInt(), (now["weather_code"] as Number).toInt(),
        (now["is_day"] as Number).toInt() == 1, aqi, System.currentTimeMillis())
}

private fun lastKnown(context: Context): Pair<Double, Double>? {
    val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (_: SecurityException) {
        null
    }
}

private const val PREFS = "atlas-weather"

private fun load(context: Context): Weather? {
    val p = context.getSharedPreferences(PREFS, 0)
    if (!p.contains("at")) return null
    return Weather(p.getInt("temp", 0), p.getInt("code", 0), p.getBoolean("day", true),
        p.getInt("aqi", -1).takeIf { it >= 0 }, p.getLong("at", 0))
}

private fun save(context: Context, w: Weather) {
    context.getSharedPreferences(PREFS, 0).edit().putInt("temp", w.tempC).putInt("code", w.code)
        .putBoolean("day", w.isDay).putInt("aqi", w.aqi ?: -1).putLong("at", w.atMs).apply()
}

/** Top-left glass chip: icon, temperature, air quality. Tap for the source and age. */
@Composable
fun WeatherChip(modifier: Modifier, online: Boolean, fix: Pair<Double, Double>?) {
    val context = LocalContext.current
    var weather by remember { mutableStateOf(load(context)) }
    var open by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf(false) }

    LaunchedEffect(online, fix) {
        while (online) {
            val here = fix ?: lastKnown(context)
            mine = here != null
            val (lat, lon) = here ?: (18.05 to 78.26) // Medak, the pilot area, when no location is known
            runCatching { withContext(Dispatchers.IO) { fetchWeather(lat, lon) } }.onSuccess {
                weather = it
                save(context, it)
            }
            delay(if (weather == null) 30_000L else 600_000L)
        }
    }

    val float by rememberInfiniteTransition(label = "weather")
        .animateFloat(-1f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "float")
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.glass(shape, MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .pressable { open = !open }
            .animateContentSize(spring(dampingRatio = 0.8f)),
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
            AnimatedContent(weather, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
                label = "reading") { w ->
                if (w == null) {
                    Text(if (online) "Weather…" else "Offline", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(weatherEmoji(w.code, w.isDay), fontSize = 21.sp,
                                modifier = Modifier.graphicsLayer { translationY = float * 2.dp.toPx() })
                            Text(" ${w.tempC}°", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        }
                        if (w.aqi != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AQI ${w.aqi}", fontSize = 12.sp)
                            Box(Modifier.padding(start = 5.dp).size(8.dp).background(Color(aqiColor(w.aqi)), CircleShape))
                        }
                    }
                }
            }
            val w = weather
            if (w != null) {
                val minutes = (System.currentTimeMillis() - w.atMs) / 60_000
                if (minutes >= 30) Text("Old reading: ${minutes / 60} h ${minutes % 60} min", fontSize = 10.sp,
                    color = Color(0xFFFF9F0A))
                if (open) Text("${if (mine) "Near you" else "Medak (pilot)"} · $minutes min ago · Open-Meteo",
                    fontSize = 10.sp)
            }
        }
    }
}
