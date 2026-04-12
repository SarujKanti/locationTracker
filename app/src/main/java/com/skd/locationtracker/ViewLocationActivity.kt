package com.skd.locationtracker

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for the person who RECEIVES a shared live location.
 * They enter the sharer's session code, then see an animated
 * person marker moving on the map in real-time.
 */
class ViewLocationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var cardCodeEntry: CardView
    private lateinit var cardLiveInfo: CardView
    private lateinit var etCode: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var tvViewerCode: TextView
    private lateinit var tvViewerLat: TextView
    private lateinit var tvViewerLng: TextView
    private lateinit var tvViewerSpeed: TextView
    private lateinit var tvViewerAccuracy: TextView
    private lateinit var tvViewerUpdated: TextView

    // Map state
    private var personMarker: Marker? = null
    private var previousLatLng: LatLng? = null
    private var routePolyline: Polyline? = null
    private val routePoints = mutableListOf<LatLng>()
    private var markerAnimator: ValueAnimator? = null
    private var cameraFollowsMarker = true
    private var firstLocationReceived = false

    // Firebase
    private var firebaseListener: ValueEventListener? = null
    private var currentSessionId: String? = null

    // Code from deep link — held until onMapReady fires
    private var pendingCode: String? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_location)

        bindViews()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.viewerMapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Extract code from either:
        //   locateme://view?code=XXXXXXXX                                  (custom scheme)
        //   https://sarujkanti.github.io/locationTracker/?code=XXXXXXXX   (App Link)
        val deepLinkCode = (intent.data?.getQueryParameter("code")
            ?: intent.getStringExtra("code"))?.uppercase()
        if (!deepLinkCode.isNullOrBlank()) {
            etCode.setText(deepLinkCode)
            pendingCode = deepLinkCode   // connect once map is ready in onMapReady()
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

        btnDisconnect.setOnClickListener { disconnect() }

        findViewById<FloatingActionButton>(R.id.fabBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun bindViews() {
        cardCodeEntry = findViewById(R.id.cardCodeEntry)
        cardLiveInfo = findViewById(R.id.cardLiveInfo)
        etCode = findViewById(R.id.etCode)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvViewerCode = findViewById(R.id.tvViewerCode)
        tvViewerLat = findViewById(R.id.tvViewerLat)
        tvViewerLng = findViewById(R.id.tvViewerLng)
        tvViewerSpeed = findViewById(R.id.tvViewerSpeed)
        tvViewerAccuracy = findViewById(R.id.tvViewerAccuracy)
        tvViewerUpdated = findViewById(R.id.tvViewerUpdated)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
        }

        // Default camera position (world view until we get a location)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.0, 78.0), 4f))

        // Auto-connect if launched from a deep link / App Link
        pendingCode?.let { code ->
            pendingCode = null
            connectToSession(code)
        }

        // Detect manual camera movement → stop auto-follow
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                cameraFollowsMarker = false
            }
        }

        // Tap on map → re-enable auto-follow
        googleMap.setOnMapClickListener { cameraFollowsMarker = true }
    }

    private fun connectToSession(code: String) {
        currentSessionId = code
        tvViewerCode.text = "Code: $code"

        // Show loading state
        tvViewerUpdated.text = "Connecting to session $code…"
        cardCodeEntry.visibility = View.GONE
        cardLiveInfo.visibility = View.VISIBLE

        firebaseListener = FirebaseLocationRepo.listenToLocation(
            sessionId = code,
            onUpdate = { location ->
                runOnUiThread { onLocationReceived(location) }
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                    if (!firstLocationReceived) disconnect()
                }
            }
        )
    }

    private fun onLocationReceived(location: FirebaseLocationRepo.LiveLocation) {
        val newLatLng = LatLng(location.lat, location.lng)

        // Update stats
        tvViewerLat.text = "%.5f°".format(location.lat)
        tvViewerLng.text = "%.5f°".format(location.lng)
        tvViewerSpeed.text = "%.1f km/h".format(location.speed)
        tvViewerAccuracy.text = if (location.accuracy > 0) "±%.0fm".format(location.accuracy) else "—"
        tvViewerUpdated.text = "Updated ${timeFormatter.format(Date(location.timestamp))}"

        // Handle inactive session
        if (!location.active && firstLocationReceived) {
            tvViewerUpdated.text = "Sharer has stopped broadcasting"
        }

        // Route
        routePoints.add(newLatLng)
        updateRoutePolyline()

        val from = previousLatLng
        if (!firstLocationReceived || from == null) {
            firstLocationReceived = true
            placePersonMarker(newLatLng)
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng, 17f))
        } else {
            animateMarkerTo(from, newLatLng)
            if (cameraFollowsMarker) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(newLatLng), 800, null)
            }
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
                .title("Live Location")
        )
        previousLatLng = position
    }

    private fun animateMarkerTo(from: LatLng, to: LatLng) {
        markerAnimator?.cancel()
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val lat = from.latitude + (to.latitude - from.latitude) * f
                val lng = from.longitude + (to.longitude - from.longitude) * f
                val pos = LatLng(lat, lng)
                val marker = personMarker
                if (marker == null) {
                    placePersonMarker(pos)
                } else {
                    marker.position = pos
                    marker.rotation = bearingBetween(from, to)
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
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f

        // Use orange/amber to visually distinguish from self (blue in MainActivity)
        canvas.drawCircle(cx, cy, r * 0.95f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33E65100") })
        canvas.drawCircle(cx, cy, r * 0.7f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(cx, cy, r * 0.55f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E65100") })

        // Person head
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.16f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // Person body arc
        val bodyRect = android.graphics.RectF(
            cx - r * 0.28f, cy - r * 0.05f,
            cx + r * 0.28f, cy + r * 0.3f
        )
        canvas.drawArc(bodyRect, 0f, 180f, true,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

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

    private fun disconnect() {
        val sessionId = currentSessionId
        val listener = firebaseListener
        if (sessionId != null && listener != null) {
            FirebaseLocationRepo.removeListener(sessionId, listener)
        }
        firebaseListener = null
        currentSessionId = null
        firstLocationReceived = false
        previousLatLng = null
        routePoints.clear()
        routePolyline?.remove()
        routePolyline = null
        personMarker?.remove()
        personMarker = null
        markerAnimator?.cancel()

        cardLiveInfo.visibility = View.GONE
        cardCodeEntry.visibility = View.VISIBLE
        etCode.text?.clear()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    override fun onDestroy() {
        markerAnimator?.cancel()
        val sessionId = currentSessionId
        val listener = firebaseListener
        if (sessionId != null && listener != null) {
            FirebaseLocationRepo.removeListener(sessionId, listener)
        }
        super.onDestroy()
    }

    companion object {
        /** Launch ViewLocationActivity with a pre-filled code (e.g. from share button). */
        fun start(context: android.content.Context, code: String? = null) {
            val intent = Intent(context, ViewLocationActivity::class.java)
            if (!code.isNullOrBlank()) intent.putExtra("code", code)
            context.startActivity(intent)
        }
    }
}
