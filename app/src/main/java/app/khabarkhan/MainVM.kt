package app.khabarkhan

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Mode { DIRECT, TOR, CUSTOM }

private fun socks(h: String, p: Int) = Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, p))

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val tor = TorManager(app)
    private val bySource = HashMap<String, List<Article>>()
    private var torReady = false

    var articles by mutableStateOf<List<Article>>(emptyList())
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var filter by mutableStateOf<String?>(null)
    var mode by mutableStateOf(Mode.DIRECT)
        private set
    var showImages by mutableStateOf(false)
        private set
    var host by mutableStateOf("127.0.0.1")
    var port by mutableStateOf("1080")
    var lastUpdate by mutableStateOf(0L)
        private set
    var torState by mutableStateOf(TorManager.TorState())
        private set
    val fullTexts = mutableStateMapOf<String, String>()
    var fullLoading by mutableStateOf<String?>(null)
        private set
    var fullError by mutableStateOf<String?>(null)
        private set

    val imageLoader: ImageLoader

    init {
        Net.init(ctx)
        imageLoader = ImageLoader.Builder(ctx).callFactory(Net.callFactory).crossfade(true).build()
        mode = runCatching { Mode.valueOf(prefs.getString("mode", "DIRECT") ?: "DIRECT") }.getOrDefault(Mode.DIRECT)
        showImages = prefs.getBoolean("img", false)
        host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        port = prefs.getString("port", "1080") ?: "1080"
        lastUpdate = prefs.getLong("last", 0L)
        for (s in SOURCES) bySource[s.id] = Store.load(ctx, s.id)
        publish()

        viewModelScope.launch {
            tor.state.collect { st ->
                torState = st
                val p = st.port
                if (mode == Mode.TOR && p != null && !torReady) {
                    torReady = true
                    Net.setProxy(socks("127.0.0.1", p))
                    Net.blocked = false
                    refresh()
                }
            }
        }
        applyMode()
    }

    private fun applyMode() {
        torReady = false
        when (mode) {
            Mode.DIRECT -> {
                tor.stop(); Net.blocked = false; Net.setProxy(null); refresh()
            }
            Mode.TOR -> {
                Net.blocked = true; Net.setProxy(null); tor.start()
            }
            Mode.CUSTOM -> {
                tor.stop(); Net.blocked = false
                Net.setProxy(socks(host, port.toIntOrNull() ?: 1080)); refresh()
            }
        }
    }

    fun changeMode(m: Mode) {
        if (m == mode) return
        mode = m
        prefs.edit().putString("mode", m.name).apply()
        if (m != Mode.CUSTOM) applyMode()
    }

    fun applyCustom() {
        prefs.edit().putString("mode", Mode.CUSTOM.name).putString("host", host).putString("port", port).apply()
        mode = Mode.CUSTOM
        applyMode()
    }

    fun setImages(v: Boolean) {
        showImages = v
        prefs.edit().putBoolean("img", v).apply()
    }

    private fun publish() {
        articles = bySource.values.flatten().distinctBy { it.link }.sortedByDescending { it.time }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException, is SocketTimeoutException -> "اتصال برقرار نشد (اینترنت ضعیف یا فیلتر)"
        else -> e.message ?: "خطا"
    }

    fun refresh() {
        if (refreshing) return
        if (mode == Mode.TOR && !torReady) {
            tor.start()
            return
        }
        refreshing = true
        viewModelScope.launch {
            coroutineScope {
                for (s in SOURCES) {
                    launch {
                        try {
                            val list = withContext(Dispatchers.Default) { fetchSource(s) }
                            bySource[s.id] = list
                            withContext(Dispatchers.IO) { Store.save(ctx, s.id, list) }
                            errors = errors - s.id
                            publish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errors = errors + (s.id to friendly(e))
                        }
                    }
                }
            }
            lastUpdate = System.currentTimeMillis()
            prefs.edit().putLong("last", lastUpdate).apply()
            refreshing = false
        }
    }

    fun loadFull(a: Article) {
        if (fullLoading != null) return
        fullLoading = a.link
        fullError = null
        viewModelScope.launch {
            try {
                fullTexts[a.link] = withContext(Dispatchers.Default) { fetchFullText(a.link) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fullError = friendly(e)
            }
            fullLoading = null
        }
    }

    override fun onCleared() {
        tor.stop()
    }
}
