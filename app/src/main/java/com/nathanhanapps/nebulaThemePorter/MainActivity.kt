package com.nathanhanapps.nebulaThemePorter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.nathanhanapps.nebulaThemePorter.ui.PorterApp
import com.nathanhanapps.nebulaThemePorter.ui.PorterTheme
import com.nathanhanapps.nebulaThemePorter.ui.PorterViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PorterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PorterTheme { PorterApp(viewModel) }
        }
    }
}
