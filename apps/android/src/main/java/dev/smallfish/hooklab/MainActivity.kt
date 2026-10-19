package dev.smallfish.hooklab

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.smallfish.hooklab.protocol.EventContract
import dev.smallfish.hooklab.protocol.TraceStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

private val Bg = Color(0xFF0A1119)
private val Panel = Color(0xFF151F2B)
private val Panel2 = Color(0xFF1C2A38)
private val Line = Color(0xFF293849)
private val Mint = Color(0xFF67E8C0)
private val Periwinkle = Color(0xFFA3B7FF)
private val Muted = Color(0xFF9AABBC)
private val White = Color(0xFFF2F6FB)
private val Coral = Color(0xFFFFAA91)

private enum class Tab { LIVE, METHODS, SYSTEM }
private enum class Filter { ALL, BRIGHTNESS, VALIDATION, EXCEPTIONS }

private data class TraceEvent(
    val id: Long, val method: String, val arg0: Float, val arg1: Int,
    val result: Float?, val outcome: String, val exception: String,
    val durationNs: Long, val whenNs: Long, val pid: Int, val tid: Int
) {
    val isError get() = outcome == "throw"
    val shortName get() = method.substringAfter("ImageProcessor.")
    fun duration() = String.format(Locale.ROOT, "%.3f ms", durationNs / 1_000_000.0)
}

class MainActivity : ComponentActivity() {
    private val uri = Uri.parse("content://" + EventContract.AUTHORITY + "/events")
    private var events by mutableStateOf<List<TraceEvent>>(emptyList())
    private var loadError by mutableStateOf<String?>(null)
    private var active by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var currentTab by mutableStateOf(Tab.LIVE)
    private var currentFilter by mutableStateOf(Filter.ALL)
    private var search by mutableStateOf("")
    private var detail by mutableStateOf<TraceEvent?>(null)
    private var askClear by mutableStateOf(false)

    private val exporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { target ->
        if (target != null) lifecycleScope.launch(Dispatchers.IO) { export(target) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 17, 25)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 25)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint, onPrimary = Bg, background = Bg,
                    onBackground = White, surface = Panel, onSurface = White,
                    outline = Line, error = Coral
                )
            ) {
                val isActive = active
                val isPaused = paused
                LaunchedEffect(isActive, isPaused) {
                    if (isActive && !isPaused) {
                        while (isActive) {
                            reload()
                            delay(1200)
                        }
                    }
                }
                Dashboard(
                    events = events, error = loadError, paused = isPaused,
                    tab = currentTab, filter = currentFilter, search = search,
                    onTab = { currentTab = it },
                    onFilter = { currentFilter = it },
                    onSearch = { search = it },
                    onPause = { paused = !paused },
                    onRefresh = { lifecycleScope.launch { reload() } },
                    onOpenDemo = ::openDemo,
                    onDetail = { detail = it },
                    onClear = { askClear = true },
                    onExport = {
                        if (events.isEmpty()) notice("No events to export")
                        else exporter.launch("hook-lab-demo.jsonl")
                    },
                )
                if (askClear) AlertDialog(
                    onDismissRequest = { askClear = false },
                    title = { Text("Clear Demo history?") },
                    text = { Text("Delete stored events on this phone? This does not uninstall hooks or modify the Demo app.") },
                    confirmButton = {
                        TextButton(onClick = {
                            askClear = false
                            lifecycleScope.launch {
                                val cleared = withContext(Dispatchers.IO) {
                                    runCatching { contentResolver.delete(uri, null, null) }
                                }
                                cleared.onSuccess { events = emptyList(); notice("History cleared") }
                                    .onFailure { notice("Clear failed: " + it.javaClass.simpleName) }
                            }
                        }) { Text("CLEAR", color = Coral) }
                    },
                    dismissButton = { TextButton(onClick = { askClear = false }) { Text("CANCEL") } }
                )
                detail?.let { EventDialog(it) { detail = null } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
    }

    override fun onPause() {
        active = false
        super.onPause()
    }

    private suspend fun reload() {
        val result = withContext(Dispatchers.IO) { runCatching { load() } }
        result.onSuccess { events = it; loadError = null }
            .onFailure { loadError = it.message ?: it.javaClass.simpleName }
    }

    private fun load(): List<TraceEvent> {
        val output = ArrayList<TraceEvent>()
        contentResolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor == null) error("Event provider unavailable")
            val id = cursor.getColumnIndexOrThrow("_id")
            val method = cursor.getColumnIndexOrThrow("method")
            val a0 = cursor.getColumnIndexOrThrow("arg0")
            val a1 = cursor.getColumnIndexOrThrow("arg1")
            val result = cursor.getColumnIndexOrThrow("result")
            val outcome = cursor.getColumnIndexOrThrow("outcome")
            val ex = cursor.getColumnIndexOrThrow("exception")
            val duration = cursor.getColumnIndexOrThrow("duration_ns")
            val time = cursor.getColumnIndexOrThrow("when_ns")
            val pid = cursor.getColumnIndexOrThrow("pid")
            val tid = cursor.getColumnIndexOrThrow("tid")
            while (cursor.moveToNext()) {
                output.add(TraceEvent(
                    cursor.getLong(id), cursor.getString(method), cursor.getFloat(a0),
                    cursor.getInt(a1), if (cursor.isNull(result)) null else cursor.getFloat(result),
                    cursor.getString(outcome), cursor.getString(ex), cursor.getLong(duration),
                    cursor.getLong(time), cursor.getInt(pid), cursor.getInt(tid)
                ))
            }
        }
        return output
    }

    private fun export(target: Uri) {
        val result = runCatching {
            val snapshot = load()
            val stream = contentResolver.openOutputStream(target) ?: error("Cannot open export file")
            stream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                for (e in snapshot.asReversed()) {
                    val obj = JSONObject().put("schema_version", 1)
                        .put("source", "HOOK_LAB_DEMO")
                        .put("id", e.id).put("method", e.method)
                        .put("arg0", e.arg0.toDouble()).put("arg1", e.arg1)
                        .put("result", e.result?.toDouble() ?: JSONObject.NULL)
                        .put("outcome", e.outcome)
                        .put("exception_type", e.exception)
                        .put("duration_ns", e.durationNs)
                        .put("monotonic_ns", e.whenNs)
                        .put("pid", e.pid).put("tid", e.tid)
                    writer.append(obj.toString()).append('\n')
                }
            }
            snapshot.size
        }
        runOnUiThread {
            result.onSuccess { notice("Exported " + it + " events") }
                .onFailure { notice("Export failed: " + it.javaClass.simpleName) }
        }
    }

    private fun openDemo() {
        val intent: Intent? = packageManager.getLaunchIntentForPackage(EventContract.TARGET_PACKAGE)
        if (intent == null) notice("Install the Hook Lab Demo APK first") else startActivity(intent)
    }

    private fun notice(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

@Composable
private fun Dashboard(
    events: List<TraceEvent>, error: String?, paused: Boolean, tab: Tab,
    filter: Filter, search: String, onTab: (Tab) -> Unit, onFilter: (Filter) -> Unit,
    onSearch: (String) -> Unit, onPause: () -> Unit, onRefresh: () -> Unit,
    onOpenDemo: () -> Unit, onDetail: (TraceEvent) -> Unit, onClear: () -> Unit,
    onExport: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Header(events.isNotEmpty())
        when (tab) {
            Tab.LIVE -> {
                val filtered = remember(events, filter, search) {
                    events.filter { e ->
                        val match = when (filter) {
                            Filter.ALL -> true
                            Filter.BRIGHTNESS -> e.method == EventContract.METHOD_BRIGHTNESS
                            Filter.VALIDATION -> e.method == EventContract.METHOD_VALIDATE
                            Filter.EXCEPTIONS -> e.isError
                        }
                        match && (search.isBlank() || e.method.contains(search.trim(), true)
                            || e.pid.toString().contains(search.trim()))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    item { TargetCard(onOpenDemo) }
                    item { Metrics(events, error) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                TitleText("TRACE STREAM")
                                Caption("Latest " + events.size + " calls · signed Demo")
                            }
                            TextButton(onClick = onPause) {
                                Text(if (paused) "RESUME VIEW" else "PAUSE VIEW",
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    item {
                        OutlinedTextField(value = search, onValueChange = onSearch,
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search method or PID", fontSize = 13.sp) },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Mint, unfocusedBorderColor = Line,
                                focusedContainerColor = Panel, unfocusedContainerColor = Panel
                            ))
                    }
                    item { Filters(filter, onFilter) }
                    if (paused) item {
                        InfoCard("VIEW PAUSED",
                            "Only screen refresh is paused. Vector may continue recording.",
                            Periwinkle)
                    }
                    if (filtered.isEmpty()) item {
                        InfoCard("NO MATCHING CALLS",
                            if (events.isEmpty()) "Enable this module in Vector, scope to Hook Lab Demo, restart Demo, and press its test buttons."
                            else "Try a different filter or search term.", Mint)
                    }
                    items(filtered, key = { it.id }) { e -> EventCard(e) { onDetail(e) } }
                }
            }
            Tab.METHODS -> Methods(events, onOpenDemo, onExplore = {
                onFilter(it); onSearch(""); onTab(Tab.LIVE)
            }, Modifier.weight(1f))
            Tab.SYSTEM -> SystemPanel(events, error, paused, onRefresh, onExport,
                onClear, Modifier.weight(1f))
        }
        Navigation(tab, onTab)
    }
}

@Composable
private fun Header(hasEvents: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center) {
            Text("H", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Bg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("HOOK LAB", color = White, fontSize = 21.sp,
                letterSpacing = 1.2.sp, fontWeight = FontWeight.ExtraBold)
            Text("INSTRUMENTATION CONSOLE", color = Muted, fontSize = 9.sp,
                letterSpacing = 1.3.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("M1 · PREVIEW", fontSize = 10.sp, color = Periwinkle,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(if (hasEvents) "● DATA RECEIVED" else "○ AWAITING DATA",
                color = if (hasEvents) Mint else Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun TargetCard(open: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Panel2, Panel, Panel))
        ).padding(17.dp)) {
            Label("ACTIVE TARGET")
            Spacer(Modifier.height(8.dp))
            Text("Hook Lab Demo", color = White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(3.dp))
            Text(EventContract.TARGET_PACKAGE, color = Muted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VECTOR / JAVA", color = Mint, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = open, modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                    Text("OPEN DEMO ↗", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Metrics(events: List<TraceEvent>, error: String?) {
    val stats = remember(events) {
        TraceStatistics.from(events.map { it.durationNs }.toLongArray(),
            events.count { it.isError })
    }
    Column {
        TitleText("SESSION OVERVIEW")
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("EVENTS", stats.count.toString(), "recent snapshot", Mint, Modifier.weight(1f))
            Metric("ERRORS", stats.errorCount.toString(), "exceptions", Coral, Modifier.weight(1f))
            Metric("P95 (MS)", String.format(Locale.ROOT, "%.2f",
                stats.p95Nanos / 1_000_000.0), "observed calls", Periwinkle,
                Modifier.weight(1f))
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            InfoCard("STORAGE ERROR", error, Coral)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, sub: String, tint: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp),
        color = Panel, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 15.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(value, color = tint, fontSize = 23.sp, maxLines = 1,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(sub, color = Muted, fontSize = 9.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun Label(s: String) {
    Text(s, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, color = Mint)
}

@Composable private fun TitleText(s: String) {
    Text(s, fontSize = 12.sp, letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Bold, color = White)
}

@Composable private fun Caption(s: String) {
    Text(s, fontSize = 11.sp, color = Muted)
}

@Composable
private fun Filters(value: Filter, onSelected: (Filter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        val items = listOf(Filter.ALL to "All", Filter.BRIGHTNESS to "Brightness",
            Filter.VALIDATION to "Validation", Filter.EXCEPTIONS to "Exceptions")
        items.forEach { (item, text) ->
            val selected = item == value
            Surface(
                modifier = Modifier.clickable { onSelected(item) },
                color = if (selected) Color(0xFF255046) else Panel,
                border = BorderStroke(1.dp, if (selected) Mint.copy(alpha = .45f) else Line),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(text, color = if (selected) Mint else Muted,
                    fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun EventCard(e: TraceEvent, click: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = click),
        color = Panel, border = BorderStroke(1.dp, Line),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (e.isError) Coral else Mint,
                    RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
                Text(e.shortName, color = White, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(e.duration(), color = if (e.isError) Coral else Periwinkle,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(10.dp))
            val value = if (e.isError) "throws " + e.exception else "returns " + (e.result ?: "null")
            Text("in " + e.arg0 + ", " + e.arg1 + "   →   " + value, color = Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PID " + e.pid + "  ·  TID " + e.tid + "  ·  #" + e.id,
                    fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f))
                Text("DETAILS ↗", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, highlight: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = Panel,
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Text(title, color = highlight, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun Methods(events: List<TraceEvent>, open: () -> Unit,
                    onExplore: (Filter) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            TitleText("METHOD EXPLORER")
            Spacer(Modifier.height(5.dp))
            Caption("Known instrumented Demo methods · scope limited")
        }
        item {
            MethodCard("adjustBrightness",
                "(float, int) → float", "Numeric brightness transform",
                events.count { it.method == EventContract.METHOD_BRIGHTNESS }) {
                onExplore(Filter.BRIGHTNESS)
            }
        }
        item {
            MethodCard("validateLevel",
                "(int) → int / IllegalArgumentException", "Validation and controlled exceptions",
                events.count { it.method == EventContract.METHOD_VALIDATE }) {
                onExplore(Filter.VALIDATION)
            }
        }
        item {
            InfoCard("COMING NEXT", "Live class enumeration and user-defined method hooks " +
                "will follow once Vector service compatibility is verified. These two " +
                "entries are actual Demo hooks, not a fake list of installed app methods.", Periwinkle)
        }
        item {
            Button(onClick = open, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                Text("OPEN DEMO APP ↗", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MethodCard(name: String, signature: String, description: String,
                       count: Int, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp), color = Panel,
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = White, fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(count.toString() + " calls ↗", color = Mint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text(signature, color = Periwinkle, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Caption(description)
        }
    }
}

@Composable
private fun SystemPanel(events: List<TraceEvent>, error: String?, paused: Boolean,
                        refresh: () -> Unit, export: () -> Unit, clear: () -> Unit,
                        modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TitleText("SYSTEM & DIAGNOSTICS")
            Spacer(Modifier.height(5.dp))
            Caption("No simulated root / Vector connection states")
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Panel,
                border = BorderStroke(1.dp, Line)) {
                Column(Modifier.fillMaxWidth().padding(17.dp)) {
                    Setting("ANDROID", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT)
                    Spacer(Modifier.height(12.dp))
                    Setting("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
                    Spacer(Modifier.height(12.dp))
                    Setting("VECTOR", "Check module status in Vector manager")
                    Spacer(Modifier.height(12.dp))
                    Setting("EVENT CHANNEL",
                        if (events.isNotEmpty()) "Demo events received" else "Not yet verified")
                    Spacer(Modifier.height(12.dp))
                    Setting("DISPLAY",
                        if (paused) "Paused view · recording may continue"
                        else "Refreshes every 1.2 seconds")
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Setting("ERROR", error)
                    }
                }
            }
        }
        item {
            TitleText("DATA MANAGEMENT")
            Spacer(Modifier.height(9.dp))
            Button(onClick = export, enabled = events.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg)) {
                Text("EXPORT JSONL ↗", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Line)) {
                Text("REFRESH NOW", color = White)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = clear, modifier = Modifier.fillMaxWidth()) {
                Text("CLEAR LOCAL EVENTS", color = Coral)
            }
        }
        item {
            InfoCard("PRIVACY FIRST",
                "Demo-only numeric values and exception types. Events are held locally. " +
                "JSONL export contains at most the latest 250 event records.", Mint)
        }
        item {
            InfoCard("PREVIEW LIMITS",
                "Frida/Native, stack traces, custom app targets, dynamic rules, " +
                "and actual Hook-stop controls are not implemented yet.", Muted)
        }
    }
}

@Composable private fun Setting(label: String, value: String) {
    Label(label)
    Spacer(Modifier.height(4.dp))
    Text(value, color = White, fontSize = 12.sp)
}

@Composable
private fun Navigation(tab: Tab, change: (Tab) -> Unit) {
    Surface(color = Panel, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(Tab.LIVE, "LIVE", "◉"),
                Triple(Tab.METHODS, "METHODS", "⌘"),
                Triple(Tab.SYSTEM, "SYSTEM", "≡")).forEach { (item, label, glyph) ->
                val selected = item == tab
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { change(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) Color(0xFF244B42) else Panel
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Text(glyph, fontSize = 16.sp, color = if (selected) Mint else Muted)
                        Spacer(Modifier.width(7.dp))
                        Text(label, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Mint else Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDialog(event: TraceEvent, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Label("METHOD CALL #" + event.id)
                Spacer(Modifier.height(8.dp))
                Text(event.shortName, color = White, fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column {
                Setting("METHOD", event.method)
                Spacer(Modifier.height(12.dp))
                Setting("INPUT", "[0] " + event.arg0 + "     [1] " + event.arg1)
                Spacer(Modifier.height(12.dp))
                Setting("OUTCOME", if (event.isError) "throws " + event.exception
                    else "returns " + (event.result ?: "null"))
                Spacer(Modifier.height(12.dp))
                Setting("DURATION", event.duration() + " / " + event.durationNs + " ns")
                Spacer(Modifier.height(12.dp))
                Setting("PROCESS / THREAD", event.pid.toString() + " / " + event.tid)
                Spacer(Modifier.height(12.dp))
                Setting("MONOTONIC TIMESTAMP", event.whenNs.toString() + " ns")
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("CLOSE", color = Mint) } }
    )
}
