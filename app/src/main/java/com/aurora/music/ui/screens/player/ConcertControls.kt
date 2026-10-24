package com.aurora.music.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.aurora.music.R
import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertSeat
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertVenue
import com.aurora.music.localization.appString
import com.aurora.music.playback.engine.ConcertAcoustics
import java.text.NumberFormat
import kotlin.math.atan2
import kotlin.math.hypot

internal fun concertVenueName(id: Int): String = appString(when (id) {
    ConcertVenue.CLUB -> R.string.concert_venue_club
    ConcertVenue.THEATRE -> R.string.concert_venue_theatre
    ConcertVenue.STADIUM -> R.string.concert_venue_stadium
    ConcertVenue.FESTIVAL -> R.string.concert_venue_festival
    ConcertVenue.AMPHITHEATRE -> R.string.concert_venue_amphitheatre
    else -> R.string.concert_venue_arena
})

internal fun concertSoundName(id: Int): String = appString(when (id) {
    ConcertSound.ROCK -> R.string.concert_sound_rock
    ConcertSound.METAL -> R.string.concert_sound_metal
    ConcertSound.SHOEGAZE -> R.string.concert_sound_shoegaze
    ConcertSound.JAZZ -> R.string.concert_sound_jazz
    ConcertSound.HIP_HOP -> R.string.concert_sound_hip_hop
    ConcertSound.ELECTRONIC -> R.string.concert_sound_electronic
    ConcertSound.CLASSICAL -> R.string.concert_sound_classical
    else -> R.string.concert_sound_pop
})

private fun concertSeatName(id: Int): String = appString(when (id) {
    ConcertSeat.FRONT -> R.string.concert_seat_front
    ConcertSeat.BACK -> R.string.concert_seat_back
    ConcertSeat.SIDE -> R.string.concert_seat_side
    ConcertSeat.BALCONY -> R.string.concert_seat_balcony
    else -> R.string.concert_seat_middle
})

@Composable
internal fun ChoiceChips(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { id, label ->
            val active = id == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPick(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun ReverbModeChips(concert: Boolean, modifier: Modifier = Modifier, onPick: (Boolean) -> Unit) =
    ChoiceChips(listOf(appString(R.string.reverb_mode_studio), appString(R.string.reverb_mode_concert)), if (concert) 1 else 0, modifier) { onPick(it == 1) }

@Composable
internal fun ConcertVenuePicker(concert: ConcertPrefs, modifier: Modifier = Modifier, onChange: (ConcertPrefs) -> Unit) {
    val venue = ConcertVenue.ALL[concert.venue]
    val percent = remember { NumberFormat.getPercentInstance() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(ConcertVenue.ALL.indices.map(::concertVenueName), concert.venue) { onChange(concert.withVenue(it)) }
        Text(appString(R.string.concert_sound), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        ChoiceChips(ConcertSound.ALL.indices.map(::concertSoundName), concert.sound) { onChange(concert.copy(sound = it)) }
        ConcertSlider(appString(R.string.concert_size), concert.size,
            label = { appString(R.string.concert_size_value, "%.0f".format(venue.widthM(it)), "%.0f".format(venue.lengthM(it))) }) { onChange(concert.copy(size = it)) }
        ConcertSlider(appString(R.string.concert_openness), concert.openness, label = {
            when { it < 0.03f -> appString(R.string.concert_enclosed); it > 0.97f -> appString(R.string.concert_open_air); else -> percent.format(it) }
        }) { onChange(concert.copy(openness = it)) }
    }
}

@Composable
internal fun ConcertPositionPicker(concert: ConcertPrefs, modifier: Modifier = Modifier, onChange: (ConcertPrefs) -> Unit) {
    var draft by remember { mutableStateOf<ConcertPrefs?>(null) }
    val shown = draft ?: concert
    val distance = remember(shown) { ConcertAcoustics.room(shown).distanceM }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConcertVenueMap(shown, concert, onDraft = { draft = it }, onChange = onChange)
        Text(appString(if (shown.elevation > 0f) R.string.concert_distance_raised else R.string.concert_distance, "%.0f".format(distance)),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        ChoiceChips(ConcertSeat.ALL.map(::concertSeatName), shown.seat() ?: -1) { onChange(concert.withSeat(it)) }
        Text(appString(R.string.concert_map_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// top-down floor plan, stage at its spot facing the room, drag the dot (or the stage) around
@Composable
private fun ConcertVenueMap(shown: ConcertPrefs, stored: ConcertPrefs, onDraft: (ConcertPrefs?) -> Unit, onChange: (ConcertPrefs) -> Unit) {
    val venue = ConcertVenue.ALL[shown.venue]
    val aspect = (venue.widthM(shown.size) / venue.lengthM(shown.size)).coerceIn(0.55f, 1.8f)
    val latest by rememberUpdatedState(shown)
    val base by rememberUpdatedState(stored)
    val draft by rememberUpdatedState(onDraft)
    val change by rememberUpdatedState(onChange)
    val floor = MaterialTheme.colorScheme.surfaceContainerHighest
    val wall = MaterialTheme.colorScheme.outline
    val stageColor = MaterialTheme.colorScheme.secondary
    val you = MaterialTheme.colorScheme.tertiary
    val youRing = MaterialTheme.colorScheme.onTertiary
    val label = appString(R.string.concert_map_label)
    fun place(at: Offset, canvas: Size, pad: Float, stage: Boolean, from: ConcertPrefs): ConcertPrefs {
        val r = venueRect(canvas, aspect, pad)
        val x = ((at.x - r.left) / r.width).coerceIn(0.02f, 0.98f)
        val y = ((at.y - r.top) / r.height).coerceIn(0.02f, 0.98f)
        return if (stage) from.copy(stageX = x, stageY = y) else from.copy(listenerX = x, listenerY = y)
    }
    Canvas(
        Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = label }
            .pointerInput(aspect) {
                detectTapGestures { at -> change(place(at, size.toSize(), 2.dp.toPx(), stage = false, from = base)) }
            }
            .pointerInput(aspect) {
                var stage = false
                detectDragGestures(
                    onDragStart = { at ->
                        val r = venueRect(size.toSize(), aspect, 2.dp.toPx())
                        stage = (at - Offset(r.left + base.stageX * r.width, r.top + base.stageY * r.height)).getDistance() < 28.dp.toPx()
                    },
                    onDrag = { pointer, _ ->
                        pointer.consume()
                        val next = place(pointer.position, size.toSize(), 2.dp.toPx(), stage, latest)
                        draft(next); change(next)
                    },
                    onDragEnd = { draft(null) },
                    onDragCancel = { draft(null) },
                )
            },
    ) {
        val r = venueRect(size, aspect, 2.dp.toPx())
        val corner = CornerRadius(14.dp.toPx())
        drawRoundRect(floor, r.topLeft, r.size, corner)
        drawRoundRect(wall.copy(alpha = 1f - 0.7f * shown.openness), r.topLeft, r.size, corner,
            style = Stroke(2.dp.toPx(), pathEffect = if (shown.openness > 0.5f) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
        val s = Offset(r.left + shown.stageX * r.width, r.top + shown.stageY * r.height)
        val p = Offset(r.left + shown.listenerX * r.width, r.top + shown.listenerY * r.height)
        clipRect(r.left, r.top, r.right, r.bottom) {
            for (i in 1..4) drawCircle(stageColor.copy(alpha = 0.12f), radius = i * r.minDimension * 0.2f, center = s, style = Stroke(1.5.dp.toPx()))
        }
        drawLine(you.copy(alpha = 0.6f), p, s, strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val centre = r.center - s
        val facing = if (hypot(centre.x / r.width, centre.y / r.height) > 0.12f) centre else p - s
        val stageW = (venue.stageWidthM(shown.size) / venue.widthM(shown.size) * r.width).coerceAtLeast(20.dp.toPx())
        val stageD = 9.dp.toPx()
        rotate(Math.toDegrees(atan2(facing.y, facing.x).toDouble()).toFloat() - 90f, pivot = s) {
            drawRoundRect(stageColor, Offset(s.x - stageW / 2, s.y - stageD / 2), Size(stageW, stageD), CornerRadius(4.dp.toPx()))
        }
        if (shown.elevation > 0f) drawCircle(you.copy(alpha = 0.3f), 15.dp.toPx(), p)
        drawCircle(you, 9.dp.toPx(), p)
        drawCircle(youRing, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
    }
}

private fun venueRect(canvas: Size, aspect: Float, pad: Float): Rect {
    val w = canvas.width - 2 * pad; val h = canvas.height - 2 * pad
    val size = if (w / h > aspect) Size(h * aspect, h) else Size(w, w / aspect)
    return Rect(Offset((canvas.width - size.width) / 2, (canvas.height - size.height) / 2), size)
}

// drafts the thumb locally so datastore round-trips never make it jump
@Composable
private fun ConcertSlider(title: String, value: Float, label: (Float) -> String, onChange: (Float) -> Unit) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft ?: value
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label(shown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = shown,
            onValueChange = { draft = it; onChange(it) },
            onValueChangeFinished = { draft = null },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary),
        )
    }
}
