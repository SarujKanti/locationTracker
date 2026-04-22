package com.skd.locationtracker

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*

class LocationService : Service() {

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.skd.locationtracker.LOCATION_UPDATE"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_BEARING = "bearing"

        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 10_000L
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var broadcastManager: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        broadcastManager = LocalBroadcastManager.getInstance(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val lat = location.latitude
                val lng = location.longitude
                val speed = if (location.hasSpeed()) location.speed * 3.6f else 0f   // m/s → km/h
                val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
                val bearing = if (location.hasBearing()) location.bearing else -1f

                // 1. Notify the UI (MainActivity / ShareSessionActivity)
                broadcastLocation(lat, lng, speed, accuracy, bearing)

                // 2. If a sharing session is active, push to Firebase so viewers see it
                if (LocationSharingManager.isSharing(this@LocationService)) {
                    val sessionId = LocationSharingManager.getOrCreateSessionId(this@LocationService)
                    FirebaseLocationRepo.pushLocation(
                        sessionId,
                        FirebaseLocationRepo.LiveLocation(
                            lat = lat,
                            lng = lng,
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
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun broadcastLocation(
        lat: Double, lng: Double, speed: Float, accuracy: Float, bearing: Float
    ) {
        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            putExtra(EXTRA_LATITUDE, lat)
            putExtra(EXTRA_LONGITUDE, lng)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_ACCURACY, accuracy)
            putExtra(EXTRA_BEARING, bearing)
        }
        broadcastManager.sendBroadcast(intent)
        // Update notification text
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification("%.5f, %.5f".format(lat, lng)))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current location while tracking is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(locationText: String = "Acquiring location…"): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_location))
            .setContentText(locationText)
            .setSmallIcon(R.drawable.ic_navigation_marker)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
