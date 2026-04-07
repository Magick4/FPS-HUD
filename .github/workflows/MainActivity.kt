package com.fps.hud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    // ── Overlay permission launcher ──────────────────────────────────────────
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkNotificationPermissionThenStart()
        } else {
            Toast.makeText(this,
                "Overlay permission is required for the FPS HUD.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Notification permission launcher (Android 13+) ───────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startHudService() }

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle toggle intent from notification action
        if (intent?.action == ACTION_TOGGLE) {
            toggleService()
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            showOverlayRationale()
        } else {
            checkNotificationPermissionThenStart()
        }
    }

    // ── Permission helpers ───────────────────────────────────────────────────
    private fun showOverlayRationale() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage(
                "FPS HUD needs the \"Display over other apps\" permission to show " +
                "the FPS counter while you're gaming.\n\nTap OK to open settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                overlayPermLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun checkNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startHudService()
        }
    }

    private fun startHudService() {
        val svc = Intent(this, FpsOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        Toast.makeText(this, "FPS HUD started", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun toggleService() {
        if (FpsOverlayService.isRunning) {
            stopService(Intent(this, FpsOverlayService::class.java))
            Toast.makeText(this, "FPS HUD stopped", Toast.LENGTH_SHORT).show()
        } else {
            startHudService()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.fps.hud.TOGGLE"
    }
}
