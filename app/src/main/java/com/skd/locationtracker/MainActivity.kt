package com.skd.locationtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnShare: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (granted) {
                if (::googleMap.isInitialized) {
                    enableMyLocation()
                }
            } else {
                Toast.makeText(this, getString(R.string.location_permission), Toast.LENGTH_LONG).show()
            }
    }


    private fun enableMyLocation() {
        if (!PermissionUtils.hasLocationPermission(this)) return
        if (!::googleMap.isInitialized) return

        googleMap.isMyLocationEnabled = true
        moveCameraToCurrentLocation()
    }


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnShare = findViewById(R.id.btnShare)
        requestLocationPermission()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
        requestNotificationPermission()

        btnStart.setOnClickListener {
            if (!PermissionUtils.hasLocationPermission(this)) {
                requestLocationPermission()
                return@setOnClickListener
            }

            checkAndEnableLocation {
                enableMyLocation()
                safeStartTracking()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
        }

        btnShare.setOnClickListener {
            shareCurrentLocation()
        }
    }

    private fun requestLocationPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true

        if (PermissionUtils.hasLocationPermission(this)) {
            enableMyLocation()
        } else {
            requestLocationPermission()
        }
    }

    private fun moveCameraToCurrentLocation() {
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        if (!PermissionUtils.hasLocationPermission(this)) return
        if (!::googleMap.isInitialized) return

        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            1000
        )
            .setMaxUpdates(1)
            .build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val location = result.lastLocation ?: return

                val latLng = LatLng(location.latitude, location.longitude)
                googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                )

                fusedClient.removeLocationUpdates(this)
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedClient.requestLocationUpdates(
            request,
            callback,
            mainLooper
        )
    }

    private fun shareCurrentLocation() {
        if (!PermissionUtils.hasLocationPermission(this)) {
            Toast.makeText(this, getString(R.string.location_permission), Toast.LENGTH_SHORT).show()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                Toast.makeText(this, getString(R.string.unable_to_locate), Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val lat = location.latitude
            val lng = location.longitude

            val mapsUrl = "https://www.google.com/maps?q=$lat,$lng"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "My current location:\n$mapsUrl")
            }

            startActivity(
                Intent.createChooser(shareIntent, getString(R.string.share_location_via))
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    200
                )
            }
        }
    }

    private fun checkAndEnableLocation(onEnabled: () -> Unit) {
        val locationRequest = com.google.android.gms.location.LocationRequest
            .Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                5000
            )
            .build()

        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client = com.google.android.gms.location.LocationServices
            .getSettingsClient(this)

        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            onEnabled()
        }

        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this, 300)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.enable_location),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun canStartForegroundService(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun safeStartTracking() {
        if (!canStartForegroundService()) {
            Toast.makeText(
                this,
                getString(R.string.allow_notification),
                Toast.LENGTH_LONG
            ).show()
            requestNotificationPermission()
            return
        }

        ContextCompat.startForegroundService(this,
            Intent(this, LocationService::class.java)
        )
    }
}
