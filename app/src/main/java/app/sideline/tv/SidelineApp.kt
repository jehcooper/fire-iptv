package app.sideline.tv

import android.app.Application
import app.sideline.tv.data.Prefs
import app.sideline.tv.data.Repository
import app.sideline.tv.sports.ScoreWatcher

class SidelineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = Repository(prefs)
        scores = ScoreWatcher(prefs)
        if (prefs.accounts.value.isNotEmpty()) repo.refresh()
        scores.start()
    }

    companion object {
        lateinit var prefs: Prefs
        lateinit var repo: Repository
        lateinit var scores: ScoreWatcher
    }
}
