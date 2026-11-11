package com.arcade.bubbledefense

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.arcade.bubbledefense.ui.GameScreen
import com.arcade.bubbledefense.ui.splash.TachyonVerticalSplash

/**
 * Main activity for Bubble Defense.
 *
 * Configures edge-to-edge immersive display, hides system navigation bars with transient swipe,
 * keeps the display awake during gameplay, and hosts the Compose [GameScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Immersive sticky arcade full-screen mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep screen awake while player is actively drawing shields
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            var isSplashFinished by remember { mutableStateOf(false) }

            Box(modifier = Modifier.fillMaxSize()) {
                // GameScreen is mounted underneath so engine and textures initialize seamlessly
                GameScreen()

                // Tachyon transparent vertical splash overlays and dissolves into the game
                if (!isSplashFinished) {
                    TachyonVerticalSplash(
                        onSplashComplete = {
                            isSplashFinished = true
                        }
                    )
                }
            }
        }
    }
}
