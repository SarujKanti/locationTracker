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
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

/**
 * Share Live Location screen.
 *
 * Broadcasting starts automatically when the screen opens.
 * The sender can see in real time how many viewers are connected.
 * Pressing "Stop Sharing" (or back) marks the session inactive so all
 * viewers are automatically notified and shown the last known location.
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
    private lateinit var tvViewerCount: TextView
    private lateinit var btnStopSharing: MaterialButton
    private lateinit var btnShareVia: MaterialButton
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var btnNewCode: MaterialButton

    private var sessionId: String = ""
    private var isBroadcasting = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Firebase listener watching the viewers/ node for this session. */
    private var viewerCountListener: ValueEventListener? = null

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
            val lat      = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
            val lng      = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
            val speed    = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
            val accuracy = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, -1f)
            val bearing  = intent.getFloatExtra(LocationService.EXTRA_BEARING, -1f)

            tvBcastLat.text    = "%.5f°".format(lat)
            tvBcastLng.text    = "%.5f°".format(lng)
            tvBcastSpeed.text  = "%.1f km/h".format(speed)
            tvBcastUpdated.text = "Sent at ${timeFormatter.format(Date())}"

            if (isBroadcasting) {
                FirebaseLocationRepo.pushLocation(
                    sessionId,
                    FirebaseLocationRepo.LiveLocation(
                        lat       = lat,
                        lng       = lng,
                        speed     = speed.toDouble(),
                        accuracy  = accuracy.toDouble(),
                        bearing   = bearing.toDouble(),
                        timestamp = System.currentTimeMillis(),
                        active    = true
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
            val oldSessionId = sessionId

            // While broadcasting: nuke the old Firebase node completely so any
            // receiver holding the previous code sees "Session not found" instead
            // of a frozen last-known location.
            if (isBroadcasting) {
                FirebaseLocationRepo.deleteSession(oldSessionId)
                stopWatchingViewerCount()
            }

            // Generate and persist the new code; LocationService picks it up
            // automatically on the very next GPS tick (reads SharedPreferences).
            sessionId = LocationSharingManager.resetSessionId(this)
            tvSessionCode.text = sessionId

            // Restart viewer-count listener on the new session node.
            if (isBroadcasting) {
                startWatchingViewerCount()
            }

            Toast.makeText(this, "New code generated", Toast.LENGTH_SHORT).show()
        }

        // Auto-start broadcasting when the screen opens
        if (LocationSharingManager.isSharing(this)) {
            setBroadcastState(true)            // restore in-progress session
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
        tvViewerCount   = findViewById(R.id.tvViewerCount)
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
        startWatchingViewerCount()
    }

    /** Stops broadcasting, tells Firebase the session is inactive. */
    private fun stopSharingAndExit() {
        stopService(Intent(this, LocationService::class.java))
        FirebaseLocationRepo.deactivateSession(sessionId)
        LocationSharingManager.setSharing(this, false)
        stopWatchingViewerCount()
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
        } else {
            tvStatus.text = "Not Broadcasting"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_inactive)
            layoutStatus.setBackgroundResource(R.drawable.bg_tracking_inactive)
            cardLiveStats.visibility = View.GONE
        }
        // "Generate New Code" is always enabled — even during a live broadcast.
        btnNewCode.isEnabled = true
        btnNewCode.alpha = 1f
    }

    // ── Viewer count ──────────────────────────────────────────────────────────

    /** Listens to the viewers/ node and updates the on-screen count. */
    private fun startWatchingViewerCount() {
        stopWatchingViewerCount()   // clear any existing listener first
        viewerCountListener = FirebaseLocationRepo.listenToViewerCount(sessionId) { count ->
            runOnUiThread {
                tvViewerCount.text = count.toString()
                tvViewerCount.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (count > 0) R.color.green_active else R.color.on_surface_secondary
                    )
                )
            }
        }
    }

    private fun stopWatchingViewerCount() {
        viewerCountListener?.let {
            FirebaseLocationRepo.removeViewerCountListener(sessionId, it)
        }
        viewerCountListener = null
    }

    // ── Share / copy ──────────────────────────────────────────────────────────

    private fun shareCodeAndLink() {
        val deepLink  = "locateme://view?code=$sessionId"
        val storeLink = "https://play.google.com/store/apps/details?id=${packageName}"
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

    // ── Back press ────────────────────────────────────────────────────────────

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Back simply navigates away — sharing continues in the background.
        // Only the explicit "Stop Sharing" button stops the broadcast.
        super.onBackPressed()
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

    override fun onDestroy() {
        stopWatchingViewerCount()
        super.onDestroy()
    }
}
