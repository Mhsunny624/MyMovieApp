package com.mymovieapp.app.data

import android.content.Context

/** Extra buffering for a live channel that keeps stalling. Seconds; 0 = the normal short buffer. */
object ChannelBuffer {
    val CHOICES = listOf(0, 8, 15, 30)
    private fun sp(c: Context) = c.getSharedPreferences("channel_buffer", Context.MODE_PRIVATE)
    fun get(c: Context, channelId: Long): Int = sp(c).getInt(channelId.toString(), 0)
    fun set(c: Context, channelId: Long, sec: Int) {
        sp(c).edit().apply { if (sec == 0) remove(channelId.toString()) else putInt(channelId.toString(), sec) }.apply()
    }
}
