package com.calmapps.calmmusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD

@Composable
fun SearchScreen(
    localSongs: List<SongUiModel>,
    onPlaySongClick: (SongUiModel) -> Unit,
    onAddToPlaylistClick: (SongUiModel) -> Unit = {},
    onDeleteClick: (SongUiModel) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
                if (localSongs.isNotEmpty()) {
                    items(localSongs.size) { index ->
                        val song = localSongs[index]
                        SongItem(
                            song = song,
                            isCurrentlyPlaying = false,
                            onClick = { onPlaySongClick(song) },
                            onAddToPlaylist = { onAddToPlaylistClick(song) },
                            onDelete = { onDeleteClick(song) },
                            showDivider = song != localSongs.lastOrNull(),
                            isInLibrary = true,
                        )
                    }
                } else {
                    item {
                        TextMMD(text = "No songs found. Try a different search.")
                    }
                }
            }
        }
    }
}
