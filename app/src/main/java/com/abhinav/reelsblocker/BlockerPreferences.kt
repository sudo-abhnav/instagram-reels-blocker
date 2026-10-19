package com.abhinav.reelsblocker

import android.content.Context

class BlockerPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isBlockerEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "reels_blocker"
        private const val KEY_ENABLED = "blocker_enabled"
    }
}
