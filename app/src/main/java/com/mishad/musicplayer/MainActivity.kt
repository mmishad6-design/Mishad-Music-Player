package com.mishad.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

data class AudioTrack(val id: Long, val title: String, val artist: String, val uri: android.net.Uri)

class MainActivity : ComponentActivity() {
    private var tracks by mutableStateOf<List<AudioTrack>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private var controller by mutableStateOf<MediaController?>(null)
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
            refreshPlaybackState()
        }
        override fun onPlaybackStateChanged(playbackState: Int) { refreshPlaybackState() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffleEnabled = shuffleModeEnabled }
        override fun onRepeatModeChanged(newRepeatMode: Int) { repeatMode = newRepeatMode }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) loadTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) loadTracks()

        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(playerListener)
                refreshPlaybackState()
            } catch (_: Exception) {
                controller = null
            }
        }, mainExecutor)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101116)) {
                    MusicHome(
                        tracks = tracks,
                        hasPermission = permissionGranted,
                        currentIndex = currentIndex,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        onGrantPermission = { permissionLauncher.launch(permission) },
                        onPlayTrack = { index -> playTrack(index) },
                        onTogglePlayback = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onNext = { controller?.seekToNextMediaItem() },
                        onSeek = { controller?.seekTo(it) },
                        onProgressTick = { refreshPlaybackState() },
                        onToggleShuffle = {
                            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                        },
                        onCycleRepeat = {
                            controller?.let {
                                it.repeatMode = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun refreshPlaybackState() {
        val player = controller ?: return
        currentIndex = player.currentMediaItemIndex
        positionMs = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
        shuffleEnabled = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        isPlaying = player.isPlaying
    }

    private fun playTrack(index: Int) {
        val player = controller ?: return
        if (index !in tracks.indices) return
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        currentIndex = index
        refreshPlaybackState()
    }

    private fun loadTracks() {
        val found = mutableListOf<AudioTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        contentResolver.query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                found += AudioTrack(
                    id,
                    cursor.getString(titleCol) ?: "Unknown title",
                    cursor.getString(artistCol) ?: "Unknown artist",
                    ContentUris.withAppendedId(collection, id)
                )
            }
        }
        tracks = found
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun MusicHome(
    tracks: List<AudioTrack>,
    hasPermission: Boolean,
    currentIndex: Int,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onGrantPermission: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onProgressTick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    LaunchedEffect(isPlaying, currentIndex) {
        while (isPlaying) {
            onProgressTick()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101116)).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("MISHAD", color = Color(0xFFB9A2FF), fontSize = 13.sp, letterSpacing = 4.sp)
        Text("Music Player", color = Color.White, fontSize = 30.sp)
        Text("Your music, your space.", color = Color(0xFF9A9BA5))
        Spacer(Modifier.height(26.dp))
        Text("YOUR LIBRARY", color = Color(0xFFB9A2FF), fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        when {
            !hasPermission -> {
                Text("Allow audio access to find music stored on your phone.", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGrantPermission) { Text("Allow access") }
            }
            tracks.isEmpty() -> {
                Text("No music found on this device.", color = Color.White)
                Text("Add audio files to your phone and reopen the app.", color = Color(0xFF9A9BA5))
            }
            else -> {
                Text(tracks.size.toString() + " songs", color = Color(0xFF9A9BA5))
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        val selected = index == currentIndex
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF302A43) else Color(0xFF1D1F28)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onPlayTrack(index) }
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFB9A2FF))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = Color(0xFF9A9BA5), fontSize = 12.sp, maxLines = 1)
                                }
                                if (selected && isPlaying) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = Color(0xFFB9A2FF))
                                }
                            }
                        }
                    }
                }
                if (currentIndex in tracks.indices) {
                    val current = tracks[currentIndex]
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF24212F)),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(current.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.artist, color = Color(0xFF9A9BA5), fontSize = 12.sp)
                            var sliderPosition by remember(currentIndex) { mutableFloatStateOf(positionMs.toFloat()) }
                            LaunchedEffect(positionMs) {
                                sliderPosition = positionMs.toFloat()
                            }
                            Slider(
                                value = if (durationMs > 0L) sliderPosition.coerceIn(0f, durationMs.toFloat()) else 0f,
                                onValueChange = { sliderPosition = it; onSeek(it.toLong()) },
                                enabled = durationMs > 0L,
                                valueRange = 0f..(durationMs.coerceAtLeast(1L).toFloat()),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFB9A2FF),
                                    activeTrackColor = Color(0xFFB9A2FF),
                                    inactiveTrackColor = Color(0xFF55515F)
                                )
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(positionMs), color = Color(0xFF9A9BA5), fontSize = 11.sp)
                                Text(formatTime(durationMs), color = Color(0xFF9A9BA5), fontSize = 11.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onToggleShuffle) {
                                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) Color(0xFFB9A2FF) else Color(0xFF9A9BA5))
                                }
                                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                                FilledIconButton(
                                    onClick = onTogglePlayback,
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB9A2FF), contentColor = Color(0xFF17131F))
                                ) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        if (isPlaying) "Pause" else "Play")
                                }
                                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                                IconButton(onClick = onCycleRepeat) {
                                    Icon(
                                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                        "Repeat",
                                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFFB9A2FF) else Color(0xFF9A9BA5)
                                    )
                                }
                            }
                            Text(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> "Repeat: One"
                                    Player.REPEAT_MODE_ALL -> "Repeat: All"
                                    else -> if (shuffleEnabled) "Shuffle: On" else "Shuffle: Off"
                                },
                                color = Color(0xFF777783),
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
