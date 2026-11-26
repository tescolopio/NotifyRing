package com.notifyring.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.notifyring.app.ui.navigation.NotifyRingNavHost
import com.notifyring.app.ui.theme.NotifyRingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as NotifyRingApplication).container
        setContent {
            NotifyRingTheme {
                NotifyRingNavHost(container = container)
            }
        }
    }
}
