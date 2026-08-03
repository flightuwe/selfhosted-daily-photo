package com.selfhosted.daily

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DailyApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // A process-scoped session keeps diagnostics from separate launches apart.
        NetworkUsageLedger.beginSession(this)
    }

    override fun newImageLoader(): ImageLoader {
        val mediaCacheDir = cacheDir.resolve("daily_media_cache")
        val cacheBudgetBytes = (cacheDir.usableSpace / 20L).coerceIn(MIN_MEDIA_CACHE_BYTES, MAX_MEDIA_CACHE_BYTES)
        return ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(mediaCacheDir)
                .maxSizeBytes(cacheBudgetBytes)
                .build()
        }
        .okHttpClient {
            buildStandardHttpClient(applicationContext, usageContext = "media")
        }
        .respectCacheHeaders(true)
            .build()
    }

    private companion object {
        const val MIN_MEDIA_CACHE_BYTES = 128L * 1024L * 1024L
        const val MAX_MEDIA_CACHE_BYTES = 512L * 1024L * 1024L
    }
}
