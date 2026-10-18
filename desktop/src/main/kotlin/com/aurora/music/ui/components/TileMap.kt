package com.aurora.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tan

data class MapPoint(val latitude: Double, val longitude: Double)

data class MapMarker<K>(val key: K, val point: MapPoint, val active: Boolean)

// openstreetmap's standard tiles, fetched two at a time with an identifying user agent and kept on disk,
// the way its tile usage policy asks
class MapTiles(private val directory: File, http: OkHttpClient) {
    private val http = http.newBuilder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    internal val images = mutableStateMapOf<String, ImageBitmap>()

    fun request(z: Int, x: Int, y: Int) {
        val key = "$z/$x/$y"
        if (images.containsKey(key) || !loading.add(key)) return
        scope.launch {
            try {
                val file = File(directory, "$key.png")
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
                val bytes = if (fresh) file.readBytes() else gate.withPermit { download(z, x, y) }?.also { bytes ->
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                } ?: file.takeIf { it.isFile }?.readBytes()
                bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }?.let { images[key] = it }
            } catch (_: Exception) {
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        http.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").header("User-Agent", USER_AGENT).build()).execute().use {
            if (it.isSuccessful) it.body?.bytes() else null
        }
    }.getOrNull()

    private companion object {
        const val USER_AGENT = "Aurora-Desktop/3 (+https://github.com/nessli420/aurora)"
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}

private const val TILE = 256.0
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 18.0

private fun worldX(longitude: Double, zoom: Double) = (longitude + 180.0) / 360.0 * TILE * 2.0.pow(zoom)

private fun worldY(latitude: Double, zoom: Double): Double {
    val radians = Math.toRadians(latitude.coerceIn(-85.05, 85.05))
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * TILE * 2.0.pow(zoom)
}

private fun longitudeAt(x: Double, zoom: Double) = x / (TILE * 2.0.pow(zoom)) * 360.0 - 180.0

private fun latitudeAt(y: Double, zoom: Double): Double {
    val n = PI - 2.0 * PI * y / (TILE * 2.0.pow(zoom))
    return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <K> TileMap(tiles: MapTiles, markers: List<MapMarker<K>>, focus: MapPoint?, onSelect: (K) -> Unit, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var center by remember { mutableStateOf(MapPoint(30.0, 0.0)) }
    var zoom by remember { mutableStateOf(MIN_ZOOM) }
    val current by rememberUpdatedState(markers)
    val select by rememberUpdatedState(onSelect)
    val accent = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surface
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh

    fun screen(point: MapPoint): Offset = Offset(
        (worldX(point.longitude, zoom) - worldX(center.longitude, zoom) + size.width / 2.0).toFloat(),
        (worldY(point.latitude, zoom) - worldY(center.latitude, zoom) + size.height / 2.0).toFloat(),
    )

    fun zoomAround(target: Double, anchor: Offset) {
        val next = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ax = worldX(center.longitude, zoom) + anchor.x - size.width / 2.0
        val ay = worldY(center.latitude, zoom) + anchor.y - size.height / 2.0
        val anchored = MapPoint(latitudeAt(ay, zoom), longitudeAt(ax, zoom))
        val nx = worldX(anchored.longitude, next) - (anchor.x - size.width / 2.0)
        val ny = worldY(anchored.latitude, next) - (anchor.y - size.height / 2.0)
        zoom = next
        center = MapPoint(latitudeAt(ny, next), longitudeAt(nx, next))
    }

    // the whole set of dots is framed when it changes, and a chosen dot is brought into view
    LaunchedEffect(markers.map { it.key }, size) {
        if (size.width == 0 || markers.isEmpty()) return@LaunchedEffect
        if (markers.size == 1) { center = markers.first().point; zoom = 16.0; return@LaunchedEffect }
        val lats = markers.map { it.point.latitude }
        val lons = markers.map { it.point.longitude }
        center = MapPoint((lats.min() + lats.max()) / 2, (lons.min() + lons.max()) / 2)
        val spanX = (worldX(lons.max(), 0.0) - worldX(lons.min(), 0.0)).coerceAtLeast(1e-6)
        val spanY = (worldY(lats.min(), 0.0) - worldY(lats.max(), 0.0)).coerceAtLeast(1e-6)
        zoom = minOf(log2(size.width / 1.3 / spanX), log2(size.height / 1.3 / spanY)).coerceIn(MIN_ZOOM, 16.0)
    }
    LaunchedEffect(focus) {
        focus ?: return@LaunchedEffect
        center = focus
        if (zoom < 16.0) zoom = 16.0
    }

    Box(modifier.clipToBounds().background(placeholder).onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectDragGestures { change, drag ->
                change.consume()
                val x = worldX(center.longitude, zoom) - drag.x
                val y = worldY(center.latitude, zoom) - drag.y
                center = MapPoint(latitudeAt(y, zoom).coerceIn(-85.0, 85.0), ((longitudeAt(x, zoom) + 540.0) % 360.0) - 180.0)
            } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoomAround(zoom + 1, it) }, onTap = { tap ->
                current.minByOrNull { (screen(it.point) - tap).getDistance() }?.takeIf { (screen(it.point) - tap).getDistance() <= 18.dp.toPx() }?.let { select(it.key) }
            }) }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.first()
                zoomAround(zoom - change.scrollDelta.y * 0.5, change.position)
            }) {
            val level = floor(zoom).toInt().coerceIn(0, MAX_ZOOM.toInt())
            val scale = 2.0.pow(zoom - level)
            val cx = worldX(center.longitude, level.toDouble())
            val cy = worldY(center.latitude, level.toDouble())
            val tileSize = TILE * scale
            val left = cx - this.size.width / 2.0 / scale
            val top = cy - this.size.height / 2.0 / scale
            val count = 1 shl level
            val firstX = floor(left / TILE).toInt()
            val firstY = floor(top / TILE).toInt().coerceAtLeast(0)
            val lastX = floor((left + this.size.width / scale) / TILE).toInt()
            val lastY = floor((top + this.size.height / scale) / TILE).toInt().coerceAtMost(count - 1)
            for (tx in firstX..lastX) for (ty in firstY..lastY) {
                val wrapped = ((tx % count) + count) % count
                val key = "$level/$wrapped/$ty"
                val image = tiles.images[key]
                if (image == null) tiles.request(level, wrapped, ty)
                val dx = ((tx * TILE - left) * scale).toFloat()
                val dy = ((ty * TILE - top) * scale).toFloat()
                if (image != null) drawImage(image, dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                    dstSize = IntSize(tileSize.toInt() + 1, tileSize.toInt() + 1))
            }
            current.sortedBy { it.active }.forEach { marker ->
                val at = screen(marker.point)
                val radius = (if (marker.active) 13 else 9).dp.toPx()
                drawCircle(if (marker.active) accent else accent.copy(alpha = 0.7f), radius, at)
                drawCircle(ring, radius, at, style = Stroke(2.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            FilledTonalIconButton(onClick = { zoomAround(zoom + 1, Offset(size.width / 2f, size.height / 2f)) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Add, null) }
            FilledTonalIconButton(onClick = { zoomAround(zoom - 1, Offset(size.width / 2f, size.height / 2f)) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Remove, null) }
        }
        Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomEnd).padding(6.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}
