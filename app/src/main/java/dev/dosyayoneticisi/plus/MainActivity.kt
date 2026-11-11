package dev.dosyayoneticisi.plus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

private val Purple = Color(0xFF514BEB)
private val Palette = listOf(0xFF8051DB, 0xFFDA414D, 0xFF008974, 0xFF2288DF, 0xFF669800,
    0xFFE78B16, 0xFF485DD8, 0xFF008CDF, 0xFF55536F).map { Color(it) }
private val CategoryIcons = listOf(Icons.Default.Image, Icons.Default.Movie, Icons.Default.MusicNote,
    Icons.Default.Description, Icons.Default.Android, Icons.Default.FolderSpecial, Icons.Default.Schedule,
    Icons.Default.Cloud, Icons.Default.DeleteOutline)

internal fun Preferences.tr(turkish: String, english: String) = if (this.english) english else turkish
internal fun Preferences.label(category: Category) = when (category) {
    Category.Images -> tr("Görüntüler", "Images")
    Category.Videos -> tr("Videolar", "Videos")
    Category.Audio -> tr("Ses", "Audio")
    Category.Documents -> tr("Belgeler", "Documents")
    Category.Apps -> tr("Uygulamalar", "Apps")
    Category.Downloads -> tr("İndirilenler", "Downloads")
    Category.Recent -> tr("Yeni Dosyalar", "Recent Files")
    Category.Cloud -> tr("Bulut", "Cloud")
    Category.Trash -> tr("Geri Dönüşüm Kutusu", "Recycle Bin")
}
internal fun Preferences.label(page: Page) = when (page) {
    Page.Home -> tr("Ana Ekran", "Home")
    Page.Files -> tr("Dosyalar", "Files")
    Page.Settings -> tr("Ayarlar", "Settings")
}
internal fun Preferences.label(order: Order) = when (order) {
    Order.Name -> tr("Ad", "Name")
    Order.Date -> tr("Tarih", "Date")
    Order.Size -> tr("Boyut", "Size")
}

class MainActivity : ComponentActivity() {
    companion object { const val OPEN_LOCATION = "dev.dosyayoneticisi.plus.OPEN_LOCATION" }
    private val manager: Manager by viewModels()
    private val storageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (manager.preferences.value.enabled("usbDetect")) manager.reload()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        androidx.core.content.ContextCompat.registerReceiver(this, storageReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() { unregisterReceiver(storageReceiver); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openShortcut(intent)
    }

    private fun openShortcut(intent: Intent) {
        if (intent.action == OPEN_LOCATION) intent.getStringExtra("shortcutToken")?.let(manager::openPinnedLocation)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openShortcut(intent)
        setContent {
            val state by manager.state.collectAsStateWithLifecycle()
            val prefs by manager.preferences.collectAsStateWithLifecycle()
            val dark = when (prefs.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val colors = remember(prefs.dynamic, dark, configuration) { when {
                prefs.dynamic && Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme(primary = Color(0xFFBFBAFF), background = Color(0xFF19172A),
                    surface = Color(0xFF27243A), secondaryContainer = Color(0xFF444073))
                else -> lightColorScheme(primary = Purple, background = Color(0xFFE9E7FF),
                    surface = Color(0xFFF7F5FF), surfaceVariant = Color(0xFFE7E3FB),
                    secondaryContainer = Color(0xFFCCC4FF), onSurfaceVariant = Color(0xFF4B4864))
            }
            }
            MaterialExpressiveTheme(colorScheme = colors) {
                SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightNavigationBars = !dark
                }
                Surface(color = colors.background, modifier = Modifier.fillMaxSize()) { Interface(manager, state, prefs, dark, window) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (manager.state.value.category == Category.Apps) manager.refreshApps()
        if (manager.files.broadAccess != manager.state.value.roots.any { it.uri.scheme == "file" }) manager.reload()
    }
}

internal data class Prompt(
    val title: String, val message: String? = null, val initial: String? = null,
    val submit: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Interface(vm: Manager, s: Screen, p: Preferences, dark: Boolean, window: android.view.Window) {
    val ctx = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    var explorerDialog by remember { mutableStateOf(false) }
    val explorerFolder = s.stack.lastOrNull()?.uri?.toString()
    val canShowExplorerSettings = s.page == Page.Files && s.category !in listOf(Category.Apps, Category.Trash) && s.rootPath == null
    var extra by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<ExtraPage?>(null) }
    var addLocation by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun openExtra(page: ExtraPage) { addLocation = false; extra = page }
    fun closeExtra() { addLocation = false; extra = null }
    SideEffect {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !dark
    }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    if (s.viewer != null) {
        ViewerScreen(vm, s.viewer, p, window, s.busy)
        s.notice?.let { message ->
            AlertDialog(onDismissRequest = { vm.notice(null) }, title = { Text(p.tr("Bilgi", "Information")) },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.notice(null) }) { Text(p.tr("Kapat", "Close")) } })
        }
        return
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::grant) }
    val broad = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.reload() }
    fun authorizeStorage() {
        if (Build.VERSION.SDK_INT >= 30 && !vm.files.broadAccess) {
            prompt = Prompt(p.tr("Tüm dosyalara erişim", "All files access"),
                p.tr("Cihazın paylaşılan depolamasını kök klasörden gezmek için Android ayarlarında erişim verin.",
                    "Grant access in Android settings to browse shared storage from its top-level folder.")) {
                try {
                    broad.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                } catch (_: android.content.ActivityNotFoundException) {
                    tree.launch(null)
                }
            }
        } else tree.launch(null)
    }

    val confirm: (Prompt) -> Unit = { prompt = it }
    val openRoot: () -> Unit = {
        if (s.rootEnabled) vm.rootBrowse()
        else confirm(Prompt(p.tr("Root erişimi iste", "Request root access"),
            p.tr("Root yöneticinizde bu uygulama için yetki verin.", "Grant access for this app in your root manager.")) {
            vm.rootEnable(browse = true)
        })
    }
    BackHandler(extra != null || s.page != Page.Home || drawer.isOpen || s.busy) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (extra != null) closeExtra()
        else if (s.page == Page.Settings) vm.page(Page.Home) else vm.back()
    }
    val openVolume: (Volume) -> Unit = { volume ->
        val match = s.roots.firstOrNull { it.uri.path == volume.path.path }
            ?: s.roots.firstOrNull { vm.files.volumeFor(it.uri, listOf(volume)) != null }
        if (match != null) vm.open(match, true) else authorizeStorage()
    }
    StorageNotification(s, p)
    BoxWithConstraints {
    val drawerWidth = (maxWidth * .74f).coerceAtMost(360.dp)
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !s.busy && extra == null, drawerContent = {
        LocationsDrawer(vm, s, p, drawerWidth, close = { scope.launch { drawer.close() } },
            connect = { tree.launch(null) }, volume = openVolume, root = openRoot,
            network = { openExtra(it) })
    }) {
        Scaffold(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            containerColor = Color.Transparent,
            topBar = {
                val title = when (extra) {
                    ExtraPage.Defaults -> p.tr("Varsayılan uygulamalar", "Default apps")
                    ExtraPage.Cloud -> p.label(Category.Cloud)
                    ExtraPage.Remote -> p.tr("Uzak", "Remote")
                    null -> when (s.page) {
                    Page.Home -> p.tr("Dosya Yöneticisi +", "File Manager +")
                    Page.Files -> s.category?.let(p::label) ?: p.label(s.page)
                    Page.Settings -> p.label(s.page)
                    }
                }
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface),
                    navigationIcon = {
                        val showMenu = extra == null && (s.page == Page.Home || p.enabled("alwaysNav"))
                        IconButton(onClick = {
                            if (showMenu) scope.launch { if (drawer.isOpen) drawer.close() else drawer.open() }
                            else if (extra != null) closeExtra()
                            else if (s.page == Page.Settings) vm.page(Page.Home) else vm.back()
                        }) {
                            if (showMenu) Icon(Icons.Default.Menu, p.tr("Menü", "Menu"))
                            else Icon(Icons.AutoMirrored.Filled.ArrowBack, p.tr("Geri", "Back"))
                        }
                    },
                    actions = {
                        if (extra == ExtraPage.Cloud || extra == ExtraPage.Remote) {
                            IconButton(onClick = { addLocation = true }) { Icon(Icons.Default.Add, p.tr("Konum ekle", "Add location")) }
                        } else if (extra == null) {
                        if (s.rootPath == null || s.page != Page.Files) IconButton(enabled = !s.busy, onClick = { vm.query("") }) { Icon(Icons.Default.Search, p.tr("Ara", "Search")) }
                        if (canShowExplorerSettings) Box {
                            IconButton(enabled = !s.busy, onClick = { quick = true }) {
                                Icon(Icons.Default.Tune, p.tr("Hızlı işlemler", "Quick actions"))
                            }
                            DropdownMenu(quick, { quick = false }) {
                                DropdownMenuItem(text = { Text(p.tr("Görünüm ve sıralama", "View and sorting")) },
                                    onClick = { quick = false; explorerDialog = true })
                                DropdownMenuItem(text = { Text(p.tr("Yenile", "Refresh")) },
                                    onClick = { quick = false; vm.reload() })
                            }
                        }
                        Box {
                            IconButton(enabled = !s.busy, onClick = { menu = true }) { Icon(Icons.Default.MoreVert, p.tr("Seçenekler", "Options")) }
                            if (menu) ActionDialog(p.tr("Seçenekler", "Options"), p.tr("İptal", "Cancel"), { menu = false }) {
                                DialogAction(p.tr("Yenile", "Refresh")) { menu = false; vm.reload() }
                                DialogAction(p.tr("Klasör bağla", "Connect folder")) { menu = false; tree.launch(null) }
                                DialogAction(p.tr("Konumlar", "Locations")) { menu = false; vm.locations() }
                            }
                        }
                        }
                    }
                )
            },
            bottomBar = {
                if (extra == null) Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    NavigationBar(modifier = Modifier.clip(RoundedCornerShape(26.dp)),
                        containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        listOf(Page.Home, Page.Settings).forEach { page ->
                            val active = s.page == page || (s.page == Page.Files && page == Page.Home)
                            val scale by animateFloatAsState(if (active) 1f else 0.85f,
                                tween(220), label = "navigation")
                            val icon = when (page) {
                                Page.Home -> Icons.Default.Home
                                Page.Files -> Icons.Default.FolderOpen
                                Page.Settings -> Icons.Default.Settings
                            }
                            NavigationBarItem(selected = active, enabled = !s.busy, onClick = { vm.page(page) },
                                icon = { Icon(icon, null, Modifier.size((25 * scale).dp)) },
                                label = { Text(p.label(page), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .padding(horizontal = if (extra == null) 12.dp else 0.dp)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (extra) {
                    ExtraPage.Defaults -> DefaultsPanel(vm, p)
                    ExtraPage.Cloud, ExtraPage.Remote -> NetworkLocations(vm, p, extra == ExtraPage.Cloud, addLocation,
                        { addLocation = false }, { addLocation = true }, { closeExtra(); vm.openLocation(it) })
                    null -> when (s.page) {
                    Page.Home -> Home(vm, s, p, analyze = vm::analyze,
                        category = { if (it == Category.Cloud) openExtra(ExtraPage.Cloud) else vm.category(it) },
                        volume = openVolume)
                    Page.Files -> if (s.category == Category.Apps) AppsPanel(vm, s, p) else Browser(vm, s, p, { tree.launch(null) }, ::authorizeStorage, openRoot, confirm)
                    Page.Settings -> PreferencesPanel(vm, s, p, confirm) { openExtra(ExtraPage.Defaults) }
                    }
                }
            }
        }
    }

    }

    if (explorerDialog && canShowExplorerSettings) {
        ExplorerSettings(p, p.explorer(explorerFolder), explorerFolder != null, { explorerDialog = false }) { options, all ->
            vm.saveExplorer(explorerFolder, options, all)
            explorerDialog = false
        }
    }

    prompt?.let { request ->
        var value by remember(request) { mutableStateOf(request.initial.orEmpty()) }
        AlertDialog(onDismissRequest = { prompt = null }, title = { Text(request.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.message?.let { Text(it) }
                if (request.initial != null) OutlinedTextField(value, { value = it }, singleLine = true)
            }
        }, confirmButton = {
            TextButton(enabled = request.initial == null || value.isNotBlank(), onClick = { prompt = null; request.submit(value) }) {
                Text(p.tr("Onayla", "Confirm"))
            }
        }, dismissButton = { TextButton(onClick = { prompt = null }) { Text(p.tr("İptal", "Cancel")) } })
    }
    s.notice?.let { message ->
        AlertDialog(onDismissRequest = { vm.notice(null) }, title = { Text(p.tr("Bilgi", "Information")) },
            text = { Text(message, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.notice(null) }) { Text(p.tr("Kapat", "Close")) } })
    }
}

@Composable
private fun Home(vm: Manager, s: Screen, p: Preferences, analyze: () -> Unit,
    category: (Category) -> Unit, volume: (Volume) -> Unit) {
    val ctx = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val statistics = remember(s.indexed, p.hidden) {
        s.indexed?.let { entries -> Category.entries.associateWith { group ->
            val matches = entries.filter { (p.hidden || !it.name.startsWith('.')) && vm.files.matches(it, group) }
            matches.size to matches.sumOf { it.size }
        } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = surface) {
            Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, Modifier.size(35.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.tr("Depolama", "Storage"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(p.tr("Cihazınızın depolama durumu", "Device storage usage"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = analyze, enabled = !s.busy, contentPadding = PaddingValues(horizontal = 9.dp)) {
                        Icon(Icons.Default.PieChart, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(p.tr("Analiz Et", "Analyze"), maxLines = 1)
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = surface) {
                    Column {
                        s.volumes.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 10.dp))
                            val ratio = if (item.total > 0) (1.0 - item.free.toDouble() / item.total).toFloat().coerceIn(0f, 1f) else 0f
                            val progress by animateFloatAsState(ratio, tween(500), label = "storage")
                            Row(Modifier.fillMaxWidth().clickable(enabled = !s.busy) { volume(item) }.padding(9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Icon(if (item.primary) Icons.Default.Smartphone else Icons.Default.SdCard, null,
                                        Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(if (item.primary) p.tr("Dahili Depolama", "Internal Storage") else p.tr("SD Kart", "SD Card"),
                                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                    Text(p.fileSize(ctx, (item.total - item.free).coerceAtLeast(0)) +
                                        " / " + p.fileSize(ctx, item.total), style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(5.dp))
                                    UsageBar(progress)
                                }
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 4.dp,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round)
                                    Text(java.text.NumberFormat.getPercentInstance(
                                        Locale.forLanguageTag(if (p.english) "en" else "tr")).format(ratio.toDouble()),
                                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (p.enabled("systemVolume", false)) {
                    val system = remember(s.volumes) {
                        runCatching { android.os.StatFs(android.os.Environment.getRootDirectory().path) }.getOrNull()
                    }
                    system?.let {
                        Text(p.tr("Sistem depolaması", "System storage") + " · " +
                            p.fileSize(ctx, it.totalBytes - it.availableBytes) + " / " + p.fileSize(ctx, it.totalBytes),
                            Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Category.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { item ->
                    Surface(onClick = { category(item) }, enabled = !s.busy,
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 83.dp),
                        color = surface, shape = RoundedCornerShape(21.dp)) {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(CategoryIcons[item.ordinal], null, Modifier.size(31.dp), tint = Palette[item.ordinal])
                            Spacer(Modifier.height(4.dp))
                            Text(p.label(item), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge)
                            val stat = statistics?.get(item)
                            val info = when {
                                item == Category.Apps -> p.fileSize(ctx, s.apps.sumOf { it.bytes }) + " (" + s.apps.size + ")"
                                item == Category.Trash -> p.fileSize(ctx, s.deleted.sumOf { it.size })
                                item == Category.Cloud || stat == null -> null
                                else -> p.fileSize(ctx, stat.second) + " (" + stat.first + ")"
                            }
                            if (info != null) Text(info, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun UsageBar(progress: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val y = size.height / 2
        val end = (size.width - y).coerceAtLeast(y)
        drawLine(track, Offset(y, y), Offset(end, y), size.height, StrokeCap.Round)
        if (progress > 0) drawLine(color, Offset(y, y), Offset(y + (end - y) * progress, y), size.height, StrokeCap.Round)
    }
}















