package com.aurora.music.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

data class LocationFix(val latitude: Double, val longitude: Double, val accuracyM: Float, val at: Long)

// home and work stay out of settings and every backup; only this device ever holds them
class PlacesStore(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "places.json")
    private val gson = Gson()
    private val state = MutableStateFlow(load())
    val places: StateFlow<Places> = state.asStateFlow()

    @Volatile private var zone: String? = null
    @Volatile private var fixedAt = Long.MIN_VALUE / 2
    @Volatile private var pending = false
    @Volatile private var lastFix: LocationFix? = null

    fun update(transform: (Places) -> Places) {
        val next = transform(state.value)
        state.value = next
        runCatching { persistBackupFileAtomically(file, gson.toJson(next).toByteArray(Charsets.UTF_8)) }
        zone = null
        fixedAt = Long.MIN_VALUE / 2
    }

    fun clear() {
        state.value = Places()
        file.delete()
        zone = null
    }

    fun permitted(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun permittedInBackground(): Boolean = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun currentZone(): String? = sample(zone = true, point = false).first

    // low-power fixes only while something plays: every ten minutes for labels, every two for the location history map
    fun sample(zone: Boolean, point: Boolean): Pair<String?, LocationFix?> {
        val now = SystemClock.elapsedRealtime()
        val wanted = (zone && state.value.configured) || point
        val refresh = if (point) POINT_REFRESH_MS else REFRESH_MS
        if (wanted && !pending && now - fixedAt > refresh && permitted()) {
            pending = true
            fix { location ->
                pending = false
                fixedAt = SystemClock.elapsedRealtime()
                lastFix = location?.let { LocationFix(it.latitude, it.longitude, it.accuracy, fixedAt) }
                this.zone = location?.let { PlaceZones.zoneFor(it.latitude, it.longitude, it.accuracy, state.value) }
            }
        }
        return (if (zone) this.zone?.takeIf { now - fixedAt <= STALE_MS } else null) to
            (if (point) lastFix?.takeIf { now - it.at <= POINT_STALE_MS } else null)
    }

    suspend fun currentLocation(): Location? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { continuation -> fix { if (continuation.isActive) continuation.resume(it) } }
    }

    private fun fix(done: (Location?) -> Unit) {
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val provider = providers.firstOrNull { runCatching { manager?.isProviderEnabled(it) == true }.getOrDefault(false) }
        if (manager == null || provider == null || !permitted()) return done(null)
        try {
            if (Build.VERSION.SDK_INT >= 30) manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { done(it) }
            else done(manager.getLastKnownLocation(provider))
        } catch (_: SecurityException) {
            done(null)
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }

    private fun load(): Places = runCatching {
        if (file.exists()) gson.fromJson(file.readText(), Places::class.java) ?: Places() else Places()
    }.getOrDefault(Places())

    private companion object {
        const val REFRESH_MS = 10 * 60_000L
        const val STALE_MS = 30 * 60_000L
        const val POINT_REFRESH_MS = 2 * 60_000L
        const val POINT_STALE_MS = 5 * 60_000L
    }
}
