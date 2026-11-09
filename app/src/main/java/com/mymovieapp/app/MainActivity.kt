package com.mymovieapp.app

import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.mymovieapp.app.ui.LibraryApp
import com.mymovieapp.app.ui.LibraryTheme
import com.mymovieapp.app.ui.LocalIsTv

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mymovieapp.app.data.AppLog.installCrashSaver(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        setContent {
            LibraryTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) { LibraryApp(vm) }
            }
        }
        if (savedInstanceState == null) handle(intent)
        if (isTv) com.mymovieapp.app.data.CastReceiver.start(this)
    }

    override fun onDestroy() {
        if (isFinishing) com.mymovieapp.app.data.CastReceiver.stop()
        super.onDestroy()
    }

    /** Leaving the app starts the auto-lock timer (a screen rotation doesn't count as leaving). */
    override fun onStart() { super.onStart(); vm.vault.backInApp() }
    override fun onStop() { if (!isChangingConfigurations) vm.vault.leftApp(); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.mymovieapp.app.ui.PlayerKeys.handler?.invoke(event) == true || super.dispatchKeyEvent(event)

    /** A video link or file sent from another app. */
    private fun handle(i: Intent?) {
        val raw: String? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data?.toString()
            Intent.ACTION_SEND -> {
                val link = Regex("""https?://\S+""").find(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value
                if (link == null) Toast.makeText(this, "No link found in what was shared", Toast.LENGTH_SHORT).show()
                link
            }
            else -> null
        }
        if (raw != null) vm.incoming.value = raw
    }
}