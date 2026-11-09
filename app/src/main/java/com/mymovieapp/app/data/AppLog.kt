package com.mymovieapp.app.data

import android.content.Context
import android.os.Build
import java.io.File

/** A short text report to paste when something goes wrong: app + device info, the last crash, and recent warnings. */
object AppLog {
    private const val CRASH = "last_crash.txt"

    /** Remember the last crash so it can be included in the report after the app restarts. */
    fun installCrashSaver(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, CRASH).writeText(
                    java.util.Date().toString() + "\n" + android.util.Log.getStackTraceString(e).take(8000)
                )
            }
            old?.uncaughtException(t, e)
        }
    }

    /** Blocking: call from a background thread. */
    fun build(ctx: Context): String {
        val sb = StringBuilder()
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        sb.append("MyMovieApp ").append(ver).append('\n')
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append(java.util.Date().toString()).append("\n\n")
        val crash = File(ctx.filesDir, CRASH)
        if (crash.exists()) sb.append("== Last crash ==\n").append(crash.readText().take(8000)).append("\n\n")
        sb.append("== Recent warnings and errors ==\n")
        sb.append(
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "300", "*:W"))
                p.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { "(couldn't read the log: ${it.message})" }.takeLast(60_000)
        )
        return sb.toString()
    }
}
