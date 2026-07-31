package com.calmapps.calmmusic.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileOutputStream

/**
 * Wrapper for a scanned song that includes metadata not directly stored in [SongEntity],
 * such as the explicit [albumArtist] string. This is crucial for correctly populating
 * [AlbumEntity] rows (e.g. preserving "Various Artists" display text).
 */
data class ScannedLocalAudio(
    val song: SongEntity,
    val albumArtist: String?,
)

object LocalMusicScanner {
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "mp4", "opus")

    /**
     * Scan the given folders for audio files.
     */
    suspend fun scanFolders(
        context: Context,
        folderUris: Set<String>,
        existingSongsByUri: Map<String, SongEntity> = emptyMap(),
        lastScanMillis: Long = 0L,
        onDiscoveryProgress: suspend (foldersVisited: Int, filesFound: Int) -> Unit = { _, _ -> },
        onProgress: suspend (processed: Int, total: Int, currentFileName: String) -> Unit = { _, _, _ -> },
    ): List<ScannedLocalAudio> {
        val result = mutableListOf<ScannedLocalAudio>()

        data class Candidate(
            val uri: Uri,
            val name: String,
            val lastModified: Long,
            val fileSize: Long,
            val existing: SongEntity?,
        )

        val candidates = mutableListOf<Candidate>()
        var foldersVisited = 0
        var lastDiscoveryUpdateTime = 0L

        for (uriString in folderUris) {
            val treeUri = try {
                uriString.toUri()
            } catch (_: Exception) {
                continue
            }
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            val stack = ArrayDeque<DocumentFile>()
            stack.add(root)

            while (stack.isNotEmpty()) {
                val dir = stack.removeFirst()
                foldersVisited++
                val children = try {
                    dir.listFiles().toList()
                } catch (_: Exception) {
                    emptyList()
                }

                for (child in children) {
                    if (child.isDirectory) {
                        stack.add(child)
                    } else if (child.isFile) {
                        val name = child.name ?: continue
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in AUDIO_EXTENSIONS) {
                            val uri = child.uri
                            val uriString = uri.toString()
                            val lastModified = child.lastModified()
                            val fileSize = child.length()

                            val existing = existingSongsByUri[uriString]
                            candidates.add(
                                Candidate(
                                    uri = uri,
                                    name = name,
                                    lastModified = lastModified,
                                    fileSize = fileSize,
                                    existing = existing,
                                ),
                            )
                        }
                    }
                }

                // The folder walk itself (SAF listFiles() calls) can take a
                // long time on a large SD card before a single audio file
                // is even found, so report progress here too -- otherwise
                // the UI has nothing to show and looks identical to a hang.
                val now = System.currentTimeMillis()
                if (now - lastDiscoveryUpdateTime > 200L) {
                    lastDiscoveryUpdateTime = now
                    onDiscoveryProgress(foldersVisited, candidates.size)
                }
            }
        }
        onDiscoveryProgress(foldersVisited, candidates.size)

        if (candidates.isEmpty()) {
            onProgress(0, 0, "")
            return emptyList()
        }

        val (unchanged, changed) = candidates.partition { candidate ->
            val existing = candidate.existing
            existing != null &&
                    existing.sourceType == "LOCAL_FILE" &&
                    existing.localLastModifiedMillis == candidate.lastModified &&
                    existing.localFileSizeBytes == candidate.fileSize
        }

        val (recentChanged, olderChanged) = changed.partition { candidate ->
            candidate.lastModified > lastScanMillis
        }

        val orderedCandidates =
            (recentChanged.sortedByDescending { it.lastModified } +
                    olderChanged.sortedByDescending { it.lastModified } +
                    unchanged)

        val total = orderedCandidates.size
        var processed = 0
        var lastProgressUpdateTime = 0L

        suspend fun maybeReportProgress(currentFileName: String) {
            val now = System.currentTimeMillis()
            if (processed == total || now - lastProgressUpdateTime > 200L) {
                lastProgressUpdateTime = now
                onProgress(processed, total, currentFileName)
            }
        }

        for (candidate in orderedCandidates) {
            val existing = candidate.existing
            if (existing != null &&
                existing.sourceType == "LOCAL_FILE" &&
                existing.localLastModifiedMillis == candidate.lastModified &&
                existing.localFileSizeBytes == candidate.fileSize
            ) {
                result.add(ScannedLocalAudio(existing, null))
                processed++
                maybeReportProgress(candidate.name)
                continue
            }

            // Reported before the (potentially slow, now timeout-bounded)
            // metadata read below, so a file that's taking a while still
            // shows as "currently on this one" instead of looking stuck.
            onProgress(processed, total, candidate.name)

            val scanned = buildSongEntityFromFile(
                context = context,
                uri = candidate.uri,
                name = candidate.name,
                lastModified = candidate.lastModified,
                fileSize = candidate.fileSize,
                existing = existing,
            )

            result.add(scanned)

            processed++
            maybeReportProgress(candidate.name)
        }

        onProgress(processed, total.coerceAtLeast(processed), "")

        return result
    }

    suspend fun buildSongEntityFromFile(
        context: Context,
        uri: Uri,
        name: String,
        lastModified: Long,
        fileSize: Long,
        existing: SongEntity? = null,
    ): ScannedLocalAudio {
        val meta = extractMetadata(context, uri)
        val titleFromName = name.substringBeforeLast('.', name)

        val existingArtist = existing?.artist?.takeIf { it.isNotBlank() }
        val existingAlbum = existing?.album?.takeIf { it.isNotBlank() }
        val existingArtistId = existing?.artistId
        val existingAlbumId = existing?.albumId

        val explicitAlbumArtist = meta.albumArtist?.trim()?.takeIf { it.isNotBlank() }

        val baseArtistCandidate = meta.artist?.takeIf { it.isNotBlank() }
            ?: existingArtist
            ?: explicitAlbumArtist

        val trackArtistDisplay = baseArtistCandidate?.trim().orEmpty()
        val trackArtistIdComponent = trackArtistDisplay.takeIf { it.isNotBlank() }?.normalizeForIdComponent()

        val artistId = trackArtistIdComponent?.let { "LOCAL_FILE:$it" } ?: existingArtistId

        val effectiveAlbumArtist = explicitAlbumArtist ?: trackArtistDisplay
        val albumArtistIdComponent = effectiveAlbumArtist.takeIf { it.isNotBlank() }?.normalizeForIdComponent()

        val albumNameDisplay = meta.album?.trim()?.takeIf { it.isNotBlank() } ?: existingAlbum
        val albumNameIdComponent = albumNameDisplay?.normalizeForIdComponent()

        val albumId = if (albumNameIdComponent != null && albumArtistIdComponent != null) {
            "LOCAL_FILE:${albumArtistIdComponent}:${albumNameIdComponent}"
        } else existingAlbumId

        val uriString = uri.toString()
        val song = SongEntity(
            id = uriString,
            title = meta.title ?: existing?.title ?: titleFromName,
            artist = trackArtistDisplay,
            album = albumNameDisplay,
            albumId = albumId,
            discNumber = meta.discNumber ?: existing?.discNumber,
            trackNumber = meta.trackNumber ?: existing?.trackNumber,
            durationMillis = meta.durationMillis ?: existing?.durationMillis,
            sourceType = "LOCAL_FILE",
            audioUri = uriString,
            artistId = artistId,
            releaseYear = meta.year ?: existing?.releaseYear,
            localLastModifiedMillis = lastModified,
            localFileSizeBytes = fileSize,
        )

        return ScannedLocalAudio(song, explicitAlbumArtist)
    }

    private data class LocalMetadata(
        val title: String?,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val discNumber: Int?,
        val trackNumber: Int?,
        val durationMillis: Long?,
        val year: Int?,
    )

    // MediaMetadataRetriever.setDataSource and jaudiotagger's file read are
    // plain blocking calls with no cancellation support; on a corrupt/odd
    // file either one can hang indefinitely with no exception thrown, which
    // would otherwise stall the whole scan forever on a single bad file.
    // withTimeoutOrNull can't interrupt a raw blocking call directly, but
    // dispatching it onto Dispatchers.IO decouples the wait from the block:
    // on timeout this suspends past it and the scan moves on, leaving at
    // worst an orphaned IO-pool thread for that one file instead of a
    // permanently stuck scan.
    private const val METADATA_TIMEOUT_MILLIS = 10_000L

    // Tag data (ID3v2 headers, Vorbis comments, embedded cover art) lives
    // near the start of the file; copying the full file (tens of MB for a
    // FLAC track) just to read a few KB of tags made every fallback-tagged
    // file dramatically slower than it needed to be.
    private const val TAG_PROBE_MAX_BYTES = 5L * 1024 * 1024

    private suspend fun extractMetadata(context: Context, uri: Uri): LocalMetadata {
        var meta = withTimeoutOrNull(METADATA_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)

                    val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    val rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)

                    val discStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    val trackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)

                    LocalMetadata(
                        title = rawTitle.normalizeTagString(),
                        artist = rawArtist.normalizeTagString(),
                        albumArtist = rawAlbumArtist.normalizeTagString(),
                        album = rawAlbum.normalizeTagString(),
                        discNumber = discStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                        trackNumber = trackStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                        durationMillis = durationStr?.toLongOrNull(),
                        year = yearStr?.take(4)?.trim()?.toIntOrNull(),
                    )
                } catch (_: Exception) {
                    LocalMetadata(null, null, null, null, null, null, null, null)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }
        } ?: LocalMetadata(null, null, null, null, null, null, null, null)

        if (meta.albumArtist.isNullOrBlank()) {
            val deepMeta = withTimeoutOrNull(METADATA_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                    val tempFile = copyUriToTempFile(context, uri, TAG_PROBE_MAX_BYTES)
                    if (tempFile != null) {
                        try {
                            TagOptionSingleton.getInstance().isAndroid = true

                            val audioFile = AudioFileIO.read(tempFile)
                            audioFile.tag
                        } catch (_: Exception) {
                            // Ignore deep scan failures (including a
                            // truncated-tag parse from the size cap above).
                            null
                        } finally {
                            tempFile.delete()
                        }
                    } else {
                        null
                    }
                }
            }

            val tag = deepMeta
            if (tag != null) {
                val deepAlbumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST)

                if (!deepAlbumArtist.isNullOrBlank()) {
                    meta = meta.copy(albumArtist = deepAlbumArtist.normalizeTagString())
                }

                if (meta.artist.isNullOrBlank()) {
                    meta = meta.copy(artist = tag.getFirst(FieldKey.ARTIST).normalizeTagString())
                }
                if (meta.album.isNullOrBlank()) {
                    meta = meta.copy(album = tag.getFirst(FieldKey.ALBUM).normalizeTagString())
                }
            }
        }

        return meta
    }

    private fun copyUriToTempFile(context: Context, uri: Uri, maxBytes: Long): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("scanner_probe", ".tmp", context.cacheDir)
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output, maxBytes)
            }
            inputStream.close()
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    /** Like [java.io.InputStream.copyTo], but stops after [limit] bytes. */
    private fun java.io.InputStream.copyTo(out: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = this.read(buffer, 0, toRead)
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }
}

private fun String?.normalizeTagString(): String? {
    if (this == null) return null
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    return trimmed.fixCommonTagMojibake()
}

private fun String.fixCommonTagMojibake(): String {
    var fixed = this
    val replacements = mapOf(
        "â€™" to "’",
        "â€˜" to "‘",
        "â€œ" to "“",
        "â€ " to "”",
        "â€“" to "–",
        "â€”" to "—",
    )
    for ((bad, good) in replacements) {
        if (fixed.contains(bad)) {
            fixed = fixed.replace(bad, good)
        }
    }
    return fixed
}

private fun String.normalizeForIdComponent(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .lowercase()