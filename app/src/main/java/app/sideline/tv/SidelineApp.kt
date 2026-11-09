package app.sideline.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.sideline.tv.data.Net
import app.sideline.tv.data.Prefs
import app.sideline.tv.data.Repository
import app.sideline.tv.data.WatchProgress
import app.sideline.tv.sports.ScoreWatcher
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class SidelineApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.cacheDir = filesDir.resolve("xtream")
        prefs = Prefs(this)
        app.sideline.tv.data.Omdb.init(this)
        repo = Repository(prefs)
        prefs.onChannelEdit = { repo.applyEdits() }
        scores = ScoreWatcher(prefs)
        progress = WatchProgress(this)
        reminders = app.sideline.tv.data.Reminders(this).also { it.start() }
        if (prefs.accounts.value.isNotEmpty()) repo.refresh()
        scores.start()

        // Track whether any of our screens is showing, so score polling pauses when the app is in the background.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { started++; foreground = true }
            override fun onActivityStopped(a: Activity) { started--; foreground = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Images (logos, posters): kept on the stick between launches (150 MB) and decoded at half the memory
     * per pixel, which matters on a Fire TV stick with lots of posters on screen.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .allowRgb565(true)
        .crossfade(false)
        .build()

    companion object {
        lateinit var prefs: Prefs
        lateinit var repo: Repository
        lateinit var scores: ScoreWatcher
        lateinit var progress: WatchProgress
        lateinit var reminders: app.sideline.tv.data.Reminders
        @Volatile var foreground: Boolean = false
    }
}
