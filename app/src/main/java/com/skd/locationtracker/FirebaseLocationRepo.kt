package com.skd.locationtracker

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Handles all Firebase Realtime Database reads and writes for live location sharing.
 *
 * DB structure:
 *   sessions/
 *     {sessionId}/
 *       lat       : Double
 *       lng       : Double
 *       speed     : Double   (km/h)
 *       accuracy  : Double   (metres)
 *       bearing   : Double   (degrees)
 *       timestamp : Long
 *       active    : Boolean
 *       viewers/             ← presence map; key = viewerId, value = true
 *         {viewerId} : Boolean
 */
object FirebaseLocationRepo {

    private val db by lazy {
        FirebaseDatabase.getInstance(
            "https://locationtracker-92791-default-rtdb.firebaseio.com"
        ).reference.child("sessions")
    }

    data class LiveLocation(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val speed: Double = 0.0,
        val accuracy: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Long = 0L,
        val active: Boolean = true
    )

    // ── Sharer API ────────────────────────────────────────────────────────────

    /**
     * Updates only the location fields of the session node, leaving the
     * [viewers] child untouched (using updateChildren instead of setValue).
     */
    fun pushLocation(sessionId: String, location: LiveLocation) {
        val updates: Map<String, Any> = mapOf(
            "lat"       to location.lat,
            "lng"       to location.lng,
            "speed"     to location.speed,
            "accuracy"  to location.accuracy,
            "bearing"   to location.bearing,
            "timestamp" to location.timestamp,
            "active"    to location.active
        )
        db.child(sessionId).updateChildren(updates)
    }

    /** Mark the session as inactive so all viewers auto-disconnect. */
    fun deactivateSession(sessionId: String) {
        db.child(sessionId).child("active").setValue(false)
    }

    /**
     * Completely removes the session node from Firebase.
     * Used when the sender generates a new code while broadcasting — the old
     * node is deleted so viewers with the previous code see "Session not found"
     * instead of a frozen last-known location.
     */
    fun deleteSession(sessionId: String) {
        db.child(sessionId).removeValue()
    }

    // ── Location listener (viewer) ────────────────────────────────────────────

    /**
     * Subscribe to live location updates for [sessionId].
     * Returns the [ValueEventListener] so the caller can unsubscribe later.
     */
    fun listenToLocation(
        sessionId: String,
        onUpdate: (LiveLocation) -> Unit,
        onError: (String) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onError("Session not found. Check the code and try again.")
                    return
                }
                val location = snapshot.getValue(LiveLocation::class.java)
                if (location != null) onUpdate(location)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        db.child(sessionId).addValueEventListener(listener)
        return listener
    }

    /** Remove a previously registered location listener. */
    fun removeListener(sessionId: String, listener: ValueEventListener) {
        db.child(sessionId).removeEventListener(listener)
    }

    // ── Viewer presence API ───────────────────────────────────────────────────

    /**
     * Registers the viewer's presence under the session.
     * An [onDisconnect] handler is attached so Firebase automatically removes
     * the entry if the viewer's connection drops unexpectedly.
     */
    fun addViewerPresence(sessionId: String, viewerId: String) {
        val ref = db.child(sessionId).child("viewers").child(viewerId)
        ref.setValue(true)
        ref.onDisconnect().removeValue()          // auto-clean on network drop
    }

    /**
     * Explicitly removes the viewer's presence (called on deliberate disconnect).
     * Also cancels the onDisconnect handler so it doesn't fire again.
     */
    fun removeViewerPresence(sessionId: String, viewerId: String) {
        val ref = db.child(sessionId).child("viewers").child(viewerId)
        ref.removeValue()
        ref.onDisconnect().cancel()
    }

    // ── Viewer-count listener (sender) ────────────────────────────────────────

    /**
     * Listens to the number of viewers currently connected to [sessionId].
     * [onCount] is called with the updated integer every time someone connects
     * or disconnects.
     */
    fun listenToViewerCount(
        sessionId: String,
        onCount: (Int) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) =
                onCount(snapshot.childrenCount.toInt())
            override fun onCancelled(error: DatabaseError) = onCount(0)
        }
        db.child(sessionId).child("viewers").addValueEventListener(listener)
        return listener
    }

    /** Remove a viewer-count listener. */
    fun removeViewerCountListener(sessionId: String, listener: ValueEventListener) {
        db.child(sessionId).child("viewers").removeEventListener(listener)
    }
}
