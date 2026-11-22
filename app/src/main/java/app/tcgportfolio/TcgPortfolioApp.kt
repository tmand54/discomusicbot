package app.tcgportfolio

import android.app.Application
import app.tcgportfolio.work.Notifications
import app.tcgportfolio.work.PriceRefreshWorker
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class TcgPortfolioApp : Application(), SingletonImageLoader.Factory {
    // Coil keeps its own disk cache, so images skip the JSON HTTP cache.
    private val imageClient by lazy { AppGraph.instance.http.client.newBuilder().cache(null).build() }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.createChannels(this)
        PriceRefreshWorker.schedule(this, AppGraph.instance.settings)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}
