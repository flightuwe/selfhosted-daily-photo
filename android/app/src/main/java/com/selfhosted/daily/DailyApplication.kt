package com.selfhosted.daily

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DailyApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("daily_media_cache"))
                .maxSizePercent(0.15)
                .build()
        }
        .okHttpClient {
            buildStandardHttpClient(applicationContext, usageContext = "media")
        }
        .respectCacheHeaders(true)
        .build()
}
