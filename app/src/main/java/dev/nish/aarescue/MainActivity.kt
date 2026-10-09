package dev.nish.aarescue

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Home() } }
        }
    }
}

private data class UiState(
    val listener: Boolean,
    val a11y: Boolean,
    val projecting: Boolean,
    val running: Boolean,
    val events: List<RescueLog.Event>,
)

@Composable
private fun Home() {
    val ctx = LocalContext.current
    fun read() = UiState(
        listener = enabled(ctx, "enabled_notification_listeners", MediaWatcher::class.java),
        a11y = enabled(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, MapsClicker::class.java),
        projecting = Rescue.isProjecting(),
        running = MediaWatcher.instance != null && MapsClicker.instance != null,
        events = RescueLog.readEvents(),
    )
    var state by remember { mutableStateOf(read()) }
    var music by remember { mutableStateOf(Prefs.resumeMusic(ctx)) }
    var nav by remember { mutableStateOf(Prefs.resumeNav(ctx)) }
    var enabled by remember { mutableStateOf(Prefs.enabled(ctx)) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { state = read(); delay(1_000) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(state, enabled, onEnabled = { enabled = it; Prefs.setEnabled(ctx, it) }, onInfo = { showInfo = true })

        if (!state.listener || !state.a11y) {
            Section("Setup") {
                SetupRow(
                    done = state.listener,
                    title = "Music control",
                    body = "Lets AA Rescue press play for you",
                ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                SetupRow(
                    done = state.a11y,
                    title = "Maps auto-Start",
                    body = "Taps Start in Maps after a drop. Greyed out? Tap App info → ⋮ → Allow restricted settings.",
                    secondary = "App info" to {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    },
                ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }

        Section("When Android Auto drops") {
            ToggleRow(Icons.Outlined.MusicNote, "Resume music", "Only if it was playing", music, enabled) {
                music = it; Prefs.setResumeMusic(ctx, it)
            }
            ToggleRow(Icons.Outlined.Navigation, "Resume navigation", "Only during an active trip", nav, enabled) {
                nav = it; Prefs.setResumeNav(ctx, it)
            }
        }

        Section("Try it") {
            Text(
                "Play something in YouTube Music, then tap Test. It pauses, and should come back within a couple of seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { Rescue.simulateDrop() }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PlayCircle, null)
                Spacer(Modifier.width(8.dp))
                Text("Test a drop")
            }
        }

        Recent(state.events) { RescueLog.clear(); state = read() }
    }

    if (showInfo) DisableSheet(onDismiss = { showInfo = false })
}

@Composable
private fun Header(state: UiState, enabled: Boolean, onEnabled: (Boolean) -> Unit, onInfo: () -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AA Rescue",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "How to turn off completely") }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        Spacer(Modifier.height(8.dp))
        val green = Color(0xFF34A853)
        val (dot, line) = when {
            !state.running -> MaterialTheme.colorScheme.error to "Not running — finish Setup below"
            !enabled -> MaterialTheme.colorScheme.outline to "Paused — drops are ignored"
            state.projecting -> green to "Running · Android Auto connected, watching"
            else -> green to "Running in background"
        }
        StatusLine(dot, line)
    }
}

@Composable
private fun StatusLine(dot: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisableSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pausing vs. turning off", style = MaterialTheme.typography.titleLarge)
            Text(
                "The switch at the top pauses AA Rescue. It stays loaded but ignores every drop and uses no battery. " +
                    "Flip it back on any time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Android doesn't let an app switch these permissions back on by itself, so turning off completely " +
                    "is done in Settings:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Step("1", "Accessibility → AA Rescue → off") {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Step("2", "Notification access → AA Rescue → off") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Text(
                "With both off, nothing of AA Rescue runs. To remove it entirely, uninstall it like any app. " +
                    "To turn it back on, enable both again (or use Setup on this screen).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: String, text: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(n, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text("Open") }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    body: String,
    secondary: Pair<String, () -> Unit>? = null,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!done) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done && secondary != null) {
                TextButton(onClick = secondary.second, contentPadding = PaddingValues(0.dp)) { Text(secondary.first) }
            }
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text("Turn on") }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Recent(events: List<RescueLog.Event>, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (events.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
        if (events.isEmpty()) {
            Text(
                "Nothing yet. After your next drive, this shows what happened at each drop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        events.forEach { e ->
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    DateUtils.formatDateTime(
                        LocalContext.current, e.time,
                        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun enabled(ctx: android.content.Context, key: String, cls: Class<*>) =
    Settings.Secure.getString(ctx.contentResolver, key).orEmpty().split(':').any {
        ComponentName.unflattenFromString(it) == ComponentName(ctx, cls)
    }
