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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Person
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
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF101116)
private val Panel = Color(0xFF1D1F28)
private val Accent = Color(0xFFB9A2FF)
private val Muted = Color(0xFF9A9BA5)

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: android.net.Uri
)

enum class MusicTab(val label: String) {
    Songs("Songs"), Favorites("Favorites"), Playlists("Playlists"),
    Albums("Albums"), Artists("Artists"), Recent("Recent")
}

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
    private var favorites by mutableStateOf<Set<Long>>(emptySet())
    private var playlists by mutableStateOf<Map<String, List<Long>>>(mapOf("My Playlist" to emptyList()))
    private var recentlyPlayed by mutableStateOf<List<Long>>(emptyList())
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
            refreshPlaybackState()
            if (currentIndex in tracks.indices) rememberPlayed(tracks[currentIndex].id)
        }
        override fun onPlaybackStateChanged(playbackState: Int) { refreshPlaybackState() }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleEnabled = enabled }
        override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) loadTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSavedLibrary()
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
            } catch (_: Exception) { controller = null }
        }, mainExecutor)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    MusicHome(
                        tracks = tracks, hasPermission = permissionGranted,
                        currentIndex = currentIndex, isPlaying = isPlaying,
                        positionMs = positionMs, durationMs = durationMs,
                        shuffleEnabled = shuffleEnabled, repeatMode = repeatMode,
                        favorites = favorites, playlists = playlists, recentlyPlayed = recentlyPlayed,
                        onGrantPermission = { permissionLauncher.launch(permission) },
                        onPlayTrack = { playTrack(it) },
                        onTogglePlayback = { controller?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onNext = { controller?.seekToNextMediaItem() },
                        onSeek = { controller?.seekTo(it) },
                        onProgressTick = { refreshPlaybackState() },
                        onToggleShuffle = { controller?.let { p -> p.shuffleModeEnabled = !p.shuffleModeEnabled } },
                        onCycleRepeat = {
                            controller?.let { p ->
                                p.repeatMode = when (p.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        },
                        onToggleFavorite = { toggleFavorite(it) },
                        onCreatePlaylist = { createPlaylist(it) },
                        onAddToPlaylist = { id, name -> addToPlaylist(id, name) }
                    )
                }
            }
        }
    }

    private fun loadSavedLibrary() {
        val prefs = getSharedPreferences("music_library", MODE_PRIVATE)
        favorites = prefs.getString("favorites", null)?.let { raw ->
            val a = JSONArray(raw); (0 until a.length()).map { a.getLong(it) }.toSet()
        } ?: emptySet()
        playlists = prefs.getString("playlists", null)?.let { raw ->
            val obj = JSONObject(raw); buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next(); val arr = obj.getJSONArray(key)
                    put(key, (0 until arr.length()).map { arr.getLong(it) })
                }
            }
        } ?: mapOf("My Playlist" to emptyList())
        recentlyPlayed = prefs.getString("recent", null)?.let { raw ->
            val a = JSONArray(raw); (0 until a.length()).map { a.getLong(it) }
        } ?: emptyList()
    }

    private fun saveLibrary() {
        val playlistJson = JSONObject()
        playlists.forEach { (name, ids) ->
            playlistJson.put(name, JSONArray().apply { ids.forEach { put(it) } })
        }
        getSharedPreferences("music_library", MODE_PRIVATE).edit()
            .putString("favorites", JSONArray().apply { favorites.forEach { put(it) } }.toString())
            .putString("playlists", playlistJson.toString())
            .putString("recent", JSONArray().apply { recentlyPlayed.forEach { put(it) } }.toString())
            .apply()
    }

    private fun toggleFavorite(id: Long) {
        favorites = if (id in favorites) favorites - id else favorites + id
        saveLibrary()
    }

    private fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && clean !in playlists) {
            playlists = playlists + (clean to emptyList())
            saveLibrary()
        }
    }

    private fun addToPlaylist(id: Long, name: String) {
        val existing = playlists[name] ?: return
        playlists = playlists + (name to (existing + id).distinct())
        saveLibrary()
    }

    private fun rememberPlayed(id: Long) {
        recentlyPlayed = (listOf(id) + recentlyPlayed.filterNot { it == id }).take(50)
        saveLibrary()
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
            MediaItem.Builder().setUri(track.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()
            ).build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        currentIndex = index
        rememberPlayed(tracks[index].id)
        refreshPlaybackState()
    }

    private fun loadTracks() {
        val found = mutableListOf<AudioTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        contentResolver.query(collection, projection, selection, null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                found += AudioTrack(
                    id, cursor.getString(titleCol) ?: "Unknown title",
                    cursor.getString(artistCol) ?: "Unknown artist",
                    cursor.getString(albumCol) ?: "Unknown album",
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
    tracks: List<AudioTrack>, hasPermission: Boolean,
    currentIndex: Int, isPlaying: Boolean, positionMs: Long, durationMs: Long,
    shuffleEnabled: Boolean, repeatMode: Int, favorites: Set<Long>,
    playlists: Map<String, List<Long>>, recentlyPlayed: List<Long>,
    onGrantPermission: () -> Unit, onPlayTrack: (Int) -> Unit,
    onTogglePlayback: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Long) -> Unit, onProgressTick: () -> Unit,
    onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit,
    onToggleFavorite: (Long) -> Unit, onCreatePlaylist: (String) -> Unit,
    onAddToPlaylist: (Long, String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(MusicTab.Songs) }
    var search by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Title") }
    var createDialog by remember { mutableStateOf(false) }
    var playlistDialogTrack by remember { mutableStateOf<Long?>(null) }
    var playlistName by remember { mutableStateOf("") }

    LaunchedEffect(isPlaying, currentIndex) {
        while (isPlaying) { onProgressTick(); delay(500) }
    }

    val visibleTracks = remember(tracks, selectedTab, search, sortMode, favorites, playlists, recentlyPlayed) {
        val base = when (selectedTab) {
            MusicTab.Favorites -> tracks.filter { it.id in favorites }
            MusicTab.Playlists -> tracks.filter { it.id in playlists.values.flatten().toSet() }
            MusicTab.Recent -> recentlyPlayed.mapNotNull { id -> tracks.find { it.id == id } }
            else -> tracks
        }
        val filtered = base.filter {
            it.title.contains(search, true) || it.artist.contains(search, true) || it.album.contains(search, true)
        }
        when (sortMode) {
            "Artist" -> filtered.sortedBy { it.artist.lowercase() }
            "Album" -> filtered.sortedBy { it.album.lowercase() }
            "Newest" -> filtered.sortedByDescending { it.id }
            else -> if (selectedTab == MusicTab.Recent) filtered else filtered.sortedBy { it.title.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text("MISHAD", color = Accent, fontSize = 12.sp, letterSpacing = 4.sp)
            Text("Music Player", color = Color.White, fontSize = 28.sp)
            Text("Your music, your space.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search songs, artists, albums…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF393A45),
                    focusedLeadingIconColor = Accent, unfocusedLeadingIconColor = Muted
                )
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selectedTab.label.uppercase(), color = Accent, fontSize = 12.sp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Default.Sort, null, tint = Muted)
                        Text(sortMode, color = Muted)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("Title", "Artist", "Album", "Newest").forEach { mode ->
                            DropdownMenuItem(text = { Text(mode) }, onClick = { sortMode = mode; sortMenu = false })
                        }
                    }
                }
                if (selectedTab == MusicTab.Playlists) {
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Default.Add, "Create playlist", tint = Accent) }
                }
            }
        }

        if (!hasPermission) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Allow audio access to find music stored on your phone.", color = Color.White)
                Button(onClick = onGrantPermission) { Text("Allow access") }
            }
        } else {
            when (selectedTab) {
                MusicTab.Albums -> GroupedLibrary(tracks = visibleTracks, groupByAlbum = true, currentIndex = currentIndex, allTracks = tracks, onPlayTrack = onPlayTrack)
                MusicTab.Artists -> GroupedLibrary(tracks = visibleTracks, groupByAlbum = false, currentIndex = currentIndex, allTracks = tracks, onPlayTrack = onPlayTrack)
                MusicTab.Playlists -> {
                    if (playlists.isEmpty()) EmptyMessage("No playlists yet. Tap + to create one.")
                    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                        playlists.forEach { (name, ids) ->
                            item(key = "playlist_$name") {
                                Text(name, color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                Text("${ids.size} songs", color = Muted, fontSize = 12.sp)
                            }
                            val list = ids.mapNotNull { id -> tracks.find { it.id == id } }
                            items(list, key = { "pl_${name}_${it.id}" }) { track ->
                                TrackRow(track, tracks.indexOfFirst { it.id == track.id }, currentIndex, isPlaying,
                                    track.id in favorites, onPlayTrack, onToggleFavorite) { playlistDialogTrack = track.id }
                            }
                        }
                    }
                }
                else -> {
                    if (visibleTracks.isEmpty()) EmptyMessage(
                        when (selectedTab) {
                            MusicTab.Favorites -> "No favorites yet. Tap the heart on a song."
                            MusicTab.Recent -> "Your recently played songs will appear here."
                            else -> "No matching songs found."
                        }
                    ) else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                        items(visibleTracks, key = { it.id }) { track ->
                            TrackRow(track, tracks.indexOfFirst { it.id == track.id }, currentIndex, isPlaying,
                                track.id in favorites, onPlayTrack, onToggleFavorite) { playlistDialogTrack = track.id }
                        }
                    }
                }
            }
        }

        if (currentIndex in tracks.indices) {
            MiniPlayer(
                track = tracks[currentIndex], isPlaying = isPlaying,
                positionMs = positionMs, durationMs = durationMs,
                shuffleEnabled = shuffleEnabled, repeatMode = repeatMode,
                onTogglePlayback = onTogglePlayback, onPrevious = onPrevious, onNext = onNext,
                onSeek = onSeek, onToggleShuffle = onToggleShuffle, onCycleRepeat = onCycleRepeat
            )
        }

        NavigationBar(containerColor = Color(0xFF191A22), contentColor = Accent) {
            MusicTab.entries.forEach { tab ->
                val icon = when (tab) {
                    MusicTab.Songs -> Icons.Default.Headphones
                    MusicTab.Favorites -> Icons.Default.Favorite
                    MusicTab.Playlists -> Icons.Default.QueueMusic
                    MusicTab.Albums -> Icons.Default.Album
                    MusicTab.Artists -> Icons.Default.Person
                    MusicTab.Recent -> Icons.Default.Repeat
                }
                NavigationBarItem(
                    selected = selectedTab == tab, onClick = { selectedTab = tab },
                    icon = { Icon(icon, contentDescription = tab.label) },
                    label = { Text(tab.label, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent, selectedTextColor = Accent,
                        unselectedIconColor = Muted, unselectedTextColor = Muted,
                        indicatorColor = Color(0xFF302A43)
                    )
                )
            }
        }
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                    label = { Text("Playlist name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { onCreatePlaylist(playlistName); playlistName = ""; createDialog = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Cancel") } }
        )
    }

    playlistDialogTrack?.let { trackId ->
        AlertDialog(
            onDismissRequest = { playlistDialogTrack = null },
            title = { Text("Add to playlist") },
            text = {
                Column {
                    if (playlists.isEmpty()) Text("Create a playlist first.")
                    playlists.keys.forEach { name ->
                        TextButton(onClick = { onAddToPlaylist(trackId, name); playlistDialogTrack = null }) {
                            Text(name, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistDialogTrack = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun TrackRow(
    track: AudioTrack, index: Int, currentIndex: Int, isPlaying: Boolean,
    favorite: Boolean, onPlayTrack: (Int) -> Unit, onToggleFavorite: (Long) -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (index == currentIndex) Color(0xFF302A43) else Panel),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlayTrack(index) }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = Accent)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} • ${track.album}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onToggleFavorite(track.id) }) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite", tint = if (favorite) Accent else Muted)
            }
            IconButton(onClick = onAddToPlaylist) { Icon(Icons.Default.Add, "Add to playlist", tint = Muted) }
        }
    }
}

@Composable
private fun GroupedLibrary(
    tracks: List<AudioTrack>, groupByAlbum: Boolean, currentIndex: Int,
    allTracks: List<AudioTrack>, onPlayTrack: (Int) -> Unit
) {
    val groups = tracks.groupBy { if (groupByAlbum) it.album else it.artist }.toSortedMap()
    if (groups.isEmpty()) {
        EmptyMessage(if (groupByAlbum) "No albums found." else "No artists found.")
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        groups.forEach { (name, songs) ->
            item(key = "group_$name") {
                Text(name.ifBlank { "Unknown" }, color = Accent, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Text("${songs.size} songs", color = Muted, fontSize = 11.sp)
            }
            items(songs, key = { "group_${name}_${it.id}" }) { track ->
                TrackRow(track, allTracks.indexOfFirst { it.id == track.id }, currentIndex, false,
                    false, onPlayTrack, {}, {})
            }
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 150.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Muted)
    }
}

@Composable
private fun MiniPlayer(
    track: AudioTrack, isPlaying: Boolean, positionMs: Long, durationMs: Long,
    shuffleEnabled: Boolean, repeatMode: Int,
    onTogglePlayback: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Long) -> Unit, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF24212F)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, color = Muted, fontSize = 11.sp)
        Slider(
            value = if (durationMs > 0) positionMs.toFloat().coerceIn(0f, durationMs.toFloat()) else 0f,
            onValueChange = { onSeek(it.toLong()) }, enabled = durationMs > 0,
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(positionMs), color = Muted, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffleEnabled) Accent else Muted)
                }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                FilledIconButton(onClick = onTogglePlayback,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color(0xFF17131F))) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                IconButton(onClick = onCycleRepeat) {
                    Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat", tint = if (repeatMode != Player.REPEAT_MODE_OFF) Accent else Muted)
                }
            }
            Text(formatTime(durationMs), color = Muted, fontSize = 10.sp)
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
