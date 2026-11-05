package com.lsfg.fork.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lsfg.fork.Log
import com.lsfg.fork.RootChecker
import com.lsfg.fork.SessionManager
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configuration / permission tab (bug 2).
 *
 * Verifies that every permission the active capture mode needs is actually
 * granted BEFORE a session can start, and runs a real capture probe so the
 * user sees whether the chosen path will produce frames.
 */
@Composable
fun ConfigScreen(onStart: () -> Unit) {
    val ctx = LocalContext.current as Context
    val session = remember { SessionManager.shared }
    val s by session.state.collectAsState()
    val accent = Color(0xFF22D3EE)

    // Live permission / probe state.
    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    var notifGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33)
                (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
            else true
        )
    }
    var probeBusy by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<RootChecker.ProbeResult?>(null) }
    // Shizuku availability, so the Shizuku row reports something truthful.
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }

    fun refreshPerms() {
        overlayGranted = android.provider.Settings.canDrawOverlays(ctx)
        notifGranted = if (Build.VERSION.SDK_INT >= 33)
            (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
        else true
        // Best-effort probe; wrapped so a failure can never break the card.
        try {
            shizukuInstalled = com.lsfg.fork.ShellBridge.shizukuInstalled()
            shizukuRunning = shizukuInstalled && com.lsfg.fork.ShellBridge.shizukuRunning()
        } catch (_: Throwable) {
            shizukuInstalled = false
            shizukuRunning = false
        }
    }

    // CRITICAL FIX (bug: "Grant the permissions above first" always shows):
    // the chips were only refreshed on a manual tap and onDispose. When the user
    // taps "Grant overlay permission" the system settings page opens (this
    // composable leaves STARTED), and on return the chips were NEVER re-read, so
    // they stayed red and Start stayed disabled even though the permission was
    // actually granted. Refresh on every ON_START (return from settings / picker)
    // so the chips always reflect the live permission state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) refreshPerms()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshPerms()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            refreshPerms()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Permissions card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                PermissionRow(
                    label = "Display over other apps (overlay)",
                    detail = "Required for every capture mode. Draws the frame-generated overlay.",
                    state = if (overlayGranted) PermState.OK else PermState.MISSING
                )
                PermissionRow(
                    label = "Notifications (Android 13+)",
                    detail = "Required for every capture mode. Keeps the capture service alive.",
                    state = if (notifGranted) PermState.OK else PermState.MISSING
                )
                PermissionRow(
                    label = "Screen capture (non-root mode only)",
                    detail = when {
                        s.captureMode != "nonroot" -> "Not needed: you are using the ${s.captureMode} mode."
                        SessionManager.pendingProjectionData != null -> "MediaProjection consent recorded."
                        else -> "Tap Start session and accept the system capture dialog."
                    },
                    state = when {
                        s.captureMode != "nonroot" -> PermState.NOT_NEEDED
                        SessionManager.pendingProjectionData != null -> PermState.OK
                        else -> PermState.MISSING
                    }
                )
                PermissionRow(
                    label = "Shizuku (Shizuku mode only)",
                    detail = when {
                        s.captureMode != "shizuku" -> "Not needed: you are using the ${s.captureMode} mode."
                        !shizukuInstalled -> "Shizuku app is not installed."
                        !shizukuRunning -> "Installed but not running. Open Shizuku and start it."
                        else -> "Shizuku is running."
                    },
                    state = when {
                        s.captureMode != "shizuku" -> PermState.NOT_NEEDED
                        shizukuRunning -> PermState.OK
                        else -> PermState.MISSING
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            refreshPerms()
                            Log.i("Config", "refresh permissions")
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF001418))
                    ) { Text("Refresh") }

                    if (Build.VERSION.SDK_INT >= 23 && !overlayGranted) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                            .setPackage(ctx.packageName)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (t: Throwable) {
                                    Log.e("Config", "open overlay settings: ${t.message}")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Grant overlay") }
                    }
                }

                // Plain-language summary so the whole card reads at a glance.
                val blockers = buildList {
                    if (!overlayGranted) add("overlay")
                    if (!notifGranted) add("notifications")
                    if (s.captureMode == "nonroot" && SessionManager.pendingProjectionData == null)
                        add("screen capture consent")
                    if (s.captureMode == "shizuku" && !shizukuRunning) add("Shizuku")
                }
                Text(
                    if (blockers.isEmpty()) "All required permissions are granted."
                    else "Still needed: ${blockers.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blockers.isEmpty()) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                )
            }
        }

        // ---- Capture mode card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Capture mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.captureMode == "root",
                        onClick = { session.setCaptureMode("root") },
                        label = { Text("Root") })
                    FilterChip(selected = s.captureMode == "shizuku",
                        onClick = { session.setCaptureMode("shizuku") },
                        label = { Text("Shizuku") })
                    FilterChip(selected = s.captureMode == "nonroot",
                        onClick = { session.setCaptureMode("nonroot") },
                        label = { Text("Non-root") })
                }
                Text(
                    when (s.captureMode) {
                        "root" -> "Root screencap -> PNG in app files -> direct read. Highest performance. Needs a working root."
                        "shizuku" -> "Shizuku/shell screencap -> base64 read. Needs Shizuku running."
                        "nonroot" -> "MediaProjection (system consent dialog). No root needed."
                        else -> "mode: ${s.captureMode}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Capture probe card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Capture self-test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Runs a real screencap round-trip to confirm the chosen mode can actually produce frames before a session starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        probeBusy = true
                        probeResult = null
                        Log.i("Config", "capture probe start (mode=${s.captureMode})")
                        // Run the probe off the UI thread, then post the result back.
                        runProbe(ctx) { r ->
                            probeResult = r
                            probeBusy = false
                        }
                    },
                    enabled = !probeBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF001418))
                ) { Text(if (probeBusy) "Testing..." else "Test capture now") }

                val r = probeResult
                if (r != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (r.ok) Color(0xFF0F2A1A) else Color(0xFF2A0F0F),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            if (r.ok) "PASS" else "FAIL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (r.ok) Color(0xFF4ADE80) else Color(0xFFF87171)
                        )
                    }
                    Text(
                        "${r.detail}  (${r.ms}ms, ${r.bytes}B)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.ok) Color(0xFF4ADE80) else Color(0xFFF87171)
                    )
                }
            }
        }

        // ---- Root status card ----
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
                    if (s.rootInfo.root) "Root detected." else "No root detected (root mode may fail; try nonroot).",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.rootInfo.root) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                )
            }
        }

        // ---- Start card ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val ready = overlayGranted && notifGranted &&
                        (s.captureMode != "nonroot" || SessionManager.pendingProjectionData != null)
                Button(
                    onClick = {
                        Log.i("Config", "Start pressed (ready=$ready)")
                        onStart()
                    },
                    enabled = ready && !session.isRunning(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ready) Color(0xFF16A34A) else Color(0xFF334155),
                        contentColor = Color.White
                    )
                ) {
                    Text(if (session.isRunning()) "Running..." else "Start session")
                }
                if (!ready) {
                    Text(
                        "Grant the permissions above first (overlay + notifications${if (s.captureMode == "nonroot") " + screen capture" else ""}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusChip(ok: Boolean, muted: Boolean = false) {
    val color = if (muted) Color(0xFF64748B) else (if (ok) Color(0xFF4ADE80) else Color(0xFFF87171))
    Box(
        Modifier
            .size(10.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
    )
}

/** Explicit permission states. A bare dot was ambiguous: grey could mean
 *  granted, denied, or "does not apply to your mode". Words remove the doubt. */
private enum class PermState { OK, MISSING, NOT_NEEDED }

@Composable
private fun PermissionRow(label: String, detail: String, state: PermState) {
    val color = when (state) {
        PermState.OK -> Color(0xFF4ADE80)
        PermState.MISSING -> Color(0xFFF87171)
        PermState.NOT_NEEDED -> Color(0xFF64748B)
    }
    val text = when (state) {
        PermState.OK -> "Granted"
        PermState.MISSING -> "Not granted"
        PermState.NOT_NEEDED -> "Not needed"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(color, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Run a capture probe on a background thread and report the result on the UI thread.
private fun runProbe(ctx: Context, onDone: (RootChecker.ProbeResult) -> Unit) {
    Thread({
        val r = RootChecker.testCapture(ctx)
        Log.i("Config", "capture probe result: ok=${r.ok} detail=${r.detail}")
        android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(r) }
    }, "lsfg-probe").apply { isDaemon = true; start() }
}
