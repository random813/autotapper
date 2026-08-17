package com.autotapper

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Setup screen: guides the user through the two one-time permissions,
 * then hands off to the floating overlay. Everything else happens over
 * other apps via OverlayService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "AutoTapper"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Place tap points on the screen and let them run. Two one-time permissions are needed:"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad)
        })

        // --- Step 1: overlay permission ---
        overlayStatus = TextView(this).apply { textSize = 15f }
        root.addView(sectionTitle("1. Draw over other apps"))
        root.addView(overlayStatus)
        root.addView(Button(this).apply {
            text = "Open overlay settings"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        // --- Step 2: accessibility service ---
        accessibilityStatus = TextView(this).apply { textSize = 15f }
        root.addView(sectionTitle("2. Accessibility service (performs the taps)"))
        root.addView(accessibilityStatus)
        root.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // --- Start / stop ---
        root.addView(sectionTitle("3. Go"))
        startButton = Button(this).apply {
            setOnClickListener {
                if (OverlayService.isRunning) {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                    updateStatus()
                } else if (Settings.canDrawOverlays(this@MainActivity)) {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, OverlayService::class.java)
                    )
                    // Send the user home so the bubble floats over whatever they open.
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            }
        }
        root.addView(startButton)
        root.addView(TextView(this).apply {
            text = "The floating bubble appears over other apps. Tap it for controls: " +
                "add a tap point, then Start. Drag a marker to move it, drag its handle to " +
                "resize (small = exact crosshair, big = random area). Tap a marker to select " +
                "it, tap again to lock it."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, 4)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = TapAccessibilityService.isRunning
        overlayStatus.text = if (overlayOk) "✓ Granted" else "✗ Not granted yet"
        overlayStatus.setTextColor(if (overlayOk) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        accessibilityStatus.text = if (accessOk) "✓ Enabled" else "✗ Not enabled yet"
        accessibilityStatus.setTextColor(if (accessOk) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        startButton.text = if (OverlayService.isRunning) "Stop floating controls" else "Start floating controls"
        startButton.isEnabled = overlayOk
    }
}
