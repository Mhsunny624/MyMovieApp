package com.mymovieapp.app.data

import android.content.Context

/** Remembers the audio / subtitle choice per series or folder, so it is not picked again for every episode. */
object TrackPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("track_prefs", Context.MODE_PRIVATE)
    fun get(c: Context, group: String, kind: String): String? = sp(c).getString("$group|$kind", null)
    fun set(c: Context, group: String, kind: String, value: String) {
        sp(c).edit().putString("$group|$kind", value).apply()
    }
}
