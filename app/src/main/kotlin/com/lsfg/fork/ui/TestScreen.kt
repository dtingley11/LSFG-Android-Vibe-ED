package com.lsfg.fork.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
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
import com.lsfg.fork.SessionManager
import com.lsfg.fork.overlay.OverlayHost
import com.lsfg.fork.ShellBridge

/**
 * Built-in test tab (bug 4): a spinning 30 fps cube that exercises the full
 * framegen pipeline (test source -> LSFG v3.1 -> overlay presenter) without
 * needing a target app or screen capture. Lets the user validate framegen
 * settings (multiplier, flow scale, performance) in real time.
 */
@Composable
fun TestScreen() {
    val ctx = LocalContext.current
    val session = remember { SessionManager.shared }
    val s by session.state.collectAsState()
    val accent = Color(0xFF22D3EE)
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val overlayGranted = Settings.canDrawOverlays(ctx)

    fun startTest() {
        errorMsg = null
        if (session.isRunning()) {
            toast(ctx, "Already running")
            return
        }
        if (!overlayGranted) {
            Log.e("Test", "overlay permission missing")
            errorMsg = "Grant 'Display over other apps' first (Config tab)."
            return
        }
        // Force the test-pattern source on.
        session.setTestPattern(true)
        Log.i("Test", "starting test pattern (framegen=${if (s.framegenBypass) "off" else "on"}, mult=${s.multiplier})")

        if (session.isRunning()) {
            session.endSession()
        }
        // Attach the overlay window (fast, main thread). The actual Surface is
        // awaited on a background thread (never block the main thread, or
        // surfaceCreated deadlocks).
        val attached = OverlayHost.attach()
        if (!attached) {
            errorMsg = "Overlay attach failed. Grant overlay permission and retry."
            Log.e("Test", "attach failed (window)")
            return
        }
        // Await the surface off the UI thread, then start the session.
        Thread({
            val surf = OverlayHost.awaitSurface(6000L)
            if (surf == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    errorMsg = "Overlay surface never became ready. Grant overlay permission and retry."
                    Log.e("Test", "surface wait failed")
                }
                return@Thread
            }
            val (w, h) = OverlayHost.size()
            session.beginSession(surf, w, h, ctx, null)
        }, "lsfg-test-begin").apply { isDaemon = true; start() }
    }

    fun stopTest() {
        Log.i("Test", "stop test")
        session.endSession()
        // Detach so the test doesn't keep a fullscreen overlay while idle.
        try { OverlayHost.detach() } catch (_: Throwable) {}
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Framegen self-test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Renders a spinning cube at 30 fps and runs it through the SAME pipeline as real capture " +
                    "(source -> LSFG v3.1 -> overlay). If the overlay is smooth at 2x/3x/4x, frame generation " +
                    "is working. If it stutters at 30 fps, frames are passing through.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Multiplier", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3, 4).forEach { m ->
                            FilterChip(selected = s.multiplier == m,
                                onClick = { session.setMultiplier(m) },
                                label = { Text("$m") })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Bypass framegen (passthrough)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.framegenBypass, onCheckedChange = { session.setFramegenBypass(it) })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val running = s.running
                // Honesty fix: "running" only means the session was CREATED. It does
                // not mean a single frame came out. Previously the box went green
                // and said "framegen ON" even while the GPU path was deadlocking and
                // delivering nothing, so a totally broken pipeline looked healthy
                // and there was no way to tell a pass from a failure.
                //
                // Now the colour and the wording track ACTUAL OUTPUT. Zero frames
                // for a sustained period is reported as a failure, not as success.
                val producing = running && s.fps > 0.5f
                val stalled = running && !producing
                val bg = when {
                    producing -> Color(0xFF0F2A1A)   // green: frames are flowing
                    stalled -> Color(0xFF3A1D0F)     // amber: session up, no frames
                    else -> Color(0xFF1E293B)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(bg, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        if (running) "%s  |  %.0f fps  |  mult x%d  |  framegen %s"
                            .format(
                                if (producing) "RUNNING" else "NO FRAMES",
                                s.fps, s.multiplier,
                                if (s.framegenReady) "ON" else "passthrough"
                            )
                        else "idle",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            producing -> Color(0xFF4ADE80)
                            stalled -> Color(0xFFFBBF24)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (stalled) {
                    Text(
                        "Nothing is being drawn. The session was created, but the GPU " +
                        "pipeline is not delivering frames, so a test here has NOT passed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }
                Text("state: ${s.statusMsg}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val err = errorMsg
                if (err != null) {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF87171))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!running) {
                        Button(onClick = { startTest() },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF001418))) {
                            Text("Start test")
                        }
                    } else {
                        Button(onClick = { stopTest() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White)) {
                            Text("Stop test")
                        }
                    }
                }
                Text(
                    "Tip: the test cube draws OVER whatever is on screen. For a clean look, open a dark screen " +
                    "or the launcher first. The overlay does not steal your touch input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}
