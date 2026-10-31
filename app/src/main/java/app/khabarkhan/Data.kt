package app.khabarkhan

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class Source(
    val id: String,
    val name: String,
    val home: String,
    val feeds: List<String>,
    val color: Long
)

data class Article(
    val id: String,
    val sourceId: String,
    val title: String,
    val link: String,
    val summary: String,
    val image: String?,
    val time: Long
)

val SOURCES = listOf(
    Source("bbc", "بی‌بی‌سی فارسی", "https://www.bbc.com/persian",
        listOf("https://feeds.bbci.co.uk/persian/rss.xml"), 0xFFB80000),
    Source("iranintl", "ایران اینترنشنال", "https://www.iranintl.com/", listOf(), 0xFFC2185B),
    Source("voa", "صدای آمریکا", "https://ir.voanews.com", listOf(), 0xFF1F5FA8),
    Source("farda", "رادیو فردا", "https://www.radiofarda.com", listOf(), 0xFF0E7C86),
    Source("hpip", "HPIP", "https://hpip.blogspot.com",
        listOf("https://hpip.blogspot.com/feeds/posts/default?alt=rss"), 0xFF7B4FB3),
    Source("paskoocheh", "پسکوچه", "https://paskoocheh.com",
        listOf("https://paskoocheh.com/feed", "https://paskoocheh.com/blog/feed"), 0xFFE08A00)
)

fun srcOf(id: String): Source = SOURCES.firstOrNull { it.id == id } ?: SOURCES.first()

object Net {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    @Volatile
    var blocked = false
    private lateinit var base: OkHttpClient
    lateinit var client: OkHttpClient

    val callFactory: Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = client.newCall(request)
    }

    fun init(ctx: Context) {
        if (::base.isInitialized) return
        base = OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "http"), 30L * 1024 * 1024))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                if (blocked) throw IOException("منتظر اتصال تور")
                chain.proceed(chain.request())
            }
            .build()
        client = base
    }

    fun setProxy(p: Proxy?) {
        client = if (p == null) base else base.newBuilder().proxy(p).build()
    }

    suspend fun get(url: String, retries: Int = 2): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (i in 0..retries) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "fa,en;q=0.7")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP " + r.code)
                    return@withContext r.body!!.string()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (blocked) break
                delay(800L * (i + 1))
            }
        }
        throw last ?: IOException("خطای نامشخص")
    }
}

fun parseDate(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    val t = s.trim()
    try {
        return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    try {
        return OffsetDateTime.parse(t).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    return 0L
}

fun parseFeed(xml: String, s: Source): List<Article> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    val out = ArrayList<Article>()
    for (e in doc.select("item, entry")) {
        val title = e.selectFirst("title")?.text()?.trim().orEmpty()
        val linkEl = e.select("link").firstOrNull { l ->
            val r = l.attr("rel")
            r.isEmpty() || r == "alternate"
        }
        val href = linkEl?.attr("href").orEmpty()
        val link = (if (href.isNotBlank()) href else (linkEl?.text() ?: e.selectFirst("guid")?.text() ?: "")).trim()
        if (title.isEmpty() || !link.startsWith("http")) continue

        val raw = e.getElementsByTag("content:encoded").firstOrNull()?.text()
            ?: e.selectFirst("description, summary, content")?.text()
            ?: ""
        val frag = Jsoup.parse(raw)
        val summary = frag.text().trim().take(500)

        var img: String? = e.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
        if (img.isNullOrBlank()) {
            img = e.getElementsByTag("media:content").firstOrNull { m ->
                m.attr("url").isNotBlank() && (m.attr("medium") == "image" || m.attr("type").startsWith("image") || m.attr("medium").isEmpty())
            }?.attr("url")
        }
        if (img.isNullOrBlank()) {
            img = e.select("enclosure").firstOrNull { m -> m.attr("type").startsWith("image") }?.attr("url")
        }
        if (img.isNullOrBlank()) img = frag.selectFirst("img[src]")?.attr("src")
        val image = if (img != null && img.startsWith("http")) img else null

        val ds = listOf("pubDate", "published", "updated", "dc:date")
            .firstNotNullOfOrNull { tag -> e.getElementsByTag(tag).firstOrNull()?.text() }
        out.add(Article(link, s.id, title, link, summary, image, parseDate(ds)))
    }
    return out
}

private fun scrapeHome(html: String, s: Source): List<Article> {
    val doc = Jsoup.parse(html, s.home)
    val host = java.net.URI(s.home).host.removePrefix("www.")
    val seen = HashSet<String>()
    val out = ArrayList<Article>()
    for (a in doc.select("a[href]")) {
        val href = a.absUrl("href")
        val text = a.text().trim()
        if (text.length < 30 || !href.startsWith("http") || !href.contains(host)) continue
        if (!seen.add(href)) continue
        val img = a.selectFirst("img[src]")?.absUrl("src")
        out.add(Article(href, s.id, text, href, "", if (img.isNullOrBlank()) null else img, dateFromUrl(href)))
        if (out.size >= 40) break
    }
    return out
}

suspend fun fetchSource(s: Source): List<Article> {
    var err: Exception? = null
    for (u in s.feeds) {
        try {
            val l = parseFeed(Net.get(u), s)
            if (l.isNotEmpty()) return l
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e
        }
    }
    var html: String? = null
    try {
        val page = Net.get(s.home)
        html = page
        val doc = Jsoup.parse(page, s.home)
        val urls = doc.select("link[rel=alternate]")
            .filter { l ->
                val t = l.attr("type")
                t.contains("rss") || t.contains("atom")
            }
            .map { it.absUrl("href") }
            .filter { it.startsWith("http") }
            .distinct()
            .take(4)
        for (u in urls) {
            try {
                val l = parseFeed(Net.get(u), s)
                if (l.isNotEmpty()) return l
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        err = e
    }
    if (html != null) {
        val l = scrapeHome(html, s)
        if (l.isNotEmpty()) return l
    }
    throw err ?: IOException("منبع در دسترس نیست")
}

suspend fun fetchFullText(url: String): String {
    val html = Net.get(url)
    val doc = Jsoup.parse(html, url)
    doc.select("script,style,nav,footer,aside,form,figure,.share,.social").remove()
    val sels = listOf("article p", ".wsw p", "#article-content p", ".post-body p", ".entry-content p", "main p", "p")
    for (sel in sels) {
        val ps = doc.select(sel).map { it.text().trim() }.filter { it.length > 30 }
        if (ps.sumOf { it.length } > 300) return ps.joinToString("\n\n")
    }
    throw IOException("متن کامل استخراج نشد")
}

object Store {
    fun save(ctx: Context, id: String, list: List<Article>) {
        try {
            val arr = JSONArray()
            for (a in list) {
                arr.put(
                    JSONObject()
                        .put("id", a.id).put("s", a.sourceId).put("t", a.title)
                        .put("l", a.link).put("m", a.summary).put("i", a.image ?: "")
                        .put("d", a.time)
                )
            }
            File(ctx.filesDir, "c_$id.json").writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context, id: String): List<Article> = try {
        val arr = JSONArray(File(ctx.filesDir, "c_$id.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Article(
                o.getString("id"), o.getString("s"), o.getString("t"), o.getString("l"),
                o.optString("m"), o.optString("i").ifBlank { null }, o.optLong("d")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun dateFromUrl(u: String): Long {
    val m = Regex("/(20\\d{2})(\\d{2})(\\d{2})\\d{4,}").find(u) ?: return 0L
    return try {
        val (y, mo, d) = m.destructured
        java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(12, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    } catch (e: Exception) {
        0L
    }
}
