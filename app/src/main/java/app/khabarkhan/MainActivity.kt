@file:OptIn(ExperimentalMaterial3Api::class)

package app.khabarkhan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.LocalTextStyle
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App(vm)
                }
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EEF1),
    onPrimaryContainer = Color(0xFF00363B),
    secondaryContainer = Color(0xFFDCEFF1),
    background = Color(0xFFF6F8F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EDEF),
    onSurfaceVariant = Color(0xFF52606A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8D2),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F56),
    onPrimaryContainer = Color(0xFFD3EEF1),
    secondaryContainer = Color(0xFF1F3A3F),
    background = Color(0xFF0F1417),
    surface = Color(0xFF182025),
    surfaceVariant = Color(0xFF263238),
    onSurfaceVariant = Color(0xFF9FB0B8)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

fun String.fa(): String = buildString {
    for (c in this@fa) append(if (c in '0'..'9') '۰' + (c - '0') else c)
}

fun ago(t: Long): String {
    if (t <= 0L) return ""
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "هم‌اکنون"
        m < 60 -> "$m دقیقه پیش".fa()
        m < 1440 -> "${m / 60} ساعت پیش".fa()
        else -> "${m / 1440} روز پیش".fa()
    }
}

@Composable
fun App(vm: MainVM) {
    var open by remember { mutableStateOf<Article?>(null) }
    var settings by remember { mutableStateOf(false) }
    val a = open
    BackHandler(enabled = a != null) { open = null }
    if (a != null) {
        Reader(vm, a) { open = null }
    } else {
        FeedScreen(vm, onOpen = { open = it }, onSettings = { settings = true })
    }
    if (settings) SettingsSheet(vm) { settings = false }
}

@Composable
fun FeedScreen(vm: MainVM, onOpen: (Article) -> Unit, onSettings: () -> Unit) {
    val filter = vm.filter
    val list = if (filter == null) vm.articles else vm.articles.filter { it.sourceId == filter }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("خبرخوان", fontWeight = FontWeight.Bold)
                        if (vm.lastUpdate > 0) {
                            Text(
                                "آخرین به‌روزرسانی: " + ago(vm.lastUpdate),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "تازه‌سازی") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "تنظیمات") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("همه") })
                }
                items(SOURCES) { s ->
                    FilterChip(
                        selected = vm.filter == s.id,
                        onClick = { vm.filter = s.id },
                        label = { Text(s.name) },
                        leadingIcon = { Box(Modifier.size(8.dp).background(Color(s.color), CircleShape)) }
                    )
                }
            }
            Banner(vm)
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (list.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (vm.refreshing) "در حال دریافت خبرها…" else "هنوز خبری دریافت نشده. صفحه را به پایین بکشید یا تنظیمات اتصال را بررسی کنید.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(list, key = { it.link }) { a -> ArticleCard(vm, a) { onOpen(a) } }
                    }
                }
            }
        }
    }
}

@Composable
fun Banner(vm: MainVM) {
    val msg: String? = when {
        vm.mode == Mode.TOR && vm.torState.port == null -> vm.torState.msg.ifBlank { "در حال راه‌اندازی تور…" }
        vm.errors.isNotEmpty() -> "بارگذاری نشد: " + vm.errors.keys.joinToString("، ") { srcOf(it).name }
        else -> null
    }
    if (msg != null) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 0.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ArticleCard(vm: MainVM, a: Article, onClick: () -> Unit) {
    val src = srcOf(a.sourceId)
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Color(src.color), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(src.name, style = MaterialTheme.typography.labelLarge, color = Color(src.color), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(ago(a.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        a.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 26.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (a.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            a.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (vm.showImages && a.image != null) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = a.image,
                        contentDescription = null,
                        imageLoader = vm.imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun Reader(vm: MainVM, a: Article, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val src = srcOf(a.sourceId)
    val full = vm.fullTexts[a.link]
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(src.name, style = MaterialTheme.typography.titleMedium, color = Color(src.color)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت") }
                },
                actions = {
                    IconButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, a.title + "\n" + a.link)
                        }
                        ctx.startActivity(Intent.createChooser(i, null))
                    }) { Icon(Icons.Default.Share, contentDescription = "اشتراک‌گذاری") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(a.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            if (a.time > 0) {
                Spacer(Modifier.height(8.dp))
                Text(ago(a.time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            if (vm.showImages && a.image != null) {
                AsyncImage(
                    model = a.image,
                    contentDescription = null,
                    imageLoader = vm.imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(16.dp))
            }
            val body = full ?: a.summary
            if (body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp)
                Spacer(Modifier.height(20.dp))
            }
            if (full == null) {
                Button(
                    onClick = { vm.loadFull(a) },
                    enabled = vm.fullLoading == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (vm.fullLoading == a.link) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("دریافت متن کامل (فقط متن، کم‌حجم)")
                }
            }
            vm.fullError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("باز کردن در مرورگر") }
            Text(
                "باز کردن در مرورگر از تور یا پروکسی اپ استفاده نمی‌کند.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
            )
        }
    }
}

@Composable
fun ModeRow(vm: MainVM, m: Mode, title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.changeMode(m) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = vm.mode == m, onClick = { vm.changeMode(m) })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSheet(vm: MainVM, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())
        ) {
            Text("نوع اتصال", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ModeRow(vm, Mode.DIRECT, "مستقیم", "بدون پروکسی؛ سریع‌ترین حالت")
            ModeRow(vm, Mode.TOR, "تور داخلی", "عبور از فیلترینگ با شبکه تور؛ کندتر است")
            if (vm.mode == Mode.TOR) {
                val st = vm.torState
                LinearProgressIndicator(
                    progress = { st.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                Text(st.msg, style = MaterialTheme.typography.bodySmall)
                Text(
                    "اگر اتصال تور در شبکه شما بسته است و پیشرفت متوقف می‌ماند، Psiphon یا Orbot را اجرا کنید و گزینه‌ی پروکسی SOCKS دلخواه را بزنید.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            ModeRow(vm, Mode.CUSTOM, "پروکسی SOCKS دلخواه", "مثلاً Orbot، Psiphon یا v2rayNG روی همین گوشی")
            if (vm.mode == Mode.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = vm.host,
                        onValueChange = { vm.host = it },
                        label = { Text("آدرس") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = vm.port,
                        onValueChange = { vm.port = it.filter { c -> c.isDigit() } },
                        label = { Text("پورت") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(onClick = { vm.applyCustom() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("اعمال") }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("نمایش تصاویر", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "پیش‌فرض خاموش است تا حجم و زمان بارگذاری کم بماند",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = vm.showImages, onCheckedChange = { vm.setImages(it) })
            }
        }
    }
}
