package ai.musicconverter.data

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicFileScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Cache previous scan results keyed by path for fast deduplication
    @Volatile
    private var cachedPaths: Set<String> = emptySet()

    suspend fun scanForMusicFiles(includeConverted: Boolean = false): List<MusicFile> = withContext(Dispatchers.IO) {
        val musicFiles = mutableListOf<MusicFile>()

        // Try MediaStore first for better performance
        try {
            musicFiles.addAll(scanWithMediaStore())
        } catch (e: Exception) {
            Timber.e(e, "MediaStore scan failed, falling back to file system scan")
        }

        // If MediaStore didn't find files that need conversion, scan file system
        // using parallel directory scanning for speed
        if (musicFiles.none { it.needsConversion }) {
            try {
                musicFiles.addAll(scanFileSystemParallel())
            } catch (e: Exception) {
                Timber.e(e, "File system scan failed")
            }
        }

        // Build deduplicated result and update cache
        val result = musicFiles
            .distinctBy { it.path }
            .let { list -> if (includeConverted) list else list.filter { it.needsConversion } }
            .sortedByDescending { it.lastModified }

        cachedPaths = result.mapTo(HashSet()) { it.path }
        result
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

            // Pre-build the set of supported extensions for O(1) lookup
            val supported = MusicFile.SUPPORTED_EXTENSIONS.toHashSet()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val path = cursor.getString(dataColumn) ?: continue

                // Quick extension check before expensive File.exists()
                val dotIdx = path.lastIndexOf('.')
                if (dotIdx < 0) continue
                val ext = path.substring(dotIdx + 1).lowercase()
                if (ext !in supported) continue

                // Skip file existence check if path was in the previous cache
                // (the file almost certainly still exists)
                if (path !in cachedPaths) {
                    val file = File(path)
                    if (!file.exists()) continue
                }

                val size = cursor.getLong(sizeColumn)
                val lastModified = cursor.getLong(dateColumn) * 1000
                val duration = cursor.getLong(durationColumn)
                val artist = cursor.getString(artistColumn)?.takeIf { it != "<unknown>" }
                val album = cursor.getString(albumColumn)?.takeIf { it != "<unknown>" }
                val albumId = cursor.getLong(albumIdColumn).takeIf { it > 0 }
                val trackRaw = cursor.getInt(trackColumn)
                val trackNumber = if (trackRaw > 0) trackRaw % 1000 else null

                musicFiles.add(
                    MusicFile(
                        id = id.toString(),
                        name = File(path).nameWithoutExtension,
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

    /**
     * Scans the file system using parallel coroutines — one per top-level directory.
     * Significantly faster on devices with many files across Music/Downloads/Podcasts.
     */
    private suspend fun scanFileSystemParallel(): List<MusicFile> = coroutineScope {
        val directories = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStorageDirectory()
        ).filter { it.exists() && it.isDirectory }

        val results = ConcurrentLinkedQueue<MusicFile>()

        directories.map { dir ->
            async(Dispatchers.IO) {
                scanDirectory(dir, results)
            }
        }.awaitAll()

        results.toList()
    }

    private fun scanDirectory(directory: File, results: ConcurrentLinkedQueue<MusicFile>, depth: Int = 0) {
        if (depth > 5) return // Limit recursion depth

        try {
            val files = directory.listFiles() ?: return
            for (file in files) {
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
