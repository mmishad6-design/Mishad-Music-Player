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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

data class AudioTrack(val id: Long, val title: String, val artist: String, val uri: android.net.Uri)

class MainActivity : ComponentActivity() {
    private var tracks by mutableStateOf<List<AudioTrack>>(emptyList())
    private var permissionGranted by mutableStateOf(false)
    private var controller by mutableStateOf<MediaController?>(null)
    private var currentIndex by mutableIntStateOf(-1)
    private var isPlaying by mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
        }
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
                isPlaying = controller?.isPlaying == true
                currentIndex = controller?.currentMediaItemIndex ?: -1
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
                        onGrantPermission = { permissionLauncher.launch(permission) },
                        onPlayTrack = { index -> playTrack(index) },
                        onTogglePlayback = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onNext = { controller?.seekToNextMediaItem() }
                    )
                }
            }
        }
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
    onGrantPermission: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
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
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                                IconButton(onClick = onTogglePlayback) {
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        if (isPlaying) "Pause" else "Play", tint = Color(0xFFB9A2FF))
                                }
                                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
}
