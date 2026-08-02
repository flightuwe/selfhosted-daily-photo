package com.selfhosted.daily

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DailyApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val mediaCacheDir = cacheDir.resolve("daily_media_cache")
        val cachePrefs = getSharedPreferences("daily_media_cache_policy", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - cachePrefs.getLong("last_reset_at", 0L) >= MEDIA_CACHE_RETENTION_MS) {
            runCatching { mediaCacheDir.deleteRecursively() }
            cachePrefs.edit().putLong("last_reset_at", now).commit()
        }
        return ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(mediaCacheDir)
                .maxSizePercent(0.15)
                .build()
        }
        .okHttpClient {
            buildStandardHttpClient(applicationContext, usageContext = "media")
        }
        .respectCacheHeaders(true)
            .build()
    }

    private companion object {
        const val MEDIA_CACHE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
