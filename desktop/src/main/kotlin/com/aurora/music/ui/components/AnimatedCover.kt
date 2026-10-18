package com.aurora.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.AnimatedCover
import com.aurora.music.desktop.video.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

data class CoverKey(val album: String, val artist: String, val path: String = "") {
    companion object {
        fun of(song: com.aurora.music.model.Song): CoverKey = CoverKey(song.album, song.artist, localPath(song))

        fun localPath(song: com.aurora.music.model.Song): String = song.path.ifBlank {
            song.streamUrl.takeIf { it.startsWith("file:") }?.let { runCatching { java.io.File(java.net.URI(it)).path }.getOrNull() }.orEmpty()
        }
    }
}

val LocalAnimatedCovers = androidx.compose.runtime.staticCompositionLocalOf<com.aurora.music.data.AnimatedCovers?> { null }

@Composable
internal fun AnimatedCoverLayer(cover: CoverKey, modifier: Modifier) {
    val covers = LocalAnimatedCovers.current ?: return
    val config by covers.config.collectAsStateWithLifecycle()
    val found by produceState<AnimatedCover?>(null, cover.album, cover.artist, cover.path, config) {
        value = covers.find(cover.album, cover.artist, cover.path)
    }
    val current = found ?: return
    key(current.file) {
        if (current.video) CoverVideo(current, modifier) else CoverAnimation(current, modifier)
    }
}

// muted looping video, center-cropped, shown once its first frame is ready
@Composable
private fun CoverVideo(cover: AnimatedCover, modifier: Modifier) {
    val player = remember(cover.file) { VideoPlayer(cover.file.absolutePath, loop = true, maxWidth = 720) }
    DisposableEffect(player) {
        player.play()
        onDispose { player.close() }
    }
    val frame by player.frame.collectAsStateWithLifecycle()
    Faded(frame, modifier)
}

// gif and animated webp through skia's own codec, each frame for its own duration
@Composable
private fun CoverAnimation(cover: AnimatedCover, modifier: Modifier) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover.file) {
        val codec = withContext(Dispatchers.IO) { runCatching { Codec.makeFromData(Data.makeFromBytes(cover.file.readBytes())) }.getOrNull() } ?: return@LaunchedEffect
        val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
        val durations = codec.framesInfo.map { it.duration.coerceAtLeast(20) }
        if (durations.isEmpty()) {
            codec.readPixels(bitmap, 0)
            frame = SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
            return@LaunchedEffect
        }
        var index = 0
        while (isActive) {
            withContext(Dispatchers.Default) { codec.readPixels(bitmap, index) }
            frame = SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
            delay(durations[index].toLong())
            index = (index + 1) % durations.size
        }
    }
    Faded(frame, modifier)
}

@Composable
private fun Faded(frame: ImageBitmap?, modifier: Modifier) {
    val alpha by animateFloatAsState(if (frame != null) 1f else 0f, tween(400), label = "coverFade")
    frame?.let { Image(it, null, modifier.alpha(alpha), contentScale = ContentScale.Crop) }
}
