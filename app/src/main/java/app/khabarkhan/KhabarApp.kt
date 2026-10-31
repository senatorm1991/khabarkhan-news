package app.khabarkhan

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

fun currentProcessName(ctx: Context): String {
    val pid = Process.myPid()
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ctx.packageName
}

class KhabarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isTor = try { currentProcessName(this).endsWith(":tor") } catch (e: Throwable) { false }
        val crashFile = File(filesDir, if (isTor) "tor_crash.txt" else "app_crash.txt")

        // Crash-loop guard: if the app crashed less than a minute ago, fall back to direct mode.
        if (!isTor) {
            try {
                if (crashFile.exists()) {
                    val ts = crashFile.readLines().firstOrNull()?.toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - ts < 60_000) {
                        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("mode", "DIRECT").apply()
                    }
                }
            } catch (e: Throwable) {
            }
        }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile.writeText(System.currentTimeMillis().toString() + "\n" + Log.getStackTraceString(e).take(2500))
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
