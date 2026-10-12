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
    @Volatile private var pointInterval = POINT_REFRESH_MS

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
    // a fix another app already got is reused and the map interval stretches while the phone stays put
    fun sample(zone: Boolean, point: Boolean): Pair<String?, LocationFix?> {
        val now = SystemClock.elapsedRealtime()
        val wanted = (zone && state.value.configured) || point
        val refresh = if (point) pointInterval else REFRESH_MS
        if (wanted && !pending && now - fixedAt > refresh && permitted()) {
            pending = true
            fix(precise = false, maxAgeMs = if (point) POINT_REFRESH_MS else REFRESH_MS) { location ->
                pending = false
                fixedAt = SystemClock.elapsedRealtime()
                val previous = lastFix
                lastFix = location?.let { LocationFix(it.latitude, it.longitude, it.accuracy, fixedAt) }
                if (point) pointInterval = nextPointInterval(previous, lastFix)
                this.zone = location?.let { PlaceZones.zoneFor(it.latitude, it.longitude, it.accuracy, state.value) }
            }
        }
        return (if (zone) this.zone?.takeIf { now - fixedAt <= STALE_MS } else null) to
            (if (point) lastFix?.takeIf { now - it.at <= pointInterval + POINT_STALE_SLACK_MS } else null)
    }

    suspend fun currentLocation(): Location? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { continuation -> fix(precise = true, maxAgeMs = 0L) { if (continuation.isActive) continuation.resume(it) } }
    }

    private fun nextPointInterval(previous: LocationFix?, current: LocationFix?): Long {
        if (previous == null || current == null) return POINT_REFRESH_MS
        val moved = FloatArray(1)
        Location.distanceBetween(previous.latitude, previous.longitude, current.latitude, current.longitude, moved)
        val still = moved[0] <= maxOf(STILL_RADIUS_M, previous.accuracyM, current.accuracyM)
        return if (still) (pointInterval * 2).coerceAtMost(POINT_MAX_REFRESH_MS) else POINT_REFRESH_MS
    }

    // background samples never wake gps; only the explicit set-to-here action may
    private fun fix(precise: Boolean, maxAgeMs: Long, done: (Location?) -> Unit) {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !permitted()) return done(null)
        if (maxAgeMs > 0) recentFix(manager, maxAgeMs)?.let { return done(it) }
        val providers = buildList {
            if (!precise && Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise && Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
        }
        val provider = providers.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return done(null)
        try {
            val executor = ContextCompat.getMainExecutor(context)
            when {
                !precise && Build.VERSION.SDK_INT >= 31 -> manager.getCurrentLocation(provider,
                    android.location.LocationRequest.Builder(0L)
                        .setQuality(android.location.LocationRequest.QUALITY_LOW_POWER)
                        .setDurationMillis(15_000L)
                        .build(),
                    null, executor) { done(it) }
                Build.VERSION.SDK_INT >= 30 -> manager.getCurrentLocation(provider, null, executor) { done(it) }
                else -> done(manager.getLastKnownLocation(provider))
            }
        } catch (_: SecurityException) {
            done(null)
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }

    private fun recentFix(manager: LocationManager, maxAgeMs: Long): Location? {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val providers = buildList {
            add(LocationManager.PASSIVE_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { (nowNs - it.elapsedRealtimeNanos) / 1_000_000 in 0..maxAgeMs && it.accuracy <= MAX_REUSE_ACCURACY_M }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private fun load(): Places = runCatching {
        if (file.exists()) gson.fromJson(file.readText(), Places::class.java) ?: Places() else Places()
    }.getOrDefault(Places())

    private companion object {
        const val REFRESH_MS = 10 * 60_000L
        const val STALE_MS = 30 * 60_000L
        const val POINT_REFRESH_MS = 2 * 60_000L
        const val POINT_MAX_REFRESH_MS = 8 * 60_000L
        const val POINT_STALE_SLACK_MS = 3 * 60_000L
        const val STILL_RADIUS_M = 60f
        const val MAX_REUSE_ACCURACY_M = 200f
    }
}
