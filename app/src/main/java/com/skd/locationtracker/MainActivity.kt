package com.skd.locationtracker

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // UI
    private lateinit var googleMap: GoogleMap
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var tvLat: TextView
    private lateinit var tvLng: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var tvStatusLabel: TextView
    private lateinit var statusDot: View
    private lateinit var cardStatus: CardView

    // Map state
    private var personMarker: Marker? = null
    private var previousLatLng: LatLng? = null
    private var routePolyline: Polyline? = null
    private val routePoints = mutableListOf<LatLng>()
    private var markerAnimator: ValueAnimator? = null
    private var isTracking = false
    private var lastShareLat = 0.0
    private var lastShareLng = 0.0

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Broadcast receiver for location updates from LocationService
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LocationService.ACTION_LOCATION_UPDATE) return
            val lat = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
            val lng = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
            val speed = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
            val accuracy = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, -1f)

            onNewLocation(lat, lng, speed, accuracy)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                if (::googleMap.isInitialized) setupMap()
            } else {
                Toast.makeText(this, getString(R.string.location_permission), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        requestNotificationPermission()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnStart.setOnClickListener { handleStartTracking() }
        btnStop.setOnClickListener { handleStopTracking() }
        btnShare.setOnClickListener { shareCurrentLocation() }
    }

    private fun bindViews() {
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnShare = findViewById(R.id.btnShare)
        tvLat = findViewById(R.id.tvLat)
        tvLng = findViewById(R.id.tvLng)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        statusDot = findViewById(R.id.statusDot)
        cardStatus = findViewById(R.id.cardStatus)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }

        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL

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

        try {
            googleMap.isMyLocationEnabled = false // we use custom marker instead
        } catch (_: SecurityException) { }

        // Fetch initial location to center map
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                placePersonMarker(latLng)
                lastShareLat = location.latitude
                lastShareLng = location.longitude
            }
        }
    }

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

            // Also do a one-shot high accuracy update right away
            fetchImmediateLocation()
        }
    }

    private fun fetchImmediateLocation() {
        if (!PermissionUtils.hasLocationPermission(this)) return
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMaxUpdates(1)
            .build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                onNewLocation(loc.latitude, loc.longitude,
                    if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
                    if (loc.hasAccuracy()) loc.accuracy else -1f)
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

    private fun onNewLocation(lat: Double, lng: Double, speed: Float, accuracy: Float) {
        val newLatLng = LatLng(lat, lng)
        lastShareLat = lat
        lastShareLng = lng

        // Update stats UI
        tvLat.text = "%.5f°".format(lat)
        tvLng.text = "%.5f°".format(lng)
        tvSpeed.text = if (speed > 0) "%.1f km/h".format(speed) else "0.0 km/h"
        tvAccuracy.text = if (accuracy > 0) "±%.0fm".format(accuracy) else "—"
        tvLastUpdated.text = "Updated at ${timeFormatter.format(Date())}"

        // Add to route
        routePoints.add(newLatLng)
        updateRoutePolyline()

        val from = previousLatLng
        if (from != null) {
            animateMarkerTo(from, newLatLng)
            // Smoothly follow with camera
            googleMap.animateCamera(
                CameraUpdateFactory.newLatLng(newLatLng),
                800, null
            )
        } else {
            placePersonMarker(newLatLng)
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng, 17f))
        }

        previousLatLng = newLatLng
    }

    private fun placePersonMarker(position: LatLng) {
        personMarker?.remove()
        personMarker = googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .icon(BitmapDescriptorFactory.fromBitmap(createPersonBitmap()))
                .anchor(0.5f, 0.5f)
                .flat(true)
                .zIndex(1f)
        )
        previousLatLng = position
    }

    private fun animateMarkerTo(from: LatLng, to: LatLng) {
        markerAnimator?.cancel()

        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val lat = from.latitude + (to.latitude - from.latitude) * fraction
                val lng = from.longitude + (to.longitude - from.longitude) * fraction
                val interpolated = LatLng(lat, lng)

                val marker = personMarker
                if (marker == null) {
                    placePersonMarker(interpolated)
                } else {
                    marker.position = interpolated
                    // Rotate marker to face direction of movement
                    val bearing = bearingBetween(from, to)
                    marker.rotation = bearing
                }
            }
        }
        markerAnimator?.start()
    }

    private fun updateRoutePolyline() {
        routePolyline?.remove()
        if (routePoints.size < 2) return

        routePolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(routePoints)
                .color(Color.parseColor("#1565C0"))
                .width(8f)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .jointType(JointType.ROUND)
                .geodesic(true)
        )
    }

    private fun createPersonBitmap(): Bitmap {
        val size = (56 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f

        val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#331565C0")
        }
        val paintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val paintBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1565C0")
        }

        canvas.drawCircle(cx, cy, r * 0.95f, paintOuter)
        canvas.drawCircle(cx, cy, r * 0.7f, paintWhite)
        canvas.drawCircle(cx, cy, r * 0.55f, paintBlue)

        // Person head
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.16f, paintWhite)

        // Person body arc
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val bodyRect = android.graphics.RectF(
            cx - r * 0.28f, cy - r * 0.05f,
            cx + r * 0.28f, cy + r * 0.3f
        )
        canvas.drawArc(bodyRect, 0f, 180f, true, bodyPaint)

        return bitmap
    }

    private fun bearingBetween(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val x = Math.sin(dLng) * Math.cos(lat2)
        val y = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(x, y)) + 360) % 360).toFloat()
    }

    private fun updateTrackingUI(active: Boolean) {
        if (active) {
            tvStatusLabel.text = getString(R.string.status_tracking)
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_active)
            btnStart.isEnabled = false
            btnStart.alpha = 0.5f
            btnStop.isEnabled = true
        } else {
            tvStatusLabel.text = getString(R.string.status_idle)
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.on_surface_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_tracking_inactive)
            btnStart.isEnabled = true
            btnStart.alpha = 1f
            btnStop.isEnabled = true
        }
    }

    private fun shareCurrentLocation() {
        if (!PermissionUtils.hasLocationPermission(this)) {
            Toast.makeText(this, getString(R.string.location_permission), Toast.LENGTH_SHORT).show()
            return
        }

        if (lastShareLat == 0.0 && lastShareLng == 0.0) {
            Toast.makeText(this, getString(R.string.unable_to_locate), Toast.LENGTH_SHORT).show()
            return
        }

        val mapsUrl = "https://www.google.com/maps?q=${lastShareLat},${lastShareLng}"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "My current location:\n$mapsUrl")
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_location_via)))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
    }

    private fun canStartForegroundService(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkLocationSettingsAndRun(onReady: () -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build()
        val settingsRequest = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { onReady() }
            .addOnFailureListener { exception ->
                if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                    try { exception.startResolutionForResult(this, 300) }
                    catch (_: Exception) { }
                } else {
                    Toast.makeText(this, getString(R.string.enable_location), Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            locationReceiver,
            IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
    }

    override fun onDestroy() {
        markerAnimator?.cancel()
        super.onDestroy()
    }
}
