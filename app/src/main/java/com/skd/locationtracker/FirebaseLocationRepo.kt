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
 *       speed     : Double  (km/h)
 *       accuracy  : Double  (meters)
 *       bearing   : Double  (degrees)
 *       timestamp : Long
 *       active    : Boolean
 */
object FirebaseLocationRepo {

    private val db by lazy { FirebaseDatabase.getInstance().reference.child("sessions") }

    data class LiveLocation(
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        val speed: Double = 0.0,
        val accuracy: Double = 0.0,
        val bearing: Double = 0.0,
        val timestamp: Long = 0L,
        val active: Boolean = true
    )

    /** Push the sharer's current position to Firebase. */
    fun pushLocation(sessionId: String, location: LiveLocation) {
        db.child(sessionId).setValue(location)
    }

    /** Mark the session as inactive (sharer stopped tracking). */
    fun deactivateSession(sessionId: String) {
        db.child(sessionId).child("active").setValue(false)
    }

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

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }
        db.child(sessionId).addValueEventListener(listener)
        return listener
    }

    /** Remove a previously registered listener. */
    fun removeListener(sessionId: String, listener: ValueEventListener) {
        db.child(sessionId).removeEventListener(listener)
    }
}
