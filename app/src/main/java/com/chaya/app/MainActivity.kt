package com.chaya.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.chaya.app.ui.navigation.ChayaNavHost
import com.chaya.app.ui.theme.ChayaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the same share would open its sheet a second time.
        if (savedInstanceState == null) takeSharedLink(intent)
        setContent {
            ChayaTheme {
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

    /** "Share > Chaya" from another app hands over text, usually with a link in it. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(SharedLinks::offer)
        }
    }
}
