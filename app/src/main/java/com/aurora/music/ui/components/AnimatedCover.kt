package com.aurora.music.ui.components

import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AnimatedCover

data class CoverKey(val album: String, val artist: String, val path: String = "")

@Composable
internal fun AnimatedCoverLayer(cover: CoverKey, modifier: Modifier) {
    val covers = (LocalContext.current.applicationContext as AuroraApplication).container.animatedCovers
    val config by covers.config.collectAsStateWithLifecycle()
    val folder = cover.path.substringBeforeLast('/', "")
    val found by produceState<AnimatedCover?>(null, cover.album, cover.artist, folder, config) {
        value = covers.find(cover.album, cover.artist, cover.path)
    }
    val current = found ?: return
    key(current.uri) {
        if (current.video) CoverVideo(current.uri, modifier)
        else AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(current.uri)
                .decoderFactory(if (Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory())
                .crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

// muted looping video, center-cropped, shown once its first frame is ready
@OptIn(UnstableApi::class)
@Composable
private fun CoverVideo(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(1f) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) player.play()
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(400), label = "coverVideo")
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val box = if (maxHeight > 0.dp) maxWidth / maxHeight else 1f
        val wide = aspect > box
        AndroidView(
            factory = { TextureView(it).also(player::setVideoTextureView) },
            modifier = Modifier.requiredSize(if (wide) maxHeight * aspect else maxWidth, if (wide) maxHeight else maxWidth / aspect)
                .graphicsLayer { this.alpha = alpha },
        )
    }
}
