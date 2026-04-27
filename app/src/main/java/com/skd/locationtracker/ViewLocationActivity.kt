package com.skd.locationtracker

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

/**
 * Viewer screen — shows:
 *  🔵 Blue pulsing dot   = viewer's own GPS position  (PulsingMarkerManager)
 *  🟠 Orange animated    = sharer's live position      (from Firebase)
 *
 * Bottom sheet:
 *   Before connect → code-entry panel (peek = full form)
 *   After connect  → live-info panel  (peek = stats row)
 */
class ViewLocationActivity : AppCompatActivity(), OnMapReadyCallback {

    // ── Map ──────────────────────────────────────────────────────────────────
    private lateinit var googleMap: GoogleMap

    // Viewer's own location — pulsing blue dot
    private var myMarker: Marker? = null
    private var myPulsingMgr: PulsingMarkerManager? = null
    private lateinit var fusedClient: FusedLocationProviderClient
    private var myLocationCallback: LocationCallback? = null
    private var myLatLng: LatLng? = null

    // Sharer marker (orange, animated)
    private var sharerMarker: Marker? = null
    private var sharerLatLng: LatLng? = null
    private var previousSharerLatLng: LatLng? = null
    private var routePolyline: Polyline? = null
    private val routePoints = mutableListOf<LatLng>()
    private var markerAnimator: ValueAnimator? = null
    private var firstLocationReceived = false

    // Camera
    private enum class CameraMode { FOLLOW_SHARER, FREE }
    private var cameraMode = CameraMode.FOLLOW_SHARER

    // Firebase
    private var firebaseListener: ValueEventListener? = null
    private var currentSessionId: String? = null
    private var pendingCode: String? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var cardLegend: CardView
    private lateinit var fabColumn: View
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var fabFitBoth: FloatingActionButton
    private lateinit var etCode: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var panelCodeEntry: View
    private lateinit var panelLiveInfo: View
    private lateinit var tvViewerCode: TextView
    private lateinit var tvViewerLat: TextView
    private lateinit var tvViewerLng: TextView
    private lateinit var tvViewerSpeed: TextView
    private lateinit var tvViewerAccuracy: TextView
    private lateinit var tvViewerUpdated: TextView
    private lateinit var navBarSpacer: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_location)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        bindViews()
        applyWindowInsets()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.viewerMapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Deep-link / extras
        val deepLinkCode = (intent.data?.getQueryParameter("code")
            ?: intent.getStringExtra("code"))?.uppercase()
        if (!deepLinkCode.isNullOrBlank()) {
            etCode.setText(deepLinkCode)
            pendingCode = deepLinkCode
        }

        btnConnect.setOnClickListener {
            val code = etCode.text?.toString()?.trim()?.uppercase() ?: ""
            if (code.length != 8) {
                Toast.makeText(this, "Please enter the full 8-character code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            connectToSession(code)
        }

        btnDisconnect.setOnClickListener         { disconnect() }
        fabMyLocation.setOnClickListener         { centreOnMe() }
        fabFitBoth.setOnClickListener            { fitBoth() }
        findViewById<FloatingActionButton>(R.id.fabBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun bindViews() {
        cardLegend       = findViewById(R.id.cardLegend)
        fabColumn        = findViewById(R.id.fabColumn)
        fabMyLocation    = findViewById(R.id.fabMyLocation)
        fabFitBoth       = findViewById(R.id.fabFitBoth)
        etCode           = findViewById(R.id.etCode)
        btnConnect       = findViewById(R.id.btnConnect)
        btnDisconnect    = findViewById(R.id.btnDisconnect)
        panelCodeEntry   = findViewById(R.id.panelCodeEntry)
        panelLiveInfo    = findViewById(R.id.panelLiveInfo)
        tvViewerCode     = findViewById(R.id.tvViewerCode)
        tvViewerLat      = findViewById(R.id.tvViewerLat)
        tvViewerLng      = findViewById(R.id.tvViewerLng)
        tvViewerSpeed    = findViewById(R.id.tvViewerSpeed)
        tvViewerAccuracy = findViewById(R.id.tvViewerAccuracy)
        tvViewerUpdated  = findViewById(R.id.tvViewerUpdated)
        navBarSpacer     = findViewById(R.id.viewerNavBarSpacer)

        val sheet = findViewById<View>(R.id.viewerBottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(sheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.viewerBottomSheet)) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            navBarSpacer.layoutParams = navBarSpacer.layoutParams.also { it.height = navBar.bottom }
            insets
        }
    }

    // ── Map ready ────────────────────────────────────────────────────────────

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.apply {
            isZoomControlsEnabled     = false
            isCompassEnabled          = true
            isMyLocationButtonEnabled = false   // own FAB used instead
        }
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 78.0), 4f))

        // Start tracking viewer's own location for Fit-Both + pulsing blue dot
        startViewerLocationUpdates()

        // Manual pan → free camera
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
                cameraMode = CameraMode.FREE
        }

        // Auto-connect if launched from deep-link
        pendingCode?.let { code ->
            pendingCode = null
            connectToSession(code)
        }
    }

    // ── Viewer's own location (blue pulsing dot) ──────────────────────────────

    private fun startViewerLocationUpdates() {
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000)
            .setMinUpdateIntervalMillis(3_000)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val newLatLng = LatLng(loc.latitude, loc.longitude)
                myLatLng = newLatLng
                updateMyDot(newLatLng)
            }
        }
        myLocationCallback = cb

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(request, cb, mainLooper)
        }
    }

    /**
     * Places / moves the pulsing blue dot representing the viewer's own position.
     * First call: creates the marker and starts PulsingMarkerManager.
     * Subsequent calls: just updates position (pulse keeps running).
     */
    private fun updateMyDot(latLng: LatLng) {
        if (!::googleMap.isInitialized) return
        val existing = myMarker
        if (existing == null) {
            // First fix — create marker + start pulsing
            val marker = googleMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(1f)
            ) ?: return
            myMarker = marker
            myPulsingMgr = PulsingMarkerManager(
                marker    = marker,
                resources = resources,
                dotColor  = Color.parseColor("#1565C0"),
                ringColor = Color.parseColor("#1565C0")
            ).also { it.start() }
        } else {
            // Subsequent fixes — move the marker; pulse icon keeps updating
            existing.position = latLng
        }
    }

    private fun stopViewerLocationUpdates() {
        myLocationCallback?.let { fusedClient.removeLocationUpdates(it) }
        myLocationCallback = null
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // ── Session connect / disconnect ──────────────────────────────────────────

    private fun connectToSession(code: String) {
        currentSessionId = code
        tvViewerCode.text = "Code: $code"
        tvViewerUpdated.text = "Connecting…"

        // Switch panel inside the bottom sheet
        panelCodeEntry.visibility = View.GONE
        panelLiveInfo.visibility  = View.VISIBLE

        // Adjust sheet peek to the stats-row height (~190dp)
        bottomSheetBehavior.peekHeight =
            (190 * resources.displayMetrics.density).toInt()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        cardLegend.visibility = View.VISIBLE
        fabColumn.visibility  = View.VISIBLE

        firebaseListener = FirebaseLocationRepo.listenToLocation(
            sessionId = code,
            onUpdate  = { loc -> runOnUiThread { onSharerLocationReceived(loc) } },
            onError   = { msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (!firstLocationReceived) disconnect()
                }
            }
        )
    }

    private fun disconnect() {
        val id = currentSessionId; val li = firebaseListener
        if (id != null && li != null) FirebaseLocationRepo.removeListener(id, li)
        firebaseListener = null; currentSessionId = null

        firstLocationReceived = false
        previousSharerLatLng  = null
        sharerLatLng          = null
        routePoints.clear()
        routePolyline?.remove(); routePolyline = null
        sharerMarker?.remove();  sharerMarker  = null
        markerAnimator?.cancel()
        cameraMode = CameraMode.FOLLOW_SHARER

        // Restore code-entry panel
        panelLiveInfo.visibility  = View.GONE
        panelCodeEntry.visibility = View.VISIBLE
        cardLegend.visibility     = View.GONE
        fabColumn.visibility      = View.GONE
        etCode.text?.clear()

        // Reset sheet peek to code-entry height
        bottomSheetBehavior.peekHeight =
            (280 * resources.displayMetrics.density).toInt()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    // ── Sharer location updates ───────────────────────────────────────────────

    private fun onSharerLocationReceived(location: FirebaseLocationRepo.LiveLocation) {
        val newLatLng = LatLng(location.lat, location.lng)
        sharerLatLng = newLatLng

        tvViewerLat.text      = "%.5f°".format(location.lat)
        tvViewerLng.text      = "%.5f°".format(location.lng)
        tvViewerSpeed.text    = "%.1f km/h".format(location.speed)
        tvViewerAccuracy.text = if (location.accuracy > 0) "±%.0fm".format(location.accuracy) else "—"
        tvViewerUpdated.text  = if (!location.active) "Sharer stopped broadcasting"
                                else "Updated ${timeFormatter.format(Date(location.timestamp))}"

        routePoints.add(newLatLng)
        updateRoutePolyline()

        val from = previousSharerLatLng
        if (!firstLocationReceived || from == null) {
            firstLocationReceived = true
            placeSharerMarker(newLatLng)
            if (cameraMode == CameraMode.FOLLOW_SHARER)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng, 17f))
        } else {
            animateSharerMarkerTo(from, newLatLng)
            if (cameraMode == CameraMode.FOLLOW_SHARER)
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(newLatLng), 800, null)
        }
        previousSharerLatLng = newLatLng
    }

    // ── Sharer marker (orange) ────────────────────────────────────────────────

    private fun placeSharerMarker(position: LatLng) {
        sharerMarker?.remove()
        sharerMarker = googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .icon(BitmapDescriptorFactory.fromBitmap(createSharerBitmap()))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(2f)
                .title("Sharer's Location")
        )
        previousSharerLatLng = position
    }

    private fun animateSharerMarkerTo(from: LatLng, to: LatLng) {
        markerAnimator?.cancel()
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 1_000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f   = anim.animatedFraction
                val pos = LatLng(
                    from.latitude  + (to.latitude  - from.latitude)  * f,
                    from.longitude + (to.longitude - from.longitude) * f
                )
                val m = sharerMarker
                if (m == null) placeSharerMarker(pos)
                else { m.position = pos; m.rotation = bearingBetween(from, to) }
            }
        }
        markerAnimator?.start()
    }

    /** Orange person icon — same shape as blue one but with orange palette. */
    private fun createSharerBitmap(): android.graphics.Bitmap {
        val density = resources.displayMetrics.density
        val size    = (56 * density).toInt()
        val bmp     = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas  = android.graphics.Canvas(bmp)
        val cx = size / 2f; val cy = size / 2f; val r = size / 2f

        canvas.drawCircle(cx, cy, r * 0.95f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33E65100") })
        canvas.drawCircle(cx, cy, r * 0.70f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE })
        canvas.drawCircle(cx, cy, r * 0.55f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E65100") })
        // head
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.16f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE })
        // body arc
        canvas.drawArc(
            android.graphics.RectF(cx - r * 0.28f, cy - r * 0.05f, cx + r * 0.28f, cy + r * 0.3f),
            0f, 180f, true,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE })
        return bmp
    }

    private fun updateRoutePolyline() {
        routePolyline?.remove()
        if (routePoints.size < 2) return
        routePolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(routePoints)
                .color(Color.parseColor("#E65100"))
                .width(7f)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .jointType(JointType.ROUND)
                .geodesic(true)
        )
    }

    // ── FAB actions ───────────────────────────────────────────────────────────

    private fun centreOnMe() {
        val me = myLatLng
        if (me != null) {
            cameraMode = CameraMode.FREE
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(me, 17f))
        } else {
            Toast.makeText(this, "Your location is not available yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fitBoth() {
        val me     = myLatLng
        val sharer = sharerLatLng
        if (me == null && sharer == null) return

        cameraMode = CameraMode.FREE

        if (me == null || sharer == null) {
            val target = me ?: sharer!!
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
            return
        }

        val bounds = LatLngBounds.Builder().include(me).include(sharer).build()
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200))
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun bearingBetween(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val x    = Math.sin(dLng) * Math.cos(lat2)
        val y    = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(x, y)) + 360) % 360).toFloat()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        myPulsingMgr?.start()
    }

    override fun onPause() {
        super.onPause()
        myPulsingMgr?.stop()
    }

    override fun onDestroy() {
        markerAnimator?.cancel()
        myPulsingMgr?.stop()
        stopViewerLocationUpdates()
        val id = currentSessionId; val li = firebaseListener
        if (id != null && li != null) FirebaseLocationRepo.removeListener(id, li)
        super.onDestroy()
    }

    companion object {
        fun start(context: android.content.Context, code: String? = null) {
            val intent = Intent(context, ViewLocationActivity::class.java)
            if (!code.isNullOrBlank()) intent.putExtra("code", code)
            context.startActivity(intent)
        }
    }
}
