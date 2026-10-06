package com.minedie.keepalive.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        peek(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            peek(packageName)?.let { return@withContext it }
            val drawable = runCatching {
                context.applicationContext.packageManager.getApplicationIcon(packageName)
            }.getOrNull() ?: return@withContext null
            val image = drawable.toBitmap(128, 128).asImageBitmap()
            cache.put(packageName, image)
            image
        }
    }
}
