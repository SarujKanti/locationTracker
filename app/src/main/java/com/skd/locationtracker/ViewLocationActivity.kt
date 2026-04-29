package com.skd.locationtracker

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Viewer screen:
 *  🔵 Blue pulsing dot        = viewer's own position     (PulsingMarkerManager)
 *  🟠 Orange marker           = sharer's current position  (Firebase)
 *  🔵 Blue polyline           = shortest route to sharer   (Directions API / straight-line)
 *
 * No orange history trail — only the shortest-path route is drawn.
 *
 * When the sender stops sharing:
 *  • Firebase listener is removed immediately
 *  • Orange marker stays at last known position
 *  • "STOPPED" badge + warning banner appear
 *  • Disconnect button becomes "Close"
 *  • Viewer can still see the last known location on the map
 */
class ViewLocationActivity : AppCompatActivity(), OnMapReadyCallback {

    // ── Map ──────────────────────────────────────────────────────────────────
    private lateinit var googleMap: GoogleMap

    // Viewer's own pulsing blue dot
    private var myMarker: Marker? = null
    private var myPulsingMgr: PulsingMarkerManager? = null
    private lateinit var fusedClient: FusedLocationProviderClient
    private var myLocationCallback: LocationCallback? = null
    private var myLatLng: LatLng? = null

    // Sharer marker
    private var sharerMarker: Marker? = null
    private var sharerLatLng: LatLng? = null
    private var previousSharerLatLng: LatLng? = null
    private var directionPolyline: Polyline? = null     // shortest route viewer → sharer
    private var markerAnimator: ValueAnimator? = null
    private var firstLocationReceived = false
    private var sharingStopped = false

    // Route fetch rate-limiting (once per 30 s)
    private var lastRouteFetchMs = 0L

    private enum class CameraMode { FOLLOW_SHARER, FREE }
    private var cameraMode = CameraMode.FOLLOW_SHARER

    // Firebase
    private var firebaseListener: ValueEventListener? = null
    private var currentSessionId: String? = null
    private var pendingCode: String? = null

    /** Stable per-device ID used for viewer presence in Firebase. */
    private val viewerId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val mapsApiKey = "AIzaSyCfqOfFyErPEmt5_Xj4yY5x-phF_AwWagE"

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
    private lateinit var bannerSharingStopped: View
    private lateinit var badgeLive: View
    private lateinit var badgeStopped: View
    private lateinit var tvViewerCode: TextView
    private lateinit var tvViewerLat: TextView
    private lateinit var tvViewerLng: TextView
    private lateinit var tvViewerSpeed: TextView
    private lateinit var tvViewerAccuracy: TextView
    private lateinit var tvViewerUpdated: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvRouteLabel: TextView
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

        etCode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        btnDisconnect.setOnClickListener            { disconnect() }
        fabMyLocation.setOnClickListener             { centreOnMe() }
        fabFitBoth.setOnClickListener                { fitBoth() }
        findViewById<FloatingActionButton>(R.id.fabBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun bindViews() {
        cardLegend           = findViewById(R.id.cardLegend)
        fabColumn            = findViewById(R.id.fabColumn)
        fabMyLocation        = findViewById(R.id.fabMyLocation)
        fabFitBoth           = findViewById(R.id.fabFitBoth)
        etCode               = findViewById(R.id.etCode)
        btnConnect           = findViewById(R.id.btnConnect)
        btnDisconnect        = findViewById(R.id.btnDisconnect)
        panelCodeEntry       = findViewById(R.id.panelCodeEntry)
        panelLiveInfo        = findViewById(R.id.panelLiveInfo)
        bannerSharingStopped = findViewById(R.id.bannerSharingStopped)
        badgeLive            = findViewById(R.id.badgeLive)
        badgeStopped         = findViewById(R.id.badgeStopped)
        tvViewerCode         = findViewById(R.id.tvViewerCode)
        tvViewerLat          = findViewById(R.id.tvViewerLat)
        tvViewerLng          = findViewById(R.id.tvViewerLng)
        tvViewerSpeed        = findViewById(R.id.tvViewerSpeed)
        tvViewerAccuracy     = findViewById(R.id.tvViewerAccuracy)
        tvViewerUpdated      = findViewById(R.id.tvViewerUpdated)
        tvDistance           = findViewById(R.id.tvDistance)
        tvRouteLabel         = findViewById(R.id.tvRouteLabel)
        navBarSpacer         = findViewById(R.id.viewerNavBarSpacer)

        val sheet = findViewById<View>(R.id.viewerBottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(sheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.viewerBottomSheet)) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime    = insets.getInsets(WindowInsetsCompat.Type.ime())
            navBarSpacer.layoutParams = navBarSpacer.layoutParams.also {
                it.height = maxOf(navBar.bottom, ime.bottom)
            }
            insets
        }
    }

    // ── Map ready ────────────────────────────────────────────────────────────

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.apply {
            isZoomControlsEnabled     = false
            isCompassEnabled          = true
            isMyLocationButtonEnabled = false
        }
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 78.0), 4f))

        startViewerLocationUpdates()

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
                cameraMode = CameraMode.FREE
        }

        pendingCode?.let { code ->
            pendingCode = null
            connectToSession(code)
        }
    }

    // ── Viewer's own pulsing blue dot ─────────────────────────────────────────

    private fun startViewerLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5_000)
            .setMinUpdateIntervalMillis(3_000).build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val latLng = LatLng(loc.latitude, loc.longitude)
                myLatLng = latLng
                updateMyDot(latLng)
            }
        }
        myLocationCallback = cb

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(request, cb, mainLooper)
        }
    }

    private fun updateMyDot(latLng: LatLng) {
        if (!::googleMap.isInitialized) return
        val existing = myMarker
        if (existing == null) {
            val marker = googleMap.addMarker(
                MarkerOptions().position(latLng).anchor(0.5f, 0.5f).flat(true).zIndex(1f)
            ) ?: return
            myMarker = marker
            myPulsingMgr = PulsingMarkerManager(
                marker    = marker,
                resources = resources,
                dotColor  = Color.parseColor("#1565C0"),
                ringColor = Color.parseColor("#1565C0")
            ).also { it.start() }
        } else {
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
        currentSessionId   = code
        sharingStopped     = false
        tvViewerCode.text  = "Code: $code"
        tvViewerUpdated.text = "Connecting…"

        panelCodeEntry.visibility = View.GONE
        panelLiveInfo.visibility  = View.VISIBLE
        bottomSheetBehavior.peekHeight =
            (190 * resources.displayMetrics.density).toInt()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        cardLegend.visibility = View.VISIBLE
        fabColumn.visibility  = View.VISIBLE

        // Register presence so the sender knows we're watching
        FirebaseLocationRepo.addViewerPresence(code, viewerId)

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
        // Remove viewer presence from Firebase
        currentSessionId?.let { id ->
            FirebaseLocationRepo.removeViewerPresence(id, viewerId)
            firebaseListener?.let { FirebaseLocationRepo.removeListener(id, it) }
        }
        firebaseListener   = null
        currentSessionId   = null
        sharingStopped     = false
        firstLocationReceived = false
        previousSharerLatLng  = null
        sharerLatLng          = null

        directionPolyline?.remove(); directionPolyline = null
        sharerMarker?.remove();      sharerMarker      = null
        markerAnimator?.cancel()
        cameraMode = CameraMode.FOLLOW_SHARER

        // Reset panels
        bannerSharingStopped.visibility = View.GONE
        badgeLive.visibility            = View.VISIBLE
        badgeStopped.visibility         = View.GONE
        panelLiveInfo.visibility        = View.GONE
        panelCodeEntry.visibility       = View.VISIBLE
        cardLegend.visibility           = View.GONE
        fabColumn.visibility            = View.GONE
        etCode.text?.clear()
        tvDistance.text         = "—"
        tvRouteLabel.visibility = View.GONE

        // Reset Disconnect button style
        btnDisconnect.text = "Disconnect"
        btnDisconnect.setTextColor(ContextCompat.getColor(this, R.color.red_stop))
        btnDisconnect.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.red_stop_bg))
        btnDisconnect.strokeColor =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.red_stop))

        bottomSheetBehavior.peekHeight =
            (280 * resources.displayMetrics.density).toInt()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    // ── Sharer location updates ───────────────────────────────────────────────

    private fun onSharerLocationReceived(location: FirebaseLocationRepo.LiveLocation) {
        // ── Sender stopped ─────────────────────────────────────────────────
        if (!location.active) {
            if (sharingStopped) return        // already handled
            sharingStopped = true

            // Stop the Firebase listener — no more updates needed
            currentSessionId?.let { id ->
                FirebaseLocationRepo.removeViewerPresence(id, viewerId)
                firebaseListener?.let { FirebaseLocationRepo.removeListener(id, it) }
            }
            firebaseListener = null

            // Remove route polyline — last known position is frozen
            directionPolyline?.remove(); directionPolyline = null
            tvRouteLabel.visibility = View.GONE

            // Show stopped UI
            bannerSharingStopped.visibility = View.VISIBLE
            badgeLive.visibility            = View.GONE
            badgeStopped.visibility         = View.VISIBLE
            tvViewerUpdated.text            = "Sharing stopped"

            // Change Disconnect → Close (grey style)
            btnDisconnect.text = "Close"
            btnDisconnect.setTextColor(
                ContextCompat.getColor(this, R.color.on_surface_secondary))
            btnDisconnect.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.surface_variant))
            btnDisconnect.strokeColor =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.divider))

            Toast.makeText(
                this,
                "Sender stopped sharing — last known location shown",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // ── Normal update ──────────────────────────────────────────────────
        val newLatLng = LatLng(location.lat, location.lng)
        sharerLatLng = newLatLng

        tvViewerLat.text      = "%.5f°".format(location.lat)
        tvViewerLng.text      = "%.5f°".format(location.lng)
        tvViewerSpeed.text    = "%.1f km/h".format(location.speed)
        tvViewerAccuracy.text =
            if (location.accuracy > 0) "±%.0fm".format(location.accuracy) else "—"
        tvViewerUpdated.text  =
            "Updated ${timeFormatter.format(Date(location.timestamp))}"

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

        // Refresh distance label and shortest-path route
        updateDistanceAndRoute()
    }

    // ── Distance + shortest-path route ───────────────────────────────────────

    private fun updateDistanceAndRoute() {
        val myPos     = myLatLng ?: return
        val sharerPos = sharerLatLng ?: return

        // Update distance label immediately (no network needed)
        val distKm = haversineKm(myPos, sharerPos)
        tvDistance.text = if (distKm < 1.0) "%.0f m".format(distKm * 1000)
                          else "%.2f km".format(distKm)

        // Rate-limit the Directions API to once per 30 s
        val now = System.currentTimeMillis()
        if (now - lastRouteFetchMs < 30_000) return
        lastRouteFetchMs = now

        fetchDirectionsRoute(myPos, sharerPos)
    }

    private fun haversineKm(a: LatLng, b: LatLng): Double {
        val R    = 6371.0
        val dLat = Math.toRadians(b.latitude  - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h    = sin(dLat / 2).pow(2) +
                   cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                   sin(dLng / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }

    /**
     * Fetches the road route from the Directions API in a background thread.
     * Falls back to a dashed straight-line geodesic if the API is unavailable.
     */
    private fun fetchDirectionsRoute(from: LatLng, to: LatLng) {
        Thread {
            val points = try {
                val urlStr = "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=${from.latitude},${from.longitude}" +
                    "&destination=${to.latitude},${to.longitude}" +
                    "&key=$mapsApiKey"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                parseDirectionsResponse(json)
            } catch (_: Exception) { emptyList() }

            runOnUiThread {
                if (!::googleMap.isInitialized || sharingStopped) return@runOnUiThread
                directionPolyline?.remove()
                if (points.isNotEmpty()) {
                    directionPolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .addAll(points)
                            .color(Color.parseColor("#1565C0"))
                            .width(6f)
                            .startCap(RoundCap()).endCap(RoundCap())
                            .jointType(JointType.ROUND)
                            .zIndex(0.5f)
                    )
                    tvRouteLabel.text       = "Road route shown"
                    tvRouteLabel.visibility = View.VISIBLE
                } else {
                    // Dashed straight line as fallback
                    directionPolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .add(from, to)
                            .color(Color.parseColor("#1565C0"))
                            .width(4f)
                            .pattern(listOf(Dash(20f), Gap(12f)))
                            .geodesic(true)
                            .zIndex(0.5f)
                    )
                    tvRouteLabel.text       = "Straight-line route"
                    tvRouteLabel.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun parseDirectionsResponse(json: String): List<LatLng> {
        return try {
            val obj = JSONObject(json)
            if (obj.getString("status") != "OK") return emptyList()
            val encoded = obj.getJSONArray("routes")
                .getJSONObject(0)
                .getJSONObject("overview_polyline")
                .getString("points")
            decodePolyline(encoded)
        } catch (_: Exception) { emptyList() }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val result = mutableListOf<LatLng>()
        var index = 0; val len = encoded.length
        var lat = 0; var lng = 0
        while (index < len) {
            var b: Int; var shift = 0; var res = 0
            do { b = encoded[index++].code - 63; res = res or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lat += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            shift = 0; res = 0
            do { b = encoded[index++].code - 63; res = res or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lng += if (res and 1 != 0) (res shr 1).inv() else res shr 1
            result.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return result
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

    private fun createSharerBitmap(): android.graphics.Bitmap {
        val dp   = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val bmp  = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val cvs  = android.graphics.Canvas(bmp)
        val cx = size / 2f; val cy = size / 2f; val r = size / 2f
        fun p(c: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = c }
        cvs.drawCircle(cx, cy, r * 0.95f, p(Color.parseColor("#33E65100")))
        cvs.drawCircle(cx, cy, r * 0.70f, p(Color.WHITE))
        cvs.drawCircle(cx, cy, r * 0.55f, p(Color.parseColor("#E65100")))
        cvs.drawCircle(cx, cy - r * 0.18f, r * 0.16f, p(Color.WHITE))
        cvs.drawArc(
            android.graphics.RectF(cx - r * 0.28f, cy - r * 0.05f, cx + r * 0.28f, cy + r * 0.3f),
            0f, 180f, true, p(Color.WHITE)
        )
        return bmp
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
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(me ?: sharer!!, 15f))
            return
        }
        val bounds = LatLngBounds.Builder().include(me).include(sharer).build()
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 200))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    override fun onResume()  { super.onResume();  myPulsingMgr?.start() }
    override fun onPause()   { super.onPause();   myPulsingMgr?.stop()  }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        markerAnimator?.cancel()
        myPulsingMgr?.stop()
        stopViewerLocationUpdates()
        currentSessionId?.let { id ->
            if (!sharingStopped) FirebaseLocationRepo.removeViewerPresence(id, viewerId)
            firebaseListener?.let { FirebaseLocationRepo.removeListener(id, it) }
        }
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
