package com.arcade.bubbledefense

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.arcade.bubbledefense.ui.GameScreen
import com.arcade.bubbledefense.ui.splash.CoreLoreIntroAnimation
import com.arcade.bubbledefense.ui.splash.TachyonVerticalSplash

/**
 * Main activity for Bubble Defense.
 *
 * Configures edge-to-edge immersive display, hides system navigation bars with transient swipe,
 * keeps the display awake during gameplay, and hosts the Compose [GameScreen].
 *
 * Sequential Startup Pipeline:
 * - Stage 0: Tachyon Studio Hero Splash Screen (0.0s - 4.15s, tap-to-skip)
 * - Stage 1: 2-Second Cinematic Core Lore Intro Sequence (0.0s - 2.0s, tap-to-skip)
 * - Stage 2: Direct 120Hz Live Gameplay (all intro overlays unmounted with 0ms hitch)
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
            // Sequential Startup: 0 = Studio Splash, 1 = Core Lore, 2 = Live Game
            var startupStage by remember { mutableIntStateOf(0) }

            Box(modifier = Modifier.fillMaxSize()) {
                // GameScreen is mounted underneath so engine, pools, and shaders initialize concurrently
                GameScreen()

                // Stage 0: Tachyon Studio Hero Splash
                if (startupStage == 0) {
                    TachyonVerticalSplash(
                        onSplashComplete = {
                            startupStage = 1
                        }
                    )
                }

                // Stage 1: 2-Second Cinematic Core Lore Intro Sequence
                if (startupStage == 1) {
                    CoreLoreIntroAnimation(
                        onLoreComplete = {
                            startupStage = 2
                        }
                    )
                }
            }
        }
    }
}
