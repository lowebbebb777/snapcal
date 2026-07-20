package com.tazawa.snapcal.capture

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class ScreenshotObserver(
    private val contentResolver: ContentResolver,
    private val onScreenshot: (Uri, Long) -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var lastDelivered: String? = null

    fun start() {
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
    }

    fun stop() {
        runCatching { contentResolver.unregisterContentObserver(this) }
        scope.cancel()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        scope.launch {
            val item = newestImage(uri) ?: return@launch
            coroutineContext.ensureActive()
            val key = item.first.toString()
            if (lastDelivered == key) return@launch
            lastDelivered = key
            onScreenshot(item.first, item.second)
        }
    }

    private fun newestImage(changedUri: Uri?): Pair<Uri, Long>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val queryUri = changedUri?.takeIf { it != collection } ?: collection
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING,
        )
        val queryArgs = if (queryUri == collection) {
            Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
            }
        } else {
            null
        }

        return try {
            contentResolver.query(queryUri, projection, queryArgs, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty()
                val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)).orEmpty()
                val addedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) != 0
                if (isPending || abs(System.currentTimeMillis() - addedSeconds * 1_000L) > NEW_IMAGE_WINDOW_MILLIS) {
                    return@use null
                }
                if (!looksLikeScreenshot(name, relativePath)) return@use null
                Uri.withAppendedPath(collection, id.toString()) to addedSeconds * 1_000L
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    internal fun looksLikeScreenshot(name: String, relativePath: String): Boolean {
        val marker = "$name/$relativePath".lowercase(Locale.ROOT)
        return marker.contains("screenshot") || marker.contains("screenshots") || marker.contains("スクリーンショット")
    }

    private companion object {
        const val NEW_IMAGE_WINDOW_MILLIS = 2L * 60L * 1_000L
    }
}
