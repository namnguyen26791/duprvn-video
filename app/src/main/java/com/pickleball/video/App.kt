package asia.pickbase.video

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global crash handler — log to logcat
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PB_VIDEO_CRASH", "Uncaught exception on ${thread.name}", throwable)
            // Re-throw to let Android handle it
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e("PB_VIDEO", "Firebase init failed", e)
        }
    }
}

