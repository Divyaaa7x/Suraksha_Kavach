package org.kavach.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A GNSS fix with its age. The UI must never show an old fix as live (dossier T-02). */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long) {
    val ageS: Long get() = ((System.currentTimeMillis() - timeMs) / 1000).coerceAtLeast(0)
}

object Gps {
    /**
     * Ask the GNSS receiver for one fix. Works without mobile data. Returns null if no fix arrives
     * within [timeoutMs] (e.g. indoors) rather than guessing a position.
     */
    @SuppressLint("MissingPermission") // the caller checks the location permission first
    suspend fun oneFix(context: Context, timeoutMs: Long = 20_000): Fix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(Fix(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time))
                    }
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
    }
}
