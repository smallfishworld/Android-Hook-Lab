package dev.smallfish.hooklab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.smallfish.hooklab.protocol.EventContract
import dev.smallfish.hooklab.protocol.MobileControlContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Dark = Color(0xFF0A1119)
private val Card = Color(0xFF151F2B)
private val Border = Color(0xFF293849)
private val Mint = Color(0xFF67E8C0)
private val Foreground = Color(0xFFF2F6FB)
private val Secondary = Color(0xFF9AABBC)
private val Warn = Color(0xFFFFAA91)

private data class OnDeviceSnapshot(
    val globalEnabled: Boolean,
    val perMethod: Map<String, Boolean>,
    val catalog: List<String>,
    val lastSeen: Long
)

private val knownMethodDescriptions = listOf(
    MobileControlContract.METHOD_BRIGHTNESS_FLOAT to "float + int → float",
    MobileControlContract.METHOD_BRIGHTNESS_INT to "int + int → int",
    MobileControlContract.METHOD_VALIDATE to "int → int / exception",
)

/**
 * The M2 Android-native control plane. No computer, ADB, shell, Frida Server or
 * network permission is needed to adjust the project's live Demo capture rules.
 */
class MobileHookActivity : ComponentActivity() {
    private val provider = Uri.parse("content://" + MobileControlContract.AUTHORITY)
    private var screenActive by mutableStateOf(false)
    private var current by mutableStateOf<OnDeviceSnapshot?>(null)
    private var problem by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = android.graphics.Color.rgb(10, 17, 25)
        window.navigationBarColor = android.graphics.Color.rgb(10, 17, 25)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Mint, onPrimary = Dark, background = Dark,
                onBackground = Foreground, surface = Card, onSurface = Foreground
            )) {
                val observing = screenActive
                LaunchedEffect(observing) {
                    if (observing) {
                        while (isActive) {
                            readSnapshot()
                            delay(1200)
                        }
                    }
                }
                MobileConsole(
                    snapshot = current,
                    error = problem,
                    busy = busy,
                    onBack = { finish() },
                    onOpenDemo = ::openDemo,
                    onRefresh = { lifecycleScope.launch { readSnapshot() } },
                    onUpdate = ::updateRule,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
    }

    override fun onPause() {
        screenActive = false
        super.onPause()
    }

    private suspend fun readSnapshot() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val prefs = contentResolver.call(
                    provider, MobileControlContract.READ_POLICY, null, null
                ) ?: error("No local capture policy provider")
                val discovery = contentResolver.call(
                    provider, MobileControlContract.READ_CATALOG, null, null
                )
                val rules = MobileControlContract.SUPPORTED.associateWith { key ->
                    prefs.getBoolean(key, true)
                }
                OnDeviceSnapshot(
                    globalEnabled = prefs.getBoolean(
                        MobileControlContract.KEY_CAPTURE_ENABLED, true
                    ),
                    perMethod = rules,
                    catalog = discovery?.getStringArrayList(
                        MobileControlContract.KEY_CATALOG
                    )?.toList().orEmpty(),
                    lastSeen = prefs.getLong(
                        MobileControlContract.KEY_REPORTED_AT_MS, -1L
                    )
                )
            }
        }
        result.onSuccess { current = it; problem = null }
            .onFailure { problem = it.message ?: it.javaClass.simpleName }
    }

    private fun updateRule(rule: String, enabled: Boolean) {
        if (busy || !MobileControlContract.isRule(rule)) return
        lifecycleScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val update = Bundle().apply {
                        putString(MobileControlContract.KEY_METHOD_ID, rule)
                        putBoolean(MobileControlContract.KEY_ENABLED, enabled)
                    }
                    val answer = contentResolver.call(
                        provider, MobileControlContract.UPDATE_POLICY, null, update
                    )
                    if (answer?.getBoolean("ok") != true) error("Rule update rejected")
                }
            }
            result.onFailure { problem = it.message ?: "Unable to update capture" }
            readSnapshot()
            busy = false
        }
    }

    private fun openDemo() {
        val intent: Intent? = packageManager.getLaunchIntentForPackage(
            EventContract.TARGET_PACKAGE
        )
        if (intent == null) Toast.makeText(this,
            "Install Hook Lab Demo first", Toast.LENGTH_SHORT).show()
        else startActivity(intent)
    }
}

@Composable
private fun MobileConsole(
    snapshot: OnDeviceSnapshot?,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenDemo: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: (String, Boolean) -> Unit
) {
    val last = snapshot?.lastSeen ?: -1L
    val ageMs = if (last < 0 || last > SystemClock.elapsedRealtime()) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - last
    val processReachable = ageMs <= 4500

    Column(Modifier.fillMaxSize().background(Dark).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Mint, fontSize = 32.sp,
                modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("ON-DEVICE HOOK", color = Foreground, fontSize = 19.sp,
                    fontWeight = FontWeight.Black)
                Text("VECTOR · LOCAL CONTROL · NO ADB", color = Secondary,
                    fontSize = 10.sp)
            }
            Text("M2", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Border)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = Card,
                    border = BorderStroke(1.dp, Border)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("SELECTED TARGET", fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, color = Mint)
                        Spacer(Modifier.height(7.dp))
                        Text("Hook Lab Demo", color = Foreground,
                            fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text(EventContract.TARGET_PACKAGE,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = Secondary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (processReachable)
                                "●  TARGET IPC SEEN RECENTLY"
                            else "○  AWAITING TARGET PROCESS",
                            color = if (processReachable) Mint else Secondary,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "A recent IPC heartbeat confirms communication, not that every Hook was installed.",
                            color = Secondary, fontSize = 11.sp, lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(13.dp))
                        Button(onClick = onOpenDemo, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Mint, contentColor = Dark
                            )) { Text("OPEN TARGET APP ↗", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            if (error != null) item {
                NoteCard("CONTROL ERROR", error, Warn)
            }
            item {
                NoteCard("CAPTURE SWITCHES",
                    "Changes propagate to the running Demo within about 1 second. " +
                        "Switches control recording, NOT physical Vector Hook unregistration.",
                    Mint)
            }
            item {
                RuleCard(
                    title = "MASTER CAPTURE",
                    subtitle = "Enable or suspend all Demo method events",
                    enabled = snapshot?.globalEnabled ?: true,
                    readOnly = snapshot == null || busy,
                    warning = true,
                    onToggle = { onUpdate(MobileControlContract.GLOBAL, it) }
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("DISCOVERED TARGET METHODS",
                        color = Foreground, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("REFRESH", color = Mint) }
                }
            }
            if (snapshot?.catalog.isNullOrEmpty()) item {
                NoteCard("NO METHODS REPORTED",
                    "Enable this module in Vector with scope Hook Lab Demo, then force-stop " +
                        "and restart the target. The catalog is reported by reflection in the target process.",
                    Warn)
            }
            knownMethodDescriptions.forEach { (name, signature) ->
                item {
                    val discovered = snapshot?.catalog?.any { catalog ->
                        val fragment = "ImageProcessor#" +
                            name.substringAfter("ImageProcessor.")
                                .substringBefore("(") + "(" +
                            name.substringAfter("(").substringBefore(")") + "):"
                        catalog.contains(fragment)
                    } == true
                    RuleCard(
                        title = name.substringAfter("ImageProcessor."),
                        subtitle = signature +
                            if (discovered) " · discovered" else " · awaiting discovery",
                        enabled = snapshot?.perMethod?.get(name) ?: true,
                        readOnly = snapshot == null || busy,
                        warning = false,
                        onToggle = { onUpdate(name, it) }
                    )
                }
            }
            item {
                NoteCard("SCOPE LIMIT",
                    "This release reflects the Demo's actual ImageProcessor class. " +
                        "Capture is implemented for three exact overloads. Arbitrary " +
                        "third-party classes and dynamically adding new interceptors " +
                        "are not enabled yet.",
                    Secondary)
            }
        }
    }
}

@Composable
private fun RuleCard(
    title: String, subtitle: String, enabled: Boolean,
    readOnly: Boolean, warning: Boolean, onToggle: (Boolean) -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = Card, border = BorderStroke(1.dp, Border)) {
        Row(Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Foreground, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(7.dp))
                Text(subtitle, color = Secondary, fontSize = 11.sp,
                    lineHeight = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onToggle,
                enabled = !readOnly,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Dark,
                    checkedTrackColor = if (warning) Warn else Mint,
                    uncheckedTrackColor = Border
                ))
        }
    }
}

@Composable
private fun NoteCard(title: String, detail: String, tint: Color) {
    Surface(shape = RoundedCornerShape(15.dp), color = Card,
        border = BorderStroke(1.dp, Border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint)
            Spacer(Modifier.height(7.dp))
            Text(detail, fontSize = 12.sp, lineHeight = 18.sp, color = Secondary)
        }
    }
}
