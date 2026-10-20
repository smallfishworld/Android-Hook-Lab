package dev.smallfish.hooklab

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.ceil

private val AnalysisBg = Color(0xFF0A1119)
private val AnalysisPanel = Color(0xFF151F2B)
private val AnalysisMint = Color(0xFF67E8C0)
private val AnalysisMuted = Color(0xFF9AABBC)
private val AnalysisWhite = Color(0xFFF2F6FB)
private val AnalysisLine = Color(0xFF293849)
private val AnalysisBlue = Color(0xFFA3B7FF)
private val AnalysisRed = Color(0xFFFFAA91)

private data class ImportedCall(
    val id: String,
    val parent: String?,
    val method: String,
    val tid: Int,
    val pid: Int,
    val startNs: Long,
    val durationNs: Long,
    val threw: Boolean
)

private data class MethodProfile(
    val name: String, val calls: Int, val errors: Int,
    val p50: Double, val p95: Double, val p99: Double, val totalMs: Double
)

private data class TraceReport(
    val sourceName: String,
    val events: List<ImportedCall>,
    val methods: List<MethodProfile>,
    val failures: Int,
    val firstNs: Long,
    val lastNs: Long,
    val treeDepths: Map<String, Int>,
    val truncated: Boolean
)

private fun percentile(values: List<Long>, quantile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return sorted[position] / 1_000_000.0
}

private fun reportFor(label: String, events: List<ImportedCall>, truncated: Boolean): TraceReport {
    val grouped = events.groupBy { it.method }
    val profiles = grouped.map { (name, calls) ->
        val times = calls.map { it.durationNs }
        MethodProfile(
            name, calls.size, calls.count { it.threw },
            percentile(times, .50), percentile(times, .95), percentile(times, .99),
            times.sumOf { it.toDouble() } / 1_000_000.0
        )
    }.sortedByDescending { it.totalMs }
    val byKey = events.associateBy { Pair(it.pid, it.id) }
    val depths = HashMap<String, Int>()
    for (call in events) {
        var depth = 0
        var parent = call.parent
        val seen = HashSet<String>()
        while (parent != null && depth < 9 && seen.add(parent)) {
            val ancestor = byKey[call.pid to parent] ?: break
            if (ancestor.tid != call.tid) break
            depth++
            parent = ancestor.parent
        }
        depths[call.id + "@" + call.pid] = depth
    }
    return TraceReport(
        sourceName = label, events = events.sortedBy { it.startNs },
        methods = profiles, failures = events.count { it.threw },
        firstNs = events.minOfOrNull { it.startNs } ?: 0,
        lastNs = events.maxOfOrNull { it.startNs + it.durationNs } ?: 0,
        treeDepths = depths,
        truncated = truncated
    )
}

/**
 * Standalone viewer for a user-selected Frida JSONL trace.
 * Parsing is strictly bounded and never evaluates scripts or renders raw args.
 */
class TraceAnalysisActivity : ComponentActivity() {
    private var report by mutableStateOf<TraceReport?>(null)
    private var error by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { runCatching { loadTrace(uri) } }
                result.onSuccess { report = it; error = null }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                busy = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 17, 25)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 25)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = AnalysisMint, onPrimary = AnalysisBg,
                background = AnalysisBg, surface = AnalysisPanel,
                onSurface = AnalysisWhite
            )) {
                TraceViewer(report, error, busy, onImport = {
                    importFile.launch(arrayOf("application/json", "application/x-ndjson",
                        "text/plain", "application/octet-stream", "*/*"))
                }, onBack = { finish() })
            }
        }
    }

    private fun loadTrace(uri: Uri): TraceReport {
        val records = ArrayList<ImportedCall>()
        var bytes = 0
        val limit = 20_000
        val maxBytes = 8 * 1024 * 1024
        var truncated = false
        val file = contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
        BufferedReader(InputStreamReader(file, Charsets.UTF_8)).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                bytes += line.toByteArray(Charsets.UTF_8).size + 1
                if (bytes > maxBytes) {
                    throw IllegalArgumentException("File exceeds 8 MB import budget")
                }
                if (line.length > 128 * 1024) throw IllegalArgumentException("Event line too long")
                if (line.isBlank()) continue
                val value = try { JSONObject(line) }
                    catch (ex: Exception) {
                        throw IllegalArgumentException("Bad JSON at line " + lineNumber, ex)
                    }
                if (value.optInt("schema_version") != 1 ||
                    value.optString("engine") != "FRIDA") {
                    throw IllegalArgumentException("Expected Frida schema v1 at line " + lineNumber)
                }
                val method = value.optString("method").take(200)
                val id = value.optString("call_id").take(80)
                val start = value.optLong("monotonic_time_ns", -1)
                val duration = value.optLong("duration_ns", -1)
                if (method.isEmpty() || id.isEmpty() || start < 0 || duration < 0) {
                    throw IllegalArgumentException("Invalid timing/method fields at line " + lineNumber)
                }
                records.add(ImportedCall(
                    id, value.optString("parent_call_id").takeIf { it.isNotEmpty() && it != "null" },
                    method, value.optInt("tid"), value.optInt("pid"), start, duration,
                    value.optString("outcome") == "throw"
                ))
                if (records.size == limit) {
                    truncated = reader.readLine() != null
                    break
                }
            }
        }
        return reportFor("Frida JSONL", records, truncated)
    }
}

@Composable
private fun TraceViewer(
    report: TraceReport?, error: String?, busy: Boolean,
    onImport: () -> Unit, onBack: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().background(AnalysisBg)) {
        Row(Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = AnalysisMint, fontSize = 33.sp,
                modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("TRACE ANALYZER", fontSize = 20.sp, fontWeight = FontWeight.Black,
                    color = AnalysisWhite)
                Text("M2 · FRIDA OFFLINE SESSION", fontSize = 10.sp, color = AnalysisMuted)
            }
        }
        HorizontalDivider(color = AnalysisLine)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AnalysisMint, contentColor = AnalysisBg)) {
                    Text(if (busy) "READING TRACE…" else "IMPORT FRIDA JSONL ↗",
                        fontWeight = FontWeight.Bold)
                }
            }
            if (error != null) item { AnalysisMessage("IMPORT FAILED", error, AnalysisRed) }
            if (report == null) {
                item { AnalysisMessage("NO TRACE IMPORTED",
                    "Export a session using the desktop Frida console. Transfer its JSONL file to this device and import it here. Only method names and timing are displayed; arguments are never rendered.",
                    AnalysisBlue) }
            } else {
                item {
                    AnalysisMessage("TRACE LOADED",
                        report.sourceName + " · " + report.events.size +
                            " calls" + if (report.truncated) " · truncated at 20k" else "",
                        AnalysisMint)
                }
                item {
                    val duration = (report.lastNs - report.firstNs).coerceAtLeast(0L)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnalysisMetric("CALLS", report.events.size.toString(), Modifier.weight(1f))
                        AnalysisMetric("THROWS", report.failures.toString(), Modifier.weight(1f))
                        AnalysisMetric("SPAN (S)",
                            String.format(Locale.ROOT, "%.2f", duration / 1e9),
                            Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("HOT METHODS", "CALL TREE").forEachIndexed { i, label ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { tab = i },
                                color = if (tab == i) Color(0xFF244B42) else AnalysisPanel,
                                shape = RoundedCornerShape(13.dp),
                                border = BorderStroke(1.dp, AnalysisLine)
                            ) {
                                Text(label, modifier = Modifier.padding(13.dp),
                                    color = if (tab == i) AnalysisMint else AnalysisMuted,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (tab == 0) {
                    items(report.methods.take(200)) { method ->
                        Surface(color = AnalysisPanel, shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp, AnalysisLine)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(method.name, color = AnalysisWhite,
                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Text("${method.calls} calls · ${method.errors} throws · total " +
                                    String.format(Locale.ROOT, "%.2fms", method.totalMs),
                                    color = AnalysisMuted, fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("P50 ${ms(method.p50)}  /  P95 ${ms(method.p95)}  /  P99 ${ms(method.p99)}",
                                    color = AnalysisMint, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                } else {
                    item {
                        AnalysisMessage("EXPLICIT NESTING",
                            "Depth uses parent_call_id on the same PID/TID. Async calls are shown separately, not guessed from adjacent timestamps.",
                            AnalysisBlue)
                    }
                    items(report.events.takeLast(400), key = { it.id + ":" + it.pid }) { e ->
                        val depth = report.treeDepths[e.id + "@" + e.pid] ?: 0
                        Surface(color = AnalysisPanel, shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AnalysisLine)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("  ".repeat(depth) + "↳ " + e.method.substringAfterLast('.'),
                                    color = if (e.threw) AnalysisRed else AnalysisWhite,
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("TID ${e.tid} · " +
                                    String.format(Locale.ROOT, "%.3f ms", e.durationNs / 1e6),
                                    color = AnalysisMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ms(d: Double): String = String.format(Locale.ROOT, "%.3f", d)

@Composable
private fun AnalysisMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, AnalysisLine), color = AnalysisPanel) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = AnalysisMuted, fontSize = 9.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = AnalysisMint, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AnalysisMessage(title: String, message: String, color: Color) {
    Surface(shape = RoundedCornerShape(15.dp), color = AnalysisPanel,
        border = BorderStroke(1.dp, AnalysisLine)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(message, color = AnalysisMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
