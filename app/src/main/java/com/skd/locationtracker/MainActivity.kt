package com.skd.locationtracker

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.LocationRequest
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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // ── Map ──────────────────────────────────────────────────────────────────
    private lateinit var googleMap: GoogleMap

    // Own location — pulsing blue dot
    private var myMarker: Marker? = null
    private var pulsingMgr: PulsingMarkerManager? = null
    private var myLatLng: LatLng? = null

    // Route
    private var routePolyline: Polyline? = null
    private val routePoints = mutableListOf<LatLng>()
    private var markerAnimator: ValueAnimator? = null

    private var isTracking = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ── UI ───────────────────────────────────────────────────────────────────
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnShareLive: MaterialButton
    private lateinit var btnViewLive: MaterialButton
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var tvLat: TextView
    private lateinit var tvLng: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var tvStatusLabel: TextView
    private lateinit var statusDot: View
    private lateinit var navBarSpacer: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    // ── Location broadcast receiver ──────────────────────────────────────────
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationService.ACTION_LOCATION_UPDATE) return
            val lat     = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
            val lng     = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
            val speed   = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
            val accuracy = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, -1f)
            onNewLocation(lat, lng, speed, accuracy)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                if (::googleMap.isInitialized) setupMap()
            } else {
                Toast.makeText(this, getString(R.string.location_permission), Toast.LENGTH_LONG).show()
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        requestNotificationPermission()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnStart.setOnClickListener    { handleStartTracking() }
        btnStop.setOnClickListener     { handleStopTracking() }
        btnShareLive.setOnClickListener {
            startActivity(Intent(this, ShareSessionActivity::class.java))
        }
        btnViewLive.setOnClickListener {
            startActivity(Intent(this, ViewLocationActivity::class.java))
        }
        fabMyLocation.setOnClickListener { centreOnMyLocation() }
    }

    private fun bindViews() {
        btnStart        = findViewById(R.id.btnStart)
        btnStop         = findViewById(R.id.btnStop)
        btnShareLive    = findViewById(R.id.btnShareLive)
        btnViewLive     = findViewById(R.id.btnViewLive)
        fabMyLocation   = findViewById(R.id.fabMyLocation)
        tvLat           = findViewById(R.id.tvLat)
        tvLng           = findViewById(R.id.tvLng)
        tvSpeed         = findViewById(R.id.tvSpeed)
        tvAccuracy      = findViewById(R.id.tvAccuracy)
        tvLastUpdated   = findViewById(R.id.tvLastUpdated)
        tvStatusLabel   = findViewById(R.id.tvStatusLabel)
        statusDot       = findViewById(R.id.statusDot)
        navBarSpacer    = findViewById(R.id.navBarSpacer)

        val sheet = findViewById<View>(R.id.mainBottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(sheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /** Pad the bottom sheet so content clears the navigation bar on gesture/button nav. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainBottomSheet)) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            navBarSpacer.layoutParams = navBarSpacer.layoutParams.also {
                it.height = navBar.bottom
            }
            insets
        }
    }

    // ── Map ──────────────────────────────────────────────────────────────────

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.apply {
            isZoomControlsEnabled     = false
            isCompassEnabled          = true
            isMyLocationButtonEnabled = false   // we use our own FAB
            isTiltGesturesEnabled     = true
            isRotateGesturesEnabled   = true
        }

        if (PermissionUtils.hasLocationPermission(this)) {
            setupMap()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun setupMap() {
        if (!PermissionUtils.hasLocationPermission(this)) return
        if (!::googleMap.isInitialized) return

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                placePulsingMarker(latLng)
            }
        }
    }

    // ── Tracking ─────────────────────────────────────────────────────────────

    private fun handleStartTracking() {
        if (!PermissionUtils.hasLocationPermission(this)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        checkLocationSettingsAndRun {
            if (!canStartForegroundService()) {
                Toast.makeText(this, getString(R.string.allow_notification), Toast.LENGTH_LONG).show()
                requestNotificationPermission()
                return@checkLocationSettingsAndRun
            }
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
            isTracking = true
            updateTrackingUI(true)
            fetchImmediateLocation()
        }
    }

    private fun fetchImmediateLocation() {
        if (!PermissionUtils.hasLocationPermission(this)) return
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMaxUpdates(1).build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                onNewLocation(
                    loc.latitude, loc.longitude,
                    if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
                    if (loc.hasAccuracy()) loc.accuracy else -1f
                )
                fusedClient.removeLocationUpdates(this)
            }
        }
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        fusedClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun handleStopTracking() {
        stopService(Intent(this, LocationService::class.java))
        isTracking = false
        updateTrackingUI(false)
    }

    // ── Location update ───────────────────────────────────────────────────────

    private fun onNewLocation(lat: Double, lng: Double, speed: Float, accuracy: Float) {
        val newLatLng = LatLng(lat, lng)

        tvLat.text      = "%.5f°".format(lat)
        tvLng.text      = "%.5f°".format(lng)
        tvSpeed.text    = if (speed > 0) "%.1f km/h".format(speed) else "0.0 km/h"
        tvAccuracy.text = if (accuracy > 0) "±%.0fm".format(accuracy) else "—"
        tvLastUpdated.text = "Updated ${timeFormatter.format(Date())}"

        routePoints.add(newLatLng)
        updateRoutePolyline()

        val from = myLatLng
        if (from != null) {
            animateMarkerTo(from, newLatLng)
            googleMap.animateCamera(CameraUpdateFactory.newLatLng(newLatLng), 800, null)
        } else {
            placePulsingMarker(newLatLng)
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng, 17f))
        }
        myLatLng = newLatLng
    }

    // ── Pulsing blue-dot marker (own location) ────────────────────────────────

    /**
     * Places (or replaces) the pulsing blue marker at [position].
     * PulsingMarkerManager redraws the icon every 80 ms creating the
     * expanding-ring pulse animation (like the Google Maps blue dot).
     */
    private fun placePulsingMarker(position: LatLng) {
        pulsingMgr?.stop()
        myMarker?.remove()

        myMarker = googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(1f)
        )

        pulsingMgr = PulsingMarkerManager(
            marker    = myMarker,
            resources = resources,
            dotColor  = Color.parseColor("#1565C0"),
            ringColor = Color.parseColor("#1565C0")
        ).also { it.start() }

        myLatLng = position
    }

    /**
     * Smoothly slides the marker from [from] to [to] over 1 200 ms.
     * PulsingMarkerManager continues to update the icon independently,
     * so the pulse keeps running during movement.
     */
    private fun animateMarkerTo(from: LatLng, to: LatLng) {
        markerAnimator?.cancel()
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 1_200
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f   = anim.animatedFraction
                val pos = LatLng(
                    from.latitude  + (to.latitude  - from.latitude)  * f,
                    from.longitude + (to.longitude - from.longitude) * f
                )
                val m = myMarker
                if (m == null) placePulsingMarker(pos)
                else {
                    m.position = pos
                    m.rotation = bearingBetween(from, to)
                }
            }
        }
        markerAnimator?.start()
    }

    // ── Route polyline ────────────────────────────────────────────────────────

    private fun updateRoutePolyline() {
        routePolyline?.remove()
        if (routePoints.size < 2) return
        routePolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(routePoints)
                .color(Color.parseColor("#1565C0"))
                .width(7f)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .jointType(JointType.ROUND)
                .geodesic(true)
        )
    }

    // ── FAB: centre on own location ───────────────────────────────────────────

    private fun centreOnMyLocation() {
        val pos = myLatLng
        if (pos != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
        } else {
            Toast.makeText(this, getString(R.string.unable_to_locate), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Bearing helper ────────────────────────────────────────────────────────

    private fun bearingBetween(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val x    = Math.sin(dLng) * Math.cos(lat2)
        val y    = Math.cos(lat1) * Math.sin(lat2) -
                   Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(x, y)) + 360) % 360).toFloat()
    }

    // ── Tracking UI ───────────────────────────────────────────────────────────

    private fun updateTrackingUI(active: Boolean) {
        if (active) {
            tvStatusLabel.text = getString(R.string.status_tracking)
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_active)
            btnStart.isEnabled = false; btnStart.alpha = 0.5f
        } else {
            tvStatusLabel.text = getString(R.string.status_idle)
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_inactive)
            btnStart.isEnabled = true; btnStart.alpha = 1f
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
    }

    private fun canStartForegroundService() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    private fun checkLocationSettingsAndRun(onReady: () -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build()
        val settingsReq = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(request).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsReq)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { ex ->
                if (ex is com.google.android.gms.common.api.ResolvableApiException) {
                    try { ex.startResolutionForResult(this, 300) } catch (_: Exception) { }
                } else {
                    Toast.makeText(this, getString(R.string.enable_location), Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            locationReceiver, IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        )
        pulsingMgr?.start()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
        pulsingMgr?.stop()
    }

    override fun onDestroy() {
        markerAnimator?.cancel()
        pulsingMgr?.stop()
        super.onDestroy()
    }
}
