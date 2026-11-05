package com.lsfg.fork.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import com.lsfg.fork.performance.OperatingMode
import com.lsfg.fork.SessionManager
import com.lsfg.fork.ShellBridge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home screen: start/stop, target package picker, multiplier, flow scale,
 * performance mode, framegen bypass, root status, session FPS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPickDll: () -> Unit,
    onResetDll: () -> Unit
) {
    val ctx = LocalContext.current
    val session = remember { SessionManager.shared }
    val s by session.state.collectAsState()

    // Local UI state (mirrors session state for controls)
    var multiplier by remember { mutableIntStateOf(s.multiplier) }
    var flowScale by remember { mutableFloatStateOf(s.flowScale) }
    var performance by remember { mutableStateOf(s.performance) }
    var bypass by remember { mutableStateOf(s.framegenBypass) }
    var selectedPkg by remember { mutableStateOf(s.targetPkg) }
    var apps by remember { mutableStateOf<List<ShellBridge.AppInfo>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        session.loadSettings(ctx)
        apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ShellBridge.launchableApps(ctx)
        }
    }

    val running = s.running
    val accent = Color(0xFF22D3EE)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LSFG", fontWeight = FontWeight.Bold, color = accent)
                        Spacer(Modifier.width(8.dp))
                        Text("frame interpolation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Session card ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (running) "Session running" else "Ready",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (running) accent else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    val statusLine = buildString {
                        append("root: ${if (s.rootInfo.root) "yes" else "no"}")
                        if (s.rootInfo.ksuNext) append(" (KSU-Next)")
                        append("  |  capture: ${s.captureMode}")
                        if (running) {
                            append("  |  ")
                            append("%.1f fps".format(s.fps))
                            if (s.captureLatencyMs > 0f) append("  cap %.0fms".format(s.captureLatencyMs))
                        }
                        append("  |  ${s.statusMsg}")
                    }
                    Text(statusLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!running) {
                            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF001418))) {
                                Text("Start")
                            }
                        } else {
                            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color(0xFFFFFFFF))) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }

            // --- Target package ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target app", style = MaterialTheme.typography.titleMedium)
                    Text(
                        selectedPkg ?: "(auto: current foreground)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPicker = true }) { Text("Pick app") }
                        if (selectedPkg != null) {
                            TextButton(onClick = { selectedPkg = null; session.setTargetPkg(null) }) { Text("clear") }
                        }
                    }
                }
            }

            // --- Framegen params ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Frame interpolation", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OperatingMode.entries.forEach { mode ->
                            FilterChip(selected = s.operatingMode == mode, onClick = { session.setOperatingMode(mode) }, label = { Text(mode.label) })
                        }
                    }
                    Text("Output limit")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60, 75, 80, 90, 120).forEach { fps ->
                            FilterChip(selected = s.targetFps.toInt() == fps, onClick = { session.setTargetFps(fps.toFloat()) }, label = { Text("$fps") })
                        }
                    }
                    var customFps by remember { mutableStateOf("") }
                    OutlinedTextField(value = customFps, onValueChange = { value ->
                        customFps = value.filter(Char::isDigit).take(3)
                        customFps.toIntOrNull()?.takeIf { it in 15..240 }?.let { session.setTargetFps(it.toFloat()) }
                    }, label = { Text("Custom FPS (15–240)") }, singleLine = true)
                    Text("Total GPU ceiling: ${s.gpuCeiling.toInt()}%")
                    Slider(value = s.gpuCeiling, onValueChange = session::setGpuCeiling, valueRange = 30f..95f, steps = 12)
                    Text("Processing resolution: ${(s.processingScale * 100).toInt()}%")
                    Slider(value = s.processingScale, onValueChange = session::setProcessingScale,
                        valueRange = .25f..1f, steps = 2, enabled = !running)
                    if (running) {
                        Text("Target ${s.effectiveTarget.toInt()} FPS · ${s.effectiveMultiplier}× · GPU ${s.gpuPercent?.let { "%.0f%%".format(it) } ?: "unavailable"}", style = MaterialTheme.typography.bodySmall)
                        Text(s.adaptiveReason, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Fixed multiplier")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1, 2, 3, 4).forEach { m ->
                                FilterChip(
                                    selected = multiplier == m,
                                    onClick = {
                                        multiplier = m
                                        session.setMultiplier(m)
                                    },
                                    label = { Text("$m") }
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Flow scale: ${"%.2f".format(flowScale)}")
                        Slider(
                            value = flowScale,
                            onValueChange = { flowScale = it; session.setFlowScale(it) },
                            valueRange = 0.25f..1f,
                            steps = 3,
                            modifier = Modifier.width(160.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Performance mode (half precision)")
                        Switch(checked = performance, onCheckedChange = { performance = it; session.setPerformance(it) })
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bypass framegen (passthrough)")
                        Switch(checked = bypass, onCheckedChange = { bypass = it; session.setFramegenBypass(it) })
                    }
                }
            }

            // --- Capture mode ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Capture mode", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = s.captureMode == "root",
                            onClick = { session.setCaptureMode("root") },
                            label = { Text("Root") }
                        )
                        FilterChip(
                            selected = s.captureMode == "shizuku",
                            onClick = { session.setCaptureMode("shizuku") },
                            label = { Text("Shizuku") }
                        )
                        FilterChip(
                            selected = s.captureMode == "nonroot",
                            onClick = { session.setCaptureMode("nonroot") },
                            enabled = true,
                            label = { Text("Non-root") }
                        )
                    }
                    Text(
                        when (s.captureMode) {
                            "root" -> "Uses root shell (screencap via su). Highest performance."
                            "shizuku" -> "Uses shell screencap via Shizuku (base64 read-back). Needs Shizuku running."
                            "nonroot" -> "Uses MediaProjection (system screen capture dialog). No root needed."
                            else -> "Capture mode: ${s.captureMode}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- Shader DLL ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shader DLL (framegen library)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.framegenSource == "user") "User-supplied DLL active"
                        else "Bundled lsfg-vk.dll",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.framegenSource == "user") Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickDll) { Text("Select DLL...") }
                        OutlinedButton(onClick = onResetDll) { Text("Reset to bundled") }
                    }
                    Text(
                        "Plug in your own compiled Lossless.dll or lsfg-vk.dll for custom LSFG shaders. Reset to fall back to the bundled library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- Root status ---
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Root / KernelSU", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { session.refreshRoot() }) { Text("refresh") }
                    }
                    Text(
                        "provider: ${s.rootInfo.provider}   ksu-next: ${if (s.rootInfo.ksuNext) "detected" else "n/a"}   uid: ${s.rootInfo.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (s.rootInfo.root) "Root OK: screencap capture path is available." else "No root: capture may fail or run as shell user.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.rootInfo.root) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            onPick = { p ->
                selectedPkg = p.pkg
                session.setTargetPkg(p.pkg)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
fun AppPickerDialog(
    apps: List<ShellBridge.AppInfo>,
    onPick: (ShellBridge.AppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick target app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("search") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    filtered.forEach { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable { onPick(app) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close") } }
    )
}
