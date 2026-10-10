package com.chaya.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.chaya.app.ui.navigation.ChayaNavHost
import com.chaya.app.ui.theme.ChayaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the same share would open its sheet a second time.
        if (savedInstanceState == null) takeSharedLink(intent)
        (application as ChayaApplication).checkEngineSoon()
        setContent {
            val mode by (application as ChayaApplication).themeSettings.mode.collectAsState()
            val dark = mode.isDark(isSystemInDarkTheme())
            // The bars' icons follow the app's choice, not the phone's, or they vanish on a light screen.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            ChayaTheme(darkTheme = dark) {
                val navController = rememberNavController()
                ChayaNavHost(navController = navController)
            }
        }
    }

    /** A share arriving while Chaya is already open (the activity is single-task). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeSharedLink(intent)
    }

    private companion object {
        // The same scrims enableEdgeToEdge() uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    /** "Share > Chaya" from another app hands over text, usually with a link in it. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(SharedLinks::offer)
        }
    }
}
