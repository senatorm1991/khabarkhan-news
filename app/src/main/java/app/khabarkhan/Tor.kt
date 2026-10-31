package app.khabarkhan

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.torproject.jni.TorService
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Tor runs in its own process (":tor") so that a crash inside the native Tor
 * library can never take the news reader UI down with it.
 */
class TorManager(private val app: Application) {

    data class TorState(
        val running: Boolean = false,
        val progress: Int = 0,
        val port: Int? = null,
        val msg: String = ""
    )

    val state = MutableStateFlow(TorState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val candidatePorts = listOf(19050, 9050, 9150)
    private var bound = false
    private var stopping = false
    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
            if (!stopping) died()
        }
    }

    private fun findPort(): Int? {
        for (p in candidatePorts) {
            val ok = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 700); true }
            } catch (e: Throwable) {
                false
            }
            if (ok) return p
        }
        return null
    }

    private fun torWorks(port: Int): Boolean = try {
        val c = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", port)))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        c.newCall(Request.Builder().url("https://check.torproject.org/api/ip").build()).execute().use { r ->
            r.isSuccessful && (r.body?.string() ?: "").contains("\"IsTor\":true")
        }
    } catch (e: Throwable) {
        false
    }

    private fun writeTorrc() {
        try {
            val dir = app.getDir("TorService", Context.MODE_PRIVATE)
            File(dir, "torrc").writeText("SocksPort 127.0.0.1:19050\nAvoidDiskWrites 1\n")
        } catch (e: Throwable) {
        }
    }

    fun start() {
        if (bound) return
        stopping = false
        try {
            File(app.filesDir, "tor_crash.txt").delete()
            writeTorrc()
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action == "org.torproject.android.intent.action.ERROR") {
                        val t = i.getStringExtra("android.intent.extra.TEXT") ?: return
                        if (state.value.port == null) {
                            state.value = TorState(true, state.value.progress, null, "خطای Tor: $t")
                        }
                    }
                }
            }
            val f = IntentFilter("org.torproject.android.intent.action.ERROR")
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            bound = app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                cleanup(true)
                state.value = TorState(false, 0, null, "سرویس Tor اجرا نشد. از تنظیمات حالت «پروکسی SOCKS» را امتحان کنید.")
                return
            }
            state.value = TorState(true, 10, null, "در حال راه‌اندازی Tor… (یک تا سه دقیقه صبر کنید)")
            job?.cancel()
            job = scope.launch {
                var t = 0
                while (isActive && t < 240) {
                    delay(2000)
                    t += 2
                    val p = findPort()
                    if (p == null) continue
                    if (state.value.port == null && !state.value.msg.startsWith("خطای Tor")) {
                        state.value = TorState(true, 50, null, "در حال اتصال به شبکه Tor…")
                    }
                    if (torWorks(p)) {
                        state.value = TorState(true, 100, p, "Tor وصل شد")
                        return@launch
                    }
                }
                if (isActive) {
                    state.value = TorState(
                        false, 0, null,
                        "Tor وصل نشد؛ احتمالاً اینترنت شما آن را مسدود کرده است. از تنظیمات، «پروکسی SOCKS» را با Orbot یا v2rayNG امتحان کنید. با دکمه‌ی بروزرسانی دوباره تلاش می‌شود."
                    )
                    cleanup(true)
                }
            }
        } catch (t: Throwable) {
            cleanup(true)
            state.value = TorState(false, 0, null, "خطا در راه‌اندازی Tor: " + t.javaClass.simpleName + " " + (t.message ?: ""))
        }
    }

    private fun died() {
        val crash = try {
            val f = File(app.filesDir, "tor_crash.txt")
            if (f.exists()) f.readLines().drop(1).take(6).joinToString("\n") else null
        } catch (e: Throwable) {
            null
        }
        cleanup(true)
        val detail = if (crash.isNullOrBlank()) " (احتمالاً با این دستگاه سازگار نیست)" else ":\n$crash"
        state.value = TorState(false, 0, null, "پردازش Tor متوقف شد$detail\nاز تنظیمات «پروکسی SOCKS» را امتحان کنید.")
    }

    private fun cleanup(killProcess: Boolean) {
        job?.cancel()
        job = null
        if (bound) {
            try { app.unbindService(conn) } catch (e: Throwable) {}
            bound = false
        }
        receiver?.let {
            try { app.unregisterReceiver(it) } catch (e: Throwable) {}
        }
        receiver = null
        if (killProcess) killTorProcess()
    }

    private fun killTorProcess() {
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.filter { it.processName.endsWith(":tor") }?.forEach { Process.killProcess(it.pid) }
        } catch (e: Throwable) {
        }
    }

    fun stop() {
        stopping = true
        cleanup(true)
        state.value = TorState()
    }
}
