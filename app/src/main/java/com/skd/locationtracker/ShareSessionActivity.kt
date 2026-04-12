package com.skd.locationtracker

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for the person who wants to SHARE their live location.
 * Shows a session code, lets them start/stop broadcasting,
 * and receives location updates from LocationService via LocalBroadcast.
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
    private lateinit var btnToggleBroadcast: MaterialButton
    private lateinit var btnShareVia: MaterialButton
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var btnNewCode: MaterialButton

    private var sessionId: String = ""
    private var isBroadcasting = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationService.ACTION_LOCATION_UPDATE) return
            val lat = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
            val lng = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
            val speed = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
            val accuracy = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, -1f)
            val bearing = intent.getFloatExtra(LocationService.EXTRA_BEARING, -1f)

            // Update local stats UI
            tvBcastLat.text = "%.5f°".format(lat)
            tvBcastLng.text = "%.5f°".format(lng)
            tvBcastSpeed.text = "%.1f km/h".format(speed)
            tvBcastUpdated.text = "Sent at ${timeFormatter.format(Date())}"

            // Push to Firebase so viewers see live location
            if (isBroadcasting) {
                FirebaseLocationRepo.pushLocation(
                    sessionId,
                    FirebaseLocationRepo.LiveLocation(
                        lat = lat, lng = lng,
                        speed = speed.toDouble(),
                        accuracy = accuracy.toDouble(),
                        bearing = bearing.toDouble(),
                        timestamp = System.currentTimeMillis(),
                        active = true
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_session)

        bindViews()
        setupToolbar()

        sessionId = LocationSharingManager.getOrCreateSessionId(this)
        tvSessionCode.text = sessionId

        // Restore state if was broadcasting
        if (LocationSharingManager.isSharing(this)) {
            setBroadcastState(true)
        }

        btnCopyCode.setOnClickListener {
            copyToClipboard(sessionId)
            Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show()
        }

        btnShareVia.setOnClickListener { shareCodeViaIntent() }

        btnToggleBroadcast.setOnClickListener {
            if (isBroadcasting) stopBroadcasting() else startBroadcasting()
        }

        btnNewCode.setOnClickListener {
            if (isBroadcasting) {
                Toast.makeText(this, "Stop broadcasting before generating a new code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sessionId = LocationSharingManager.resetSessionId(this)
            tvSessionCode.text = sessionId
            Toast.makeText(this, "New code generated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindViews() {
        tvSessionCode = findViewById(R.id.tvSessionCode)
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        layoutStatus = findViewById(R.id.layoutStatus)
        cardLiveStats = findViewById(R.id.cardLiveStats)
        tvBcastLat = findViewById(R.id.tvBcastLat)
        tvBcastLng = findViewById(R.id.tvBcastLng)
        tvBcastSpeed = findViewById(R.id.tvBcastSpeed)
        tvBcastUpdated = findViewById(R.id.tvBcastUpdated)
        btnToggleBroadcast = findViewById(R.id.btnToggleBroadcast)
        btnShareVia = findViewById(R.id.btnShareVia)
        btnCopyCode = findViewById(R.id.btnCopyCode)
        btnNewCode = findViewById(R.id.btnNewCode)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun startBroadcasting() {
        if (!PermissionUtils.hasLocationPermission(this)) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        LocationSharingManager.setSharing(this, true)
        setBroadcastState(true)
    }

    private fun stopBroadcasting() {
        stopService(Intent(this, LocationService::class.java))
        FirebaseLocationRepo.deactivateSession(sessionId)
        LocationSharingManager.setSharing(this, false)
        setBroadcastState(false)
    }

    private fun setBroadcastState(active: Boolean) {
        isBroadcasting = active
        if (active) {
            tvStatus.text = "Broadcasting Live"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_active)
            layoutStatus.setBackgroundResource(R.drawable.bg_tracking_active)
            cardLiveStats.visibility = View.VISIBLE
            btnToggleBroadcast.text = "Stop Broadcasting"
            btnToggleBroadcast.setTextColor(ContextCompat.getColor(this, R.color.red_stop))
            btnToggleBroadcast.setBackgroundColor(0)
            btnToggleBroadcast.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.red_stop_bg)
                )
            btnToggleBroadcast.strokeColor =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.red_stop)
                )
        } else {
            tvStatus.text = "Not Broadcasting"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_inactive)
            layoutStatus.setBackgroundResource(R.drawable.bg_tracking_inactive)
            cardLiveStats.visibility = View.GONE
            btnToggleBroadcast.text = "Start Broadcasting"
            btnToggleBroadcast.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            btnToggleBroadcast.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.green_active_bg)
                )
            btnToggleBroadcast.strokeColor =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.green_active)
                )
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Session Code", text))
    }

    private fun shareCodeViaIntent() {
        val text = "Track my live location!\n\n" +
                "Open Locate Me app → View Live Location → Enter code:\n\n" +
                "🔑 $sessionId\n\n" +
                "Or tap: locateme://view?code=$sessionId"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share session code via"))
    }

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
