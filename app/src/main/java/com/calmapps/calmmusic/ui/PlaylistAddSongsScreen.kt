package com.calmapps.calmmusic.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmapps.calmmusic.CalmMusicViewModel
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAddSongsScreen(
    songs: List<SongUiModel>,
    artists: List<ArtistUiModel>,
    viewModel: CalmMusicViewModel,
    existingSongIds: Set<String>,
    initialSelectedSongIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
) {
    var selectedIds by remember { mutableStateOf(initialSelectedSongIds) }

    LaunchedEffect(initialSelectedSongIds) {
        selectedIds = initialSelectedSongIds
    }

    fun toggleSong(songId: String) {
        val newSelection = selectedIds.toMutableSet()
        if (!newSelection.add(songId)) {
            newSelection.remove(songId)
        }
        selectedIds = newSelection
        onSelectionChanged(newSelection)
    }

    // 0 = Songs (flat list), 1 = Artists (browse by artist → album)
    var selectedTab by remember { mutableStateOf(0) }

    var browseArtist by remember { mutableStateOf<ArtistUiModel?>(null) }
    var browseAlbum by remember { mutableStateOf<AlbumUiModel?>(null) }

    var artistAlbums by remember { mutableStateOf<List<AlbumUiModel>>(emptyList()) }
    var artistSongs by remember { mutableStateOf<List<SongUiModel>>(emptyList()) }
    var albumSongs by remember { mutableStateOf<List<SongUiModel>>(emptyList()) }
    var isLoadingArtist by remember { mutableStateOf(false) }
    var isLoadingAlbum by remember { mutableStateOf(false) }

    LaunchedEffect(browseArtist?.id) {
        val artist = browseArtist
        artistAlbums = emptyList()
        artistSongs = emptyList()
        if (artist != null) {
            isLoadingArtist = true
            try {
                val content = viewModel.getArtistContent(artist.id)
                artistAlbums = content.albums
                artistSongs = content.songs.filter { it.id !in existingSongIds }
            } catch (_: Exception) {
            } finally {
                isLoadingArtist = false
            }
        }
    }

    LaunchedEffect(browseAlbum?.id) {
        val album = browseAlbum
        albumSongs = emptyList()
        if (album != null) {
            isLoadingAlbum = true
            try {
                albumSongs = viewModel.getAlbumSongs(album.id).filter { it.id !in existingSongIds }
            } catch (_: Exception) {
            } finally {
                isLoadingAlbum = false
            }
        }
    }

    // Hardware/gesture back walks up one browse level before leaving the screen.
    BackHandler(enabled = browseArtist != null) {
        if (browseAlbum != null) {
            browseAlbum = null
        } else {
            browseArtist = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                browseAlbum != null -> {
                    val album = browseAlbum!!
                    BrowseBackHeader(
                        title = album.title,
                        subtitle = album.artist,
                        onBackClick = { browseAlbum = null },
                    )

                    when {
                        isLoadingAlbum -> CenteredMessage("Loading songs...")
                        albumSongs.isEmpty() -> CenteredMessage("No songs available to add from this album")
                        else -> SelectableSongList(
                            songs = albumSongs,
                            selectedIds = selectedIds,
                            onToggleSong = ::toggleSong,
                        )
                    }
                }

                browseArtist != null -> {
                    val artist = browseArtist!!
                    BrowseBackHeader(
                        title = artist.name,
                        subtitle = null,
                        onBackClick = { browseArtist = null },
                    )

                    when {
                        isLoadingArtist -> CenteredMessage("Loading albums...")
                        artistAlbums.isEmpty() && artistSongs.isEmpty() ->
                            CenteredMessage("No songs available to add from this artist")
                        artistAlbums.isEmpty() -> SelectableSongList(
                            songs = artistSongs,
                            selectedIds = selectedIds,
                            onToggleSong = ::toggleSong,
                        )
                        else -> {
                            LazyColumnMMD(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                items(artistAlbums.size) { index ->
                                    val album = artistAlbums[index]
                                    BrowseAlbumItem(
                                        album = album,
                                        onClick = { browseAlbum = album },
                                        showDivider = index != artistAlbums.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    PrimaryTabRowMMD(selectedTabIndex = selectedTab) {
                        listOf("Songs", "Artists").forEachIndexed { index, title ->
                            TabMMD(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    TextMMD(
                                        text = title,
                                        fontSize = 16.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                            )
                        }
                    }

                    if (selectedTab == 0) {
                        if (songs.isEmpty()) {
                            CenteredMessage("No songs available to add")
                        } else {
                            SelectableSongList(
                                songs = songs,
                                selectedIds = selectedIds,
                                onToggleSong = ::toggleSong,
                            )
                        }
                    } else {
                        if (artists.isEmpty()) {
                            CenteredMessage("No artists in your library")
                        } else {
                            LazyColumnMMD(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                items(artists.size) { index ->
                                    val artist = artists[index]
                                    BrowseArtistItem(
                                        artist = artist,
                                        onClick = { browseArtist = artist },
                                        showDivider = index != artists.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(text = text)
    }
}

@Composable
private fun BrowseBackHeader(
    title: String,
    subtitle: String?,
    onBackClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBackClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    TextMMD(
                        text = subtitle,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        DashedDivider(thickness = 1.dp)
    }
}

@Composable
private fun BrowseArtistItem(
    artist: ArtistUiModel,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = artist.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val albumsPart = if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums"
                val songsPart = if (artist.songCount == 1) "1 song" else "${artist.songCount} songs"
                TextMMD(
                    text = "$albumsPart • $songsPart",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}

@Composable
private fun BrowseAlbumItem(
    album: AlbumUiModel,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = album.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val year = album.releaseYear
                if (year != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextMMD(
                        text = year.toString(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}

@Composable
private fun SelectableSongList(
    songs: List<SongUiModel>,
    selectedIds: Set<String>,
    onToggleSong: (String) -> Unit,
) {
    LazyColumnMMD(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(songs.size) { index ->
            val song = songs[index]
            SelectableSongItem(
                song = song,
                isSelected = selectedIds.contains(song.id),
                onToggleSelected = { onToggleSong(song.id) },
                showDivider = index != songs.lastIndex,
            )
        }
    }
}

@Composable
private fun SelectableSongItem(
    song: SongUiModel,
    isSelected: Boolean,
    onToggleSelected: () -> Unit,
    showDivider: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelected)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckboxMMD(
                checked = isSelected,
                onCheckedChange = { onToggleSelected() },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                TextMMD(
                    text = song.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val isLocal = song.sourceType == "LOCAL_FILE" || song.sourceType == "YOUTUBE_DOWNLOAD"
                val fileExtension = if (isLocal) {
                    val uriString = song.audioUri ?: song.id
                    try {
                        val lastSegment = Uri.parse(uriString).lastPathSegment ?: ""
                        lastSegment.substringAfterLast('.', "").lowercase()
                    } catch (_: Exception) {
                        ""
                    }
                } else {
                    ""
                }
                val isMp4 = isLocal && fileExtension == "mp4"

                val baseArtist = song.artist.ifBlank { if (isLocal) "Local file" else "" }
                val prefix = when {
                    isMp4 -> "MP4 • "
                    else -> ""
                }
                val subtitle = if (!song.durationText.isNullOrBlank()) {
                    "$prefix${baseArtist} • ${song.durationText}"
                } else {
                    if (baseArtist.isNotBlank()) "$prefix$baseArtist" else if (prefix.isNotBlank()) prefix.trimEnd(' ', '•') else ""
                }
                TextMMD(
                    text = subtitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}
