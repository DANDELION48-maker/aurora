package com.aurora.music.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Place(val latitude: Double, val longitude: Double, val radiusM: Int = 250)

data class Places(val home: Place? = null, val work: Place? = null) {
    val configured: Boolean get() = home != null || work != null
}

object PlaceZones {
    const val HOME = "HOME"
    const val WORK = "WORK"
    const val AWAY = "AWAY"

    // a fix is only compared with the two saved places and then discarded; plays keep just the label
    fun zoneFor(latitude: Double, longitude: Double, accuracyM: Float, places: Places): String? {
        if (!places.configured) return null
        val slack = accuracyM.coerceIn(0f, 150f)
        return listOfNotNull(places.home?.let { HOME to it }, places.work?.let { WORK to it })
            .map { (name, place) -> name to (distanceM(latitude, longitude, place.latitude, place.longitude) - place.radiusM - slack) }
            .filter { it.second <= 0 }
            .minByOrNull { it.second }?.first ?: AWAY
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
