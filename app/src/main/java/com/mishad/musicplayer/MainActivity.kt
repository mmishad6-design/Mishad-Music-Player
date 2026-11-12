package com.mishad.musicplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

data class AudioTrack(val id: Long, val title: String, val artist: String, val uri: android.net.Uri)

class MainActivity : ComponentActivity() {
    private var tracks by mutableStateOf<List<AudioTrack>>(emptyList())
    private var permissionGranted by mutableStateOf(false)

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
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101116)) {
                    MusicHome(tracks, permissionGranted) { permissionLauncher.launch(permission) }
                }
            }
        }
    }

    private fun loadTracks() {
        val found = mutableListOf<AudioTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
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
}

@Composable
private fun MusicHome(tracks: List<AudioTrack>, hasPermission: Boolean, onGrantPermission: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF101116)).padding(20.dp)) {
        Text("MISHAD", color = Color(0xFFB9A2FF), fontSize = 13.sp, letterSpacing = 4.sp)
        Text("Music Player", color = Color.White, fontSize = 30.sp)
        Text("Your music, your space.", color = Color(0xFF9A9BA5))
        Spacer(Modifier.height(24.dp))
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
                Text("${tracks.size} songs", color = Color(0xFF9A9BA5))
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1F28)),
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFB9A2FF))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(track.title, color = Color.White, maxLines = 1)
                                    Text(track.artist, color = Color(0xFF9A9BA5), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
