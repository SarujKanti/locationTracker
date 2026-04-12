package com.skd.locationtracker

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the live-sharing session ID.
 * A session ID is a short alphanumeric code the sharer shares with viewers.
 */
object LocationSharingManager {

    private const val PREFS_NAME = "sharing_prefs"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_IS_SHARING = "is_sharing"
    private const val CODE_LENGTH = 8
    private val CHARS = ('A'..'Z') + ('0'..'9')

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns existing session ID, or generates and saves a new one. */
    fun getOrCreateSessionId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_SESSION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = (1..CODE_LENGTH).map { CHARS.random() }.joinToString("")
        prefs.edit().putString(KEY_SESSION_ID, newId).apply()
        return newId
    }

    fun setSharing(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_SHARING, active).apply()
    }

    fun isSharing(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_SHARING, false)

    /** Call this to generate a fresh code for a new sharing session. */
    fun resetSessionId(context: Context): String {
        val newId = (1..CODE_LENGTH).map { CHARS.random() }.joinToString("")
        prefs(context).edit()
            .putString(KEY_SESSION_ID, newId)
            .putBoolean(KEY_IS_SHARING, false)
            .apply()
        return newId
    }
}
