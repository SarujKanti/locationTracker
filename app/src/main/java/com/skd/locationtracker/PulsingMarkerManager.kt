package com.skd.locationtracker

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Marker

/**
 * Animates a marker with an expanding pulsing ring — like Google Maps' blue dot.
 *
 * Usage:
 *   val mgr = PulsingMarkerManager(marker, resources, Color.parseColor("#1565C0"))
 *   mgr.start()      // begin pulsing
 *   mgr.updatePosition(newLatLng)
 *   mgr.stop()       // clean up in onDestroy / onPause
 */
class PulsingMarkerManager(
    private var marker: Marker?,
    private val resources: Resources,
    private val dotColor: Int = Color.parseColor("#1565C0"),
    private val ringColor: Int = Color.parseColor("#1565C0")
) {
    private val handler = Handler(Looper.getMainLooper())
    private var phase = 0f
    private var running = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            phase = (phase + 0.055f) % 1f
            marker?.setIcon(
                BitmapDescriptorFactory.fromBitmap(buildBitmap(phase))
            )
            handler.postDelayed(this, 80L)   // ~12 fps — smooth but battery-friendly
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(runnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }

    fun setMarker(m: Marker?) {
        marker = m
    }

    // -------------------------------------------------------------------------

    private fun buildBitmap(phase: Float): Bitmap {
        val dp     = resources.displayMetrics.density
        val size   = (64 * dp).toInt()          // bitmap canvas size
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f

        // — Outer pulse ring (expands from 40 % to 95 % radius, fades out)
        val minR  = size * 0.20f
        val maxR  = size * 0.47f
        val ringR = minR + (maxR - minR) * phase
        val alpha = (255 * (1f - phase)).toInt().coerceIn(0, 255)

        val ringFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            this.alpha = (alpha * 0.25).toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, ringR, ringFillPaint)

        val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ringColor
            this.alpha = alpha
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * dp
        }
        canvas.drawCircle(cx, cy, ringR, ringStrokePaint)

        // — White border circle
        canvas.drawCircle(cx, cy, size * 0.185f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // — Inner solid dot
        canvas.drawCircle(cx, cy, size * 0.145f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor })

        return bitmap
    }
}
