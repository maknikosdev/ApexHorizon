package com.apexhorizon.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.apexhorizon.app.ui.ApexRoot
import com.apexhorizon.app.ui.AppViewModel
import com.apexhorizon.app.ui.theme.ApexTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark HUD look: light status/navigation bar icons on a transparent bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ApexTheme {
                ApexRoot(viewModel)
            }
        }
    }
}
