package com.arcade.bubbledefense

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arcade.bubbledefense.ui.GameScreen

/**
 * Main activity for Bubble Defense.
 *
 * Configures edge-to-edge immersive display, keeps the display awake during gameplay,
 * and hosts the Compose [GameScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen awake while player is actively drawing shields
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            GameScreen()
        }
    }
}
