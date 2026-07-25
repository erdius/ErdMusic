package com.calmapps.calmmusic.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader

/**
 * Imports M3U/M3U8 playlist files found inside the user's local music folders.
 *
 * Entries are matched to indexed songs by file name (case-insensitive), since
 * absolute paths written by desktop players will not match Android SAF URIs.
 * Imported playlists use a deterministic "M3U:<name>" id so a rescan updates
 * the same playlist in place instead of creating duplicates, and never
 * collides with playlists created in-app (which use random UUID ids).
 */
object M3uPlaylistImporter {

    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

    data class ImportStats(
        val playlistsImported: Int,
        val tracksMatched: Int,
        val tracksUnmatched: Int,
    )

    suspend fun importFromFolders(
        context: Context,
        folderUris: Set<String>,
        songDao: SongDao,
        playlistDao: PlaylistDao,
    ): ImportStats {
        val playlistFiles = findPlaylistFiles(context, folderUris)
        if (playlistFiles.isEmpty()) return ImportStats(0, 0, 0)

        // Index all local songs by their decoded file name for matching.
        val localSongs = songDao.getSongsBySourceType("LOCAL_FILE") +
                songDao.getSongsBySourceType("YOUTUBE_DOWNLOAD")
        val songsByFileName = HashMap<String, SongEntity>()
        val songsByTitle = HashMap<String, SongEntity>()
        for (song in localSongs) {
            val fileName = fileNameFromUriString(song.audioUri)
            if (fileName != null) {
                songsByFileName.putIfAbsent(fileName, song)
            }
            val titleKey = song.title.normalizeForMatch()
            if (titleKey.isNotEmpty()) {
                songsByTitle.putIfAbsent(titleKey, song)
            }
        }

        var playlistsImported = 0
        var matched = 0
        var unmatched = 0

        for (file in playlistFiles) {
            val rawName = file.name ?: continue
            val playlistName = rawName.substringBeforeLast('.')
            if (playlistName.isBlank()) continue

            val entries = try {
                readEntries(context, file.uri)
            } catch (_: Exception) {
                continue
            }
            if (entries.isEmpty()) continue

            val matchedSongs = mutableListOf<SongEntity>()
            for (entry in entries) {
                val fileName = fileNameFromPath(entry)?.lowercase()
                val byFile = fileName?.let { songsByFileName[it] }
                val song = byFile ?: run {
                    val titleKey = fileName
                        ?.substringBeforeLast('.')
                        ?.normalizeForMatch()
                    titleKey?.takeIf { it.isNotEmpty() }?.let { songsByTitle[it] }
                }
                if (song != null) {
                    matchedSongs += song
                    matched++
                } else {
                    unmatched++
                }
            }

            if (matchedSongs.isEmpty()) continue

            val playlistId = "M3U:" + playlistName.normalizeForMatch()
            val existing = playlistDao.getAllPlaylists().firstOrNull { it.id == playlistId }
            if (existing == null) {
                playlistDao.upsertPlaylist(
                    PlaylistEntity(
                        id = playlistId,
                        name = playlistName,
                        description = "Imported from $rawName",
                    )
                )
            } else {
                playlistDao.updatePlaylistMetadata(
                    id = playlistId,
                    name = playlistName,
                    description = "Imported from $rawName",
                )
            }

            playlistDao.deleteTracksForPlaylist(playlistId)
            playlistDao.upsertTracks(
                matchedSongs.distinctBy { it.id }.mapIndexed { index, song ->
                    PlaylistTrackEntity(
                        playlistId = playlistId,
                        songId = song.id,
                        position = index,
                    )
                }
            )
            playlistsImported++
        }

        return ImportStats(playlistsImported, matched, unmatched)
    }

    private fun findPlaylistFiles(
        context: Context,
        folderUris: Set<String>,
    ): List<DocumentFile> {
        val found = mutableListOf<DocumentFile>()
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
                        if (ext in PLAYLIST_EXTENSIONS) {
                            found += child
                        }
                    }
                }
            }
        }
        return found
    }

    private fun readEntries(context: Context, uri: Uri): List<String> {
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        return input.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
            reader.readLines()
                .map { it.trim().removePrefix("﻿") }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    /** Extract the file name from an M3U entry path (handles / and \ separators and URLs). */
    private fun fileNameFromPath(entry: String): String? {
        val normalized = entry.replace('\\', '/')
        val last = normalized.substringAfterLast('/')
        val decoded = try {
            Uri.decode(last)
        } catch (_: Exception) {
            last
        }
        return decoded.takeIf { it.isNotBlank() }
    }

    /** Extract the decoded file name from a stored SAF/file URI string, lowercased. */
    private fun fileNameFromUriString(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        val decoded = try {
            Uri.decode(uriString)
        } catch (_: Exception) {
            uriString
        }
        // SAF document ids look like ".../document/primary:Music/Foo/bar.mp3";
        // take the segment after the last '/' or ':'.
        val afterSlash = decoded.substringAfterLast('/')
        val name = afterSlash.substringAfterLast(':')
        return name.takeIf { it.isNotBlank() }?.lowercase()
    }

    private fun String.normalizeForMatch(): String =
        trim().replace(Regex("\\s+"), " ").lowercase()
}
