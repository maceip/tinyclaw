package ai.musicconverter.data

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicFileScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun scanForMusicFiles(includeConverted: Boolean = false): List<MusicFile> = withContext(Dispatchers.IO) {
        val musicFiles = mutableListOf<MusicFile>()

        // Try MediaStore first for better performance
        try {
            musicFiles.addAll(scanWithMediaStore())
        } catch (e: Exception) {
            Timber.e(e, "MediaStore scan failed, falling back to file system scan")
        }

        // If MediaStore didn't find files that need conversion, scan file system
        if (musicFiles.none { it.needsConversion }) {
            try {
                musicFiles.addAll(scanFileSystem())
            } catch (e: Exception) {
                Timber.e(e, "File system scan failed")
            }
        }

        // Remove duplicates and sort by last modified (most recent first)
        musicFiles
            .distinctBy { it.path }
            .let { list -> if (includeConverted) list else list.filter { it.needsConversion } }
            .sortedByDescending { it.lastModified }
    }

    private fun scanWithMediaStore(): List<MusicFile> {
        val musicFiles = mutableListOf<MusicFile>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            // Count total tracks per album path for "X of Y" display
            val totalFiles = mutableListOf<Triple<String, Long, Int?>>() // path, duration, track

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val path = cursor.getString(dataColumn) ?: continue
                val size = cursor.getLong(sizeColumn)
                val lastModified = cursor.getLong(dateColumn) * 1000
                val duration = cursor.getLong(durationColumn)
                val artist = cursor.getString(artistColumn)?.takeIf { it != "<unknown>" }
                val album = cursor.getString(albumColumn)?.takeIf { it != "<unknown>" }
                val albumId = cursor.getLong(albumIdColumn).takeIf { it > 0 }
                val trackRaw = cursor.getInt(trackColumn)
                val trackNumber = if (trackRaw > 0) trackRaw % 1000 else null

                val file = File(path)
                if (!file.exists()) continue

                val ext = file.extension.lowercase()
                if (ext !in MusicFile.SUPPORTED_EXTENSIONS) continue

                musicFiles.add(
                    MusicFile(
                        id = id.toString(),
                        name = file.nameWithoutExtension,
                        path = path,
                        extension = ext,
                        size = size,
                        lastModified = lastModified,
                        duration = duration,
                        artist = artist,
                        album = album,
                        albumId = albumId,
                        trackNumber = trackNumber
                    )
                )
            }
        }

        return musicFiles
    }

    private fun scanFileSystem(): List<MusicFile> {
        val musicFiles = mutableListOf<MusicFile>()
        val directories = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStorageDirectory()
        )

        for (dir in directories) {
            if (dir.exists() && dir.isDirectory) {
                scanDirectory(dir, musicFiles)
            }
        }

        return musicFiles
    }

    private fun scanDirectory(directory: File, results: MutableList<MusicFile>, depth: Int = 0) {
        if (depth > 5) return // Limit recursion depth

        try {
            directory.listFiles()?.forEach { file ->
                if (file.isDirectory && !file.name.startsWith(".")) {
                    scanDirectory(file, results, depth + 1)
                } else if (file.isFile) {
                    MusicFile.fromFile(file)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error scanning directory: ${directory.absolutePath}")
        }
    }
}
