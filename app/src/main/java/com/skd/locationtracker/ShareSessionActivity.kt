package com.skd.locationtracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

/**
 * Share Live Location screen.
 *
 * Broadcasting starts automatically as soon as this screen opens
 * (no "Start Broadcasting" button needed — the intent of opening this
 * screen IS to share). The user can:
 *   • Share the 8-char code + deep link via any installed app
 *   • Copy the code to clipboard
 *   • Stop sharing (marks session inactive in Firebase, finishes screen)
 *   • Generate a new code (only available while not broadcasting)
 */
class ShareSessionActivity : AppCompatActivity() {

    private lateinit var tvSessionCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View
    private lateinit var layoutStatus: View
    private lateinit var cardLiveStats: View
    private lateinit var tvBcastLat: TextView
    private lateinit var tvBcastLng: TextView
    private lateinit var tvBcastSpeed: TextView
    private lateinit var tvBcastUpdated: TextView
    private lateinit var btnStopSharing: MaterialButton
    private lateinit var btnShareVia: MaterialButton
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var btnNewCode: MaterialButton

    private var sessionId: String = ""
    private var isBroadcasting = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── Permission launcher ───────────────────────────────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startBroadcasting()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to share your location",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    // ── Location broadcast receiver ───────────────────────────────────────────
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationService.ACTION_LOCATION_UPDATE) return
            val lat     = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
            val lng     = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
            val speed   = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
            val accuracy = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, -1f)
            val bearing = intent.getFloatExtra(LocationService.EXTRA_BEARING, -1f)

            tvBcastLat.text   = "%.5f°".format(lat)
            tvBcastLng.text   = "%.5f°".format(lng)
            tvBcastSpeed.text = "%.1f km/h".format(speed)
            tvBcastUpdated.text = "Sent at ${timeFormatter.format(Date())}"

            if (isBroadcasting) {
                FirebaseLocationRepo.pushLocation(
                    sessionId,
                    FirebaseLocationRepo.LiveLocation(
                        lat      = lat,
                        lng      = lng,
                        speed    = speed.toDouble(),
                        accuracy = accuracy.toDouble(),
                        bearing  = bearing.toDouble(),
                        timestamp = System.currentTimeMillis(),
                        active   = true
                    )
                )
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_session)

        bindViews()
        setupToolbar()

        sessionId = LocationSharingManager.getOrCreateSessionId(this)
        tvSessionCode.text = sessionId

        btnCopyCode.setOnClickListener {
            copyToClipboard(sessionId)
            Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show()
        }

        btnShareVia.setOnClickListener { shareCodeAndLink() }

        btnStopSharing.setOnClickListener { stopSharingAndExit() }

        btnNewCode.setOnClickListener {
            if (isBroadcasting) {
                Toast.makeText(this, "Stop sharing before generating a new code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sessionId = LocationSharingManager.resetSessionId(this)
            tvSessionCode.text = sessionId
            Toast.makeText(this, "New code generated", Toast.LENGTH_SHORT).show()
        }

        // Auto-start broadcasting when screen opens
        if (LocationSharingManager.isSharing(this)) {
            // Was already sharing — restore state
            setBroadcastState(true)
        } else {
            autoStartBroadcasting()
        }
    }

    private fun bindViews() {
        tvSessionCode   = findViewById(R.id.tvSessionCode)
        tvStatus        = findViewById(R.id.tvStatus)
        statusDot       = findViewById(R.id.statusDot)
        layoutStatus    = findViewById(R.id.layoutStatus)
        cardLiveStats   = findViewById(R.id.cardLiveStats)
        tvBcastLat      = findViewById(R.id.tvBcastLat)
        tvBcastLng      = findViewById(R.id.tvBcastLng)
        tvBcastSpeed    = findViewById(R.id.tvBcastSpeed)
        tvBcastUpdated  = findViewById(R.id.tvBcastUpdated)
        btnStopSharing  = findViewById(R.id.btnStopSharing)
        btnShareVia     = findViewById(R.id.btnShareVia)
        btnCopyCode     = findViewById(R.id.btnCopyCode)
        btnNewCode      = findViewById(R.id.btnNewCode)
    }

    private fun setupToolbar() {
        val toolbar =
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // ── Broadcasting control ──────────────────────────────────────────────────

    /** Called once on screen open — requests permission then starts broadcasting. */
    private fun autoStartBroadcasting() {
        if (PermissionUtils.hasLocationPermission(this)) {
            startBroadcasting()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startBroadcasting() {
        if (!PermissionUtils.hasLocationPermission(this)) return
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        LocationSharingManager.setSharing(this, true)
        setBroadcastState(true)
    }

    /** Stops broadcasting, marks the session inactive so receivers auto-disconnect. */
    private fun stopSharingAndExit() {
        stopService(Intent(this, LocationService::class.java))
        FirebaseLocationRepo.deactivateSession(sessionId)
        LocationSharingManager.setSharing(this, false)
        isBroadcasting = false
        finish()
    }

    private fun setBroadcastState(active: Boolean) {
        isBroadcasting = active
        if (active) {
            tvStatus.text = "Broadcasting Live"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_active)
            layoutStatus.setBackgroundResource(R.drawable.bg_tracking_active)
            cardLiveStats.visibility = View.VISIBLE
            btnNewCode.isEnabled = false
            btnNewCode.alpha = 0.4f
        } else {
            tvStatus.text = "Not Broadcasting"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_inactive)
            layoutStatus.setBackgroundResource(R.drawable.bg_tracking_inactive)
            cardLiveStats.visibility = View.GONE
            btnNewCode.isEnabled = true
            btnNewCode.alpha = 1f
        }
    }

    // ── Share / copy ──────────────────────────────────────────────────────────

    /**
     * Shares the code plus a deep link.
     * If the "Locate Me" app is installed on the recipient's phone,
     * tapping the link opens the app directly on the View Live screen.
     * If the app is NOT installed, the Play Store link lets them install it.
     */
    private fun shareCodeAndLink() {
        val deepLink   = "locateme://view?code=$sessionId"
        val storeLink  = "https://play.google.com/store/apps/details?id=${packageName}"
        val text = buildString {
            appendLine("📍 I'm sharing my live location with you!")
            appendLine()
            appendLine("🔑 Code: $sessionId")
            appendLine()
            appendLine("👆 Tap to open directly in the Locate Me app:")
            appendLine(deepLink)
            appendLine()
            appendLine("📲 Don't have the app? Install it free:")
            append(storeLink)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share live location via"))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", text))
    }

    // ── Back press — stop sharing and leave ───────────────────────────────────

    override fun onBackPressed() {
        if (isBroadcasting) stopSharingAndExit() else super.onBackPressed()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            locationReceiver, IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
    }
}
