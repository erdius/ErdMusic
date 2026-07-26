package com.calmapps.calmmusic

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.calmapps.calmmusic.data.ArtistWithCounts
import com.calmapps.calmmusic.data.CalmMusicDatabase
import com.calmapps.calmmusic.data.CalmMusicSettingsManager
import com.calmapps.calmmusic.data.LibraryRepository
import com.calmapps.calmmusic.data.NowPlayingSnapshot
import com.calmapps.calmmusic.data.NowPlayingStorage
import com.calmapps.calmmusic.data.NowPlayingRepeatModeKeys
import com.calmapps.calmmusic.data.SongEntity
import com.calmapps.calmmusic.playback.PlaybackCoordinator
import com.calmapps.calmmusic.ui.AlbumUiModel
import com.calmapps.calmmusic.ui.ArtistUiModel
import com.calmapps.calmmusic.ui.PlaylistUiModel
import com.calmapps.calmmusic.ui.RepeatMode
import com.calmapps.calmmusic.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * ViewModel responsible for owning long-lived CalmMusic library state and
 */
class CalmMusicViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app: CalmMusic
        @OptIn(UnstableApi::class)
        get() = getApplication() as CalmMusic

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    private val songDao by lazy { database.songDao() }
    private val albumDao by lazy { database.albumDao() }
    private val artistDao by lazy { database.artistDao() }
    private val playlistDao by lazy { database.playlistDao() }
    private val libraryRepository: LibraryRepository by lazy { LibraryRepository(app) }
    private val nowPlayingStorage: NowPlayingStorage by lazy { app.nowPlayingStorage }

    private val playbackCoordinator = PlaybackCoordinator()
    private var localPlaybackMonitorJob: Job? = null

    private var lastCompletedSongId: String? = null
    private var lastProcessedIcyTitle: String? = null

    private val _librarySongs = MutableStateFlow<List<SongUiModel>>(emptyList())
    val librarySongs: StateFlow<List<SongUiModel>> = _librarySongs

    private val _libraryAlbums = MutableStateFlow<List<AlbumUiModel>>(emptyList())
    val libraryAlbums: StateFlow<List<AlbumUiModel>> = _libraryAlbums

    private val _libraryArtists = MutableStateFlow<List<ArtistUiModel>>(emptyList())
    val libraryArtists: StateFlow<List<ArtistUiModel>> = _libraryArtists

    private val _libraryPlaylists = MutableStateFlow<List<PlaylistUiModel>>(emptyList())

    private val _libraryRefreshTrigger = MutableStateFlow(0)
    val libraryRefreshTrigger: StateFlow<Int> = _libraryRefreshTrigger.asStateFlow()

    private val _isLoadingSongs = MutableStateFlow(true)
    val isLoadingSongs: StateFlow<Boolean> = _isLoadingSongs

    private val _isLoadingAlbums = MutableStateFlow(true)
    val isLoadingAlbums: StateFlow<Boolean> = _isLoadingAlbums

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    suspend fun getAlbumSongs(albumId: String): List<SongUiModel> {
        return withContext(Dispatchers.IO) {
            val idsToFetch = mutableSetOf(albumId)

            val suffix = albumId.substringAfter(":", missingDelimiterValue = "")
            if (suffix.isNotEmpty()) {
                idsToFetch.add("LOCAL_FILE:$suffix")
                idsToFetch.add("YOUTUBE_DOWNLOAD:$suffix")
            }

            val allEntities = idsToFetch.flatMap { id ->
                songDao.getSongsByAlbumId(id)
            }.distinctBy { it.id }

            allEntities.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    discNumber = entity.discNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
        }
    }

    suspend fun getAlbumSongsForDetails(album: AlbumUiModel): List<SongUiModel> {
        return getAlbumSongs(album.id)
    }

    data class ArtistContent(
        val songs: List<SongUiModel>,
        val albums: List<AlbumUiModel>
    )

    suspend fun getArtistContent(artistId: String): ArtistContent {
        return withContext(Dispatchers.IO) {
            fun normalizeName(name: String): String =
                name.trim().replace(Regex("\\s+"), " ").lowercase()

            val allArtists = artistDao.getAllArtistsWithCounts()
            val baseArtist = allArtists.firstOrNull { it.id == artistId }

            val relatedArtistIds: List<String> = if (baseArtist != null) {
                val key = normalizeName(baseArtist.name)
                allArtists.filter { normalizeName(it.name) == key }.map { it.id }
            } else {
                listOf(artistId)
            }

            val songEntities = relatedArtistIds
                .flatMap { id -> songDao.getSongsByArtistId(id) }
                .distinctBy { it.id }

            val albumEntities = relatedArtistIds
                .flatMap { id -> albumDao.getAlbumsByArtistId(id) }
                .distinctBy { it.id }

            val songs = songEntities.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    discNumber = entity.discNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }

            val albumIdToYear: Map<String, Int?> = songEntities
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            val mergedAlbums = albumEntities
                .groupBy {
                    (it.name.lowercase().trim() to (it.artist?.lowercase()?.trim() ?: ""))
                }
                .map { (_, duplicates) ->
                    val primary = duplicates.find { it.sourceType == "LOCAL_FILE" } ?: duplicates.first()

                    AlbumUiModel(
                        id = primary.id,
                        title = primary.name,
                        artist = primary.artist,
                        sourceType = primary.sourceType,
                        releaseYear = albumIdToYear[primary.id],
                    )
                }
                .sortedWith(
                    compareByDescending<AlbumUiModel> { album ->
                        album.releaseYear ?: Int.MIN_VALUE
                    }.thenBy { album -> album.title },
                )

            ArtistContent(songs, mergedAlbums)
        }
    }

    private fun rebuildPlaybackSubqueues(queue: List<SongUiModel>) {
        playbackCoordinator.rebuildPlaybackSubqueues(queue)
    }

    private fun persistPlaybackSnapshot(state: PlaybackState = _playbackState.value) {
        val queueIds = state.playbackQueue.map { it.id }
        val index = state.playbackQueueIndex
        val isPlaying = state.isPlaybackPlaying
        val positionMs = state.nowPlayingPositionMs
        val repeatModeKey = when (state.repeatMode) {
            RepeatMode.OFF -> NowPlayingRepeatModeKeys.OFF
            RepeatMode.QUEUE -> NowPlayingRepeatModeKeys.QUEUE
            RepeatMode.ONE -> NowPlayingRepeatModeKeys.ONE
        }
        val isShuffleOn = state.isShuffleOn

        viewModelScope.launch(Dispatchers.IO) {
            nowPlayingStorage.save(
                NowPlayingSnapshot(
                    queueSongIds = queueIds,
                    currentIndex = index,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    repeatModeKey = repeatModeKey,
                    isShuffleOn = isShuffleOn,
                )
            )
        }
    }

    fun togglePlayback(localController: MediaController?) {
        val state = _playbackState.value
        val song = state.nowPlayingSong ?: return
        val currentlyPlaying = state.isPlaybackPlaying

        if (!currentlyPlaying) {
            val queue = state.playbackQueue
            val index = state.playbackQueueIndex

            val needsInit = when (song.sourceType) {
                "LOCAL_FILE", "YOUTUBE_DOWNLOAD", "INTERNET_RADIO" -> !playbackCoordinator.localQueueInitialized
                else -> false
            }

            if (needsInit && queue.isNotEmpty() && index != null && index in queue.indices) {
                startPlaybackFromQueue(
                    queue = queue,
                    startIndex = index,
                    isNewQueue = false,
                    localController = localController,
                )
                return
            }
        }

        if (currentlyPlaying) {
            localController?.pause()
        } else {
            localController?.playWhenReady = true
        }

        _playbackState.value = state.copy(isPlaybackPlaying = !currentlyPlaying)
        persistPlaybackSnapshot()
    }

    private fun SongUiModel.toQueueEntity(): SongEntity =
        SongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = null,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMillis = durationMillis,
            sourceType = sourceType,
            audioUri = audioUri ?: id,
            artistId = null,
            releaseYear = null,
            localLastModifiedMillis = null,
            localFileSizeBytes = null,
        )

    fun startPlaybackFromQueue(
        queue: List<SongUiModel>,
        startIndex: Int,
        isNewQueue: Boolean = true,
        localController: MediaController?,
        startPositionMs: Long = 0L,
    ) {
        if (queue.isEmpty() || startIndex !in queue.indices) return

        val previous = _playbackState.value
        val originalQueue = if (isNewQueue) queue else previous.originalPlaybackQueue
        val shuffle = if (isNewQueue) false else previous.isShuffleOn

        rebuildPlaybackSubqueues(queue)

        val song = queue[startIndex]
        val repeatMode = previous.repeatMode

        if (song.sourceType == "INTERNET_RADIO") {
            // Fresh station tune-in: any previously tracked ICY title is stale.
            lastProcessedIcyTitle = null
            app.playbackStateManager.updateIcyStreamTitle(null)
        }

        val queueEntities = queue.map { it.toQueueEntity() }

        val newState = previous.copy(
            playbackQueue = queue,
            playbackQueueEntities = queueEntities,
            playbackQueueIndex = startIndex,
            originalPlaybackQueue = originalQueue,
            isShuffleOn = shuffle,
            currentSongId = song.id,
            nowPlayingSong = song,
            isPlaybackPlaying = true,
            isBuffering = false,
            nowPlayingPositionMs = startPositionMs,
            nowPlayingDurationMs = song.durationMillis ?: 0L,
        )
        _playbackState.value = newState
        persistPlaybackSnapshot(newState)

        if (song.sourceType == "LOCAL_FILE" || song.sourceType == "YOUTUBE_DOWNLOAD" || song.sourceType == "INTERNET_RADIO") {
            val controller = localController
            if (controller != null && playbackCoordinator.localMediaItemsForQueue.isNotEmpty()) {

                // Identify the contiguous segment of playable local media
                // starting from startIndex (entries with missing URIs are gaps).
                var segmentEndIndex = startIndex
                while (segmentEndIndex < queue.size &&
                    (queue[segmentEndIndex].sourceType == "LOCAL_FILE" ||
                            queue[segmentEndIndex].sourceType == "YOUTUBE_DOWNLOAD" ||
                            queue[segmentEndIndex].sourceType == "INTERNET_RADIO")
                ) {
                    segmentEndIndex++
                }

                // Construct the MediaItem list for ONLY this segment
                val segmentMediaItems = (startIndex until segmentEndIndex).mapNotNull { globalIndex ->
                    val localIndex = playbackCoordinator.localIndexByGlobal?.get(globalIndex)
                    if (localIndex != null && localIndex != -1) {
                        playbackCoordinator.localMediaItemsForQueue.getOrNull(localIndex)
                    } else null
                }

                if (segmentMediaItems.isNotEmpty()) {
                    controller.setMediaItems(
                        segmentMediaItems,
                        0, // Start at the beginning of THIS segment
                        startPositionMs
                    )

                    // IMPORTANT: Never delegate REPEAT_ALL to the local player.
                    // The ViewModel must handle the loop.
                    controller.repeatMode = when (repeatMode) {
                        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }

                    controller.prepare()
                    controller.playWhenReady = true
                    playbackCoordinator.localQueueInitialized = true
                }
            }
        }
    }

    fun startShuffledPlaybackFromQueue(
        queue: List<SongUiModel>,
        localController: MediaController?,
    ) {
        if (queue.isEmpty()) return

        val shuffledQueue = queue.shuffled()
        val previous = _playbackState.value

        _playbackState.value = previous.copy(
            originalPlaybackQueue = queue,
            isShuffleOn = true,
        )

        startPlaybackFromQueue(shuffledQueue, 0, isNewQueue = false, localController = localController)
    }

    fun playNextInQueue(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        if (queue.isEmpty()) return

        val currentIndex = state.playbackQueueIndex ?: return
        if (currentIndex !in queue.indices) return

        val targetIndex = when {
            currentIndex < queue.lastIndex -> currentIndex + 1
            currentIndex == queue.lastIndex && state.repeatMode == RepeatMode.QUEUE -> 0
            state.repeatMode == RepeatMode.ONE -> currentIndex
            else -> return
        }

        val nextSong = queue[targetIndex]

        _playbackState.value = state.copy(
            playbackQueueIndex = targetIndex,
            currentSongId = nextSong.id,
            nowPlayingSong = nextSong,
            nowPlayingDurationMs = nextSong.durationMillis ?: state.nowPlayingDurationMs,
            nowPlayingPositionMs = 0L,
            isPlaybackPlaying = true,
            isBuffering = false,
        )
        persistPlaybackSnapshot()

        startPlaybackFromQueue(
            queue = queue,
            startIndex = targetIndex,
            isNewQueue = false,
            localController = localController,
        )
    }

    fun playPreviousInQueue(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        if (queue.isEmpty()) return

        val currentIndex = state.playbackQueueIndex ?: return
        if (currentIndex !in queue.indices) return

        val targetIndex = when {
            currentIndex > 0 -> currentIndex - 1
            currentIndex == 0 && state.repeatMode == RepeatMode.QUEUE -> queue.lastIndex
            state.repeatMode == RepeatMode.ONE -> currentIndex
            else -> return
        }

        val prevSong = queue[targetIndex]

        _playbackState.value = state.copy(
            playbackQueueIndex = targetIndex,
            currentSongId = prevSong.id,
            nowPlayingSong = prevSong,
            nowPlayingDurationMs = prevSong.durationMillis ?: state.nowPlayingDurationMs,
            nowPlayingPositionMs = 0L,
            isPlaybackPlaying = true,
            isBuffering = false,
        )
        persistPlaybackSnapshot()

        startPlaybackFromQueue(
            queue = queue,
            startIndex = targetIndex,
            isNewQueue = false,
            localController = localController,
        )
    }

    fun toggleShuffleMode(localController: MediaController?) {
        val state = _playbackState.value
        val queue = state.playbackQueue
        val index = state.playbackQueueIndex ?: return
        val current = state.nowPlayingSong ?: return

        if (queue.isEmpty() || index !in queue.indices) return

        if (!state.isShuffleOn) {
            val remaining = (queue.take(index) + queue.drop(index + 1)).shuffled()
            val newQueue = listOf(current) + remaining
            val newQueueEntities = newQueue.map { it.toQueueEntity() }

            val newState = state.copy(
                playbackQueue = newQueue,
                playbackQueueEntities = newQueueEntities,
                playbackQueueIndex = 0,
                originalPlaybackQueue = queue,
                isShuffleOn = true,
                currentSongId = current.id,
                nowPlayingSong = current,
            )
            _playbackState.value = newState
            persistPlaybackSnapshot(newState)

            if (current.sourceType == "LOCAL_FILE" || current.sourceType == "YOUTUBE_DOWNLOAD") {
                startPlaybackFromQueue(
                    queue = newQueue,
                    startIndex = 0,
                    isNewQueue = false,
                    localController = localController,
                    startPositionMs = state.nowPlayingPositionMs
                )
            }

        } else {
            if (state.originalPlaybackQueue.isEmpty()) {
                _playbackState.value = state.copy(isShuffleOn = false)
                persistPlaybackSnapshot()
                return
            }

            val restoreQueue = state.originalPlaybackQueue
            val originalIndex = restoreQueue.indexOfFirst { it.id == current.id }
                .takeIf { it >= 0 } ?: 0

            val restoredCurrent = restoreQueue[originalIndex]
            val restoreEntities = restoreQueue.map { it.toQueueEntity() }

            val newState = state.copy(
                isShuffleOn = false,
                playbackQueue = restoreQueue,
                playbackQueueEntities = restoreEntities,
                playbackQueueIndex = originalIndex,
                currentSongId = restoredCurrent.id,
                nowPlayingSong = restoredCurrent,
                nowPlayingDurationMs = restoredCurrent.durationMillis ?: state.nowPlayingDurationMs,
                nowPlayingPositionMs = 0L,
            )
            _playbackState.value = newState
            persistPlaybackSnapshot(newState)

            if (restoredCurrent.sourceType == "LOCAL_FILE" || restoredCurrent.sourceType == "YOUTUBE_DOWNLOAD") {
                startPlaybackFromQueue(
                    queue = restoreQueue,
                    startIndex = originalIndex,
                    isNewQueue = false,
                    localController = localController,
                    startPositionMs = state.nowPlayingPositionMs
                )
            }
        }
    }

    fun cycleRepeatMode(localController: MediaController?) {
        val state = _playbackState.value
        val newRepeat = when (state.repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

        _playbackState.value = state.copy(repeatMode = newRepeat)
        persistPlaybackSnapshot()

        val song = state.nowPlayingSong
        if (song?.sourceType == "LOCAL_FILE" || song?.sourceType == "YOUTUBE_DOWNLOAD") {
            localController?.let { controller ->
                controller.repeatMode = when (newRepeat) {
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF // Always OFF for Queue/Off
                }
            }
        }
    }

    fun startLocalPlaybackMonitoring(controller: MediaController) {
        localPlaybackMonitorJob?.cancel()
        localPlaybackMonitorJob = viewModelScope.launch {
            val fastIntervalMs = 200L
            val slowIntervalMs = 2000L

            while (true) {
                val state = _playbackState.value
                val queue = state.playbackQueue
                val currentSong = state.nowPlayingSong
                val isLocalFile = currentSong?.sourceType == "LOCAL_FILE" || currentSong?.sourceType == "YOUTUBE_DOWNLOAD"
                val isRadio = currentSong?.sourceType == "INTERNET_RADIO"
                var didAutoAdvance = false

                if (!isLocalFile && !isRadio) {
                    delay(slowIntervalMs)
                    continue
                }

                // Read controller state
                val isPlaying = controller.playWhenReady
                val position = controller.currentPosition
                val duration = controller.duration
                val playbackState = controller.playbackState
                // Local files buffer instantly; radio streams genuinely wait on the network.
                val isBufferingNow = isRadio && playbackState == Player.STATE_BUFFERING

                var newState = state.copy(
                    isPlaybackPlaying = isPlaying,
                    nowPlayingPositionMs = position,
                    nowPlayingDurationMs = if (duration > 0) duration else state.nowPlayingDurationMs,
                    isBuffering = isBufferingNow,
                )

                val currentMediaId = controller.currentMediaItem?.mediaId
                if (currentMediaId != null && queue.isNotEmpty()) {
                    val targetIndex = queue.indexOfFirst { it.id == currentMediaId }
                    if (targetIndex >= 0 && targetIndex != state.playbackQueueIndex) {
                        val newSong = queue[targetIndex]
                        newState = newState.copy(
                            playbackQueueIndex = targetIndex,
                            currentSongId = newSong.id,
                            nowPlayingSong = newSong,
                            nowPlayingDurationMs = newSong.durationMillis
                                ?: newState.nowPlayingDurationMs,
                            nowPlayingPositionMs = position,
                            isPlaybackPlaying = isPlaying,
                        )
                    }
                }

                // Icecast/Shoutcast servers broadcast a live "StreamTitle" as
                // ICY metadata, which Media3 automatically merges into the
                // player's MediaMetadata.title as the track changes. It is
                // conventionally formatted "Artist - Title"; split it so the
                // UI can show a real artist instead of the static placeholder
                // set when the station was first tuned in.
                if (isRadio) {
                    // ICY in-band metadata does not merge into
                    // Player/MediaController.mediaMetadata; PlaybackService
                    // captures it separately from the raw onMetadata callback.
                    val liveTitle = app.playbackStateManager.state.value.icyStreamTitle?.trim()
                    val radioSong = newState.nowPlayingSong
                    if (!liveTitle.isNullOrBlank() && radioSong != null && liveTitle != lastProcessedIcyTitle) {
                        lastProcessedIcyTitle = liveTitle
                        val parts = liveTitle.split(Regex("\\s+[-–]\\s+"), limit = 2)
                        val (parsedArtist, parsedTitle) = if (parts.size == 2) {
                            parts[0].trim() to parts[1].trim()
                        } else {
                            "" to liveTitle
                        }
                        val updatedSong = radioSong.copy(title = parsedTitle, artist = parsedArtist)
                        val idx = newState.playbackQueueIndex
                        val updatedQueue = if (idx != null && idx in newState.playbackQueue.indices) {
                            newState.playbackQueue.toMutableList().also { it[idx] = updatedSong }
                        } else {
                            newState.playbackQueue
                        }
                        newState = newState.copy(
                            nowPlayingSong = updatedSong,
                            playbackQueue = updatedQueue,
                            playbackQueueEntities = updatedQueue.map { it.toQueueEntity() },
                        )
                    }
                }

                // Handle End-of-Track / End-of-Segment Auto-Advance
                if (playbackState == Player.STATE_ENDED && currentSong != null) {
                    val songId = currentSong.id
                    if (songId != lastCompletedSongId) {
                        lastCompletedSongId = songId

                        val currentIndex = state.playbackQueueIndex

                        if (queue.isNotEmpty() && currentIndex != null && currentIndex in queue.indices) {
                            val hasNext = currentIndex < queue.lastIndex
                            val hasMultiple = queue.size > 1
                            when {
                                state.repeatMode == RepeatMode.ONE -> {
                                    didAutoAdvance = true
                                    startPlaybackFromQueue(
                                        queue = queue,
                                        startIndex = currentIndex,
                                        isNewQueue = false,
                                        localController = controller,
                                    )
                                }
                                hasNext -> {
                                    didAutoAdvance = true
                                    playNextInQueue(controller)
                                }
                                !hasNext && hasMultiple && state.repeatMode == RepeatMode.QUEUE -> {
                                    didAutoAdvance = true
                                    startPlaybackFromQueue(
                                        queue = queue,
                                        startIndex = 0,
                                        isNewQueue = false,
                                        localController = controller,
                                    )
                                }
                            }
                        }
                    }
                } else if (playbackState == Player.STATE_READY && isPlaying) {
                    lastCompletedSongId = null
                }

                if (!didAutoAdvance && newState != state) {
                    _playbackState.value = newState
                    persistPlaybackSnapshot(newState)
                }

                val nextDelayMs = when {
                    (isLocalFile || isRadio) && isPlaying -> fastIntervalMs
                    else -> slowIntervalMs
                }
                delay(nextDelayMs)
            }
        }
    }

    suspend fun resyncLocalLibrary(
        folders: Set<String>,
        onScanProgress: (Float) -> Unit,
        onIngestProgress: (Float) -> Unit,
    ): LibraryRepository.LocalResyncResult {
        val result = libraryRepository.resyncLocalLibrary(
            folders,
            onScanProgress,
            onIngestProgress
        )

        refreshLibraryFromDatabase()

        return result
    }

    fun removeSongFromLibrary(song: SongUiModel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                songDao.deleteByIds(listOf(song.id))
            }
            refreshLibraryFromDatabase()
        }
    }

    /**
     * Permanently delete a LOCAL_FILE or YOUTUBE_DOWNLOAD song, including its
     * underlying file and any playlist memberships. Returns true if the
     * database was updated successfully (file deletion best-effort).
     */
    suspend fun deleteLocalMediaSong(song: SongUiModel): Boolean {
        if (song.sourceType != "LOCAL_FILE" && song.sourceType != "YOUTUBE_DOWNLOAD") return false

        return try {
            withContext(Dispatchers.IO) {
                val uriString = song.audioUri ?: song.id
                if (uriString.isNotBlank()) {
                    try {
                        val uri = Uri.parse(uriString)
                        if (song.sourceType == "YOUTUBE_DOWNLOAD") {
                            // Downloads live under app-specific storage and are usually file:// URIs.
                            if (uri.scheme == null || uri.scheme == "file") {
                                uri.path?.let { path ->
                                    try {
                                        java.io.File(path).delete()
                                    } catch (_: Exception) {
                                    }
                                }
                            } else {
                                try {
                                    DocumentFile.fromSingleUri(app, uri)?.delete()
                                } catch (_: Exception) {
                                }
                            }
                        } else {
                            try {
                                DocumentFile.fromSingleUri(app, uri)?.delete()
                            } catch (_: Exception) {
                                if (uri.scheme == null || uri.scheme == "file") {
                                    uri.path?.let { path ->
                                        try {
                                            java.io.File(path).delete()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                // Remove from all playlists and from the songs table.
                playlistDao.deleteTracksForSongId(song.id)
                songDao.deleteByIds(listOf(song.id))
            }

            refreshLibraryFromDatabase()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun refreshLibraryFromDatabase() {
        try {
            val (allSongs, allAlbums) = withContext(Dispatchers.IO) {
                val songsFromDb = songDao.getAllSongs()
                val albumsFromDb = albumDao.getAllAlbums()
                songsFromDb to albumsFromDb
            }
            val allArtistsWithCounts = withContext(Dispatchers.IO) {
                artistDao.getAllArtistsWithCounts()
            }

            val uniqueAlbumCounts = allAlbums
                .filter { it.artistId != null }
                .groupBy { it.artistId!! }
                .mapValues { (_, albums) ->
                    albums.groupBy {
                        (it.name.lowercase().trim() to (it.artist?.lowercase()?.trim() ?: ""))
                    }.count()
                }

            val songModels = allSongs.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = com.calmapps.calmmusic.formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }

            val albumIdToYear: Map<String, Int?> = allSongs
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            val mergedAlbums = allAlbums
                .groupBy {
                    (it.name.lowercase().trim() to (it.artist?.lowercase()?.trim() ?: ""))
                }
                .map { (_, duplicates) ->
                    val primary = duplicates.find { it.sourceType == "LOCAL_FILE" } ?: duplicates.first()

                    AlbumUiModel(
                        id = primary.id,
                        title = primary.name,
                        artist = primary.artist,
                        sourceType = primary.sourceType,
                        releaseYear = albumIdToYear[primary.id],
                    )
                }

            val mergedArtists = mergeArtistsByName(allArtistsWithCounts, uniqueAlbumCounts)

            updateLibrary(
                songs = songModels,
                albums = mergedAlbums,
                artists = mergedArtists,
            )
        } catch (_: Exception) {
        }
    }

    fun updateLibrary(
        songs: List<SongUiModel>,
        albums: List<AlbumUiModel>,
        artists: List<ArtistUiModel>,
    ) {
        _librarySongs.value = songs
        _libraryAlbums.value = albums
        _libraryArtists.value = artists
        _libraryRefreshTrigger.value += 1
    }

    private fun mergeArtistsByName(
        allArtistsWithCounts: List<ArtistWithCounts>,
        uniqueAlbumCounts: Map<String, Int>,
    ): List<ArtistUiModel> {
        fun normalizeName(name: String): String =
            name.trim().replace(Regex("\\s+"), " ").lowercase()

        return allArtistsWithCounts
            .groupBy { normalizeName(it.name) }
            .values
            .map { group ->
                val primary = group.find { it.sourceType == "LOCAL_FILE" }
                    ?: group.find { it.sourceType == "YOUTUBE_DOWNLOAD" }
                    ?: group.first()

                val totalSongCount = group.sumOf { it.songCount }
                val totalAlbumCount = group.sumOf { artist -> uniqueAlbumCounts[artist.id] ?: 0 }

                ArtistUiModel(
                    id = primary.id,
                    name = primary.name,
                    songCount = totalSongCount,
                    albumCount = totalAlbumCount,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override fun onCleared() {
        super.onCleared()
        localPlaybackMonitorJob?.cancel()
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                libraryRepository.ingestAppDownloadsIfMissing()
            }

            val allSongs = withContext(Dispatchers.IO) { songDao.getAllSongs() }
            val allAlbums = withContext(Dispatchers.IO) { albumDao.getAllAlbums() }
            val allArtistsWithCounts = withContext(Dispatchers.IO) { artistDao.getAllArtistsWithCounts() }
            val allPlaylistsWithCounts = withContext(Dispatchers.IO) { playlistDao.getAllPlaylistsWithSongCount() }

            val uniqueAlbumCounts = allAlbums
                .filter { it.artistId != null }
                .groupBy { it.artistId!! }
                .mapValues { (_, albums) ->
                    albums.groupBy {
                        (it.name.lowercase().trim() to (it.artist?.lowercase()?.trim() ?: ""))
                    }.count()
                }

            _librarySongs.value = allSongs.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }
            val albumIdToYear: Map<String, Int?> = allSongs
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            val mergedAlbums = allAlbums
                .groupBy {
                    (it.name.lowercase().trim() to (it.artist?.lowercase()?.trim() ?: ""))
                }
                .map { (_, duplicates) ->
                    val primary = duplicates.find { it.sourceType == "LOCAL_FILE" } ?: duplicates.first()

                    AlbumUiModel(
                        id = primary.id,
                        title = primary.name,
                        artist = primary.artist,
                        sourceType = primary.sourceType,
                        releaseYear = albumIdToYear[primary.id],
                    )
                }

            _libraryAlbums.value = mergedAlbums

            _libraryArtists.value = mergeArtistsByName(allArtistsWithCounts, uniqueAlbumCounts)
            _libraryPlaylists.value = allPlaylistsWithCounts.map { playlist ->
                PlaylistUiModel(
                    id = playlist.id,
                    name = playlist.name,
                    description = playlist.description,
                    songCount = playlist.songCount,
                )
            }

            val snapshot = withContext(Dispatchers.IO) { nowPlayingStorage.load() }
            if (snapshot != null) {
                val songsById = allSongs.associateBy { it.id }
                val queueEntities = snapshot.queueSongIds.mapNotNull { songsById[it] }
                if (queueEntities.isNotEmpty()) {
                    val playbackQueue = queueEntities.map { entity ->
                        SongUiModel(
                            id = entity.id,
                            title = entity.title,
                            artist = entity.artist,
                            durationText = formatDurationMillis(entity.durationMillis),
                            durationMillis = entity.durationMillis,
                            trackNumber = entity.trackNumber,
                            sourceType = entity.sourceType,
                            audioUri = entity.audioUri,
                            album = entity.album,
                        )
                    }

                    val indexFromSnapshot = snapshot.currentIndex
                    val effectiveIndex = indexFromSnapshot?.takeIf { it in playbackQueue.indices } ?: 0
                    val currentSong = playbackQueue[effectiveIndex]

                    val repeatMode = when (snapshot.repeatModeKey) {
                        NowPlayingRepeatModeKeys.QUEUE -> RepeatMode.QUEUE
                        NowPlayingRepeatModeKeys.ONE -> RepeatMode.ONE
                        else -> RepeatMode.OFF
                    }

                    val playbackQueueEntities = playbackQueue.map { it.toQueueEntity() }

                    _playbackState.value = PlaybackState(
                        playbackQueue = playbackQueue,
                        playbackQueueEntities = playbackQueueEntities,
                        playbackQueueIndex = effectiveIndex,
                        originalPlaybackQueue = if (snapshot.isShuffleOn) playbackQueue else emptyList(),
                        repeatMode = repeatMode,
                        isShuffleOn = snapshot.isShuffleOn,
                        currentSongId = currentSong.id,
                        nowPlayingSong = currentSong,
                        isPlaybackPlaying = snapshot.isPlaying,
                        nowPlayingPositionMs = snapshot.positionMs,
                        nowPlayingDurationMs = currentSong.durationMillis ?: 0L,
                    )
                }
            }

            _isLoadingSongs.value = false
            _isLoadingAlbums.value = false
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CalmMusicViewModel::class.java)) {
                        return CalmMusicViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
                }
            }
    }
}

data class PlaybackState(
    val playbackQueue: List<SongUiModel> = emptyList(),
    val playbackQueueEntities: List<SongEntity> = emptyList(),
    val playbackQueueIndex: Int? = null,
    val originalPlaybackQueue: List<SongUiModel> = emptyList(),
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffleOn: Boolean = false,
    val currentSongId: String? = null,
    val nowPlayingSong: SongUiModel? = null,
    val isPlaybackPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val nowPlayingPositionMs: Long = 0L,
    val nowPlayingDurationMs: Long = 0L,
)