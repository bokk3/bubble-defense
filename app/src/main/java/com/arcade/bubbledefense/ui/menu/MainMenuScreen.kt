package com.arcade.bubbledefense.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcade.bubbledefense.domain.economy.BubbleSkin
import com.arcade.bubbledefense.domain.economy.EconomyGateway
import com.arcade.bubbledefense.domain.economy.ShieldSkin
import com.arcade.bubbledefense.domain.games.AuthAndGamesGateway
import com.arcade.bubbledefense.domain.games.BountyMission
import com.arcade.bubbledefense.domain.games.ScoreboardEntry
import com.arcade.bubbledefense.ui.render.ProceduralRenderer
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

enum class MenuTab(val title: String, val icon: String) {
    SORTIE("SORTIE", "🚀"),
    ARSENAL("ARSENAL", "🛰️"),
    BOUNTIES("BOUNTIES", "🎯"),
    DATA_LINK("DATA LINK", "🏆"),
    SYSTEM("SYSTEM", "⚙️")
}

enum class GameMissionMode(val title: String, val subtitle: String, val minLevel: Int) {
    ENDLESS("ENDLESS VOID", "High-Speed Atmospheric Ascent", 1),
    SOLAR_TRIALS("SOLAR CATACLYSM", "Invasive Meteorites & Flare EMPs", 2),
    BOSS_HUNT("SINGULARITY BREACH", "Void Colossus Confrontation", 3)
}

/**
 * Modern Cyberpunk Command Hub / Main Menu.
 *
 * Provides a live 3D space backdrop with the floating bubble, Mission Sortie deployment,
 * Arsenal tech-tree & skin customization, Daily Bounties with Prism rewards, competitive
 * local scoreboards, and system settings.
 */
@Composable
fun MainMenuScreen(
    onLaunchMission: (GameMissionMode) -> Unit,
    onReplayIntro: () -> Unit,
    economyGateway: EconomyGateway,
    gamesGateway: AuthAndGamesGateway,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(MenuTab.SORTIE) }
    var selectedMode by remember { mutableStateOf(GameMissionMode.ENDLESS) }
    var showEditTagDialog by remember { mutableStateOf(false) }

    val profile by gamesGateway.profileState.collectAsState()
    val scoreboards by gamesGateway.scoreboardState.collectAsState()
    val bounties by gamesGateway.bountiesState.collectAsState()

    var backgroundTime by remember { mutableFloatStateOf(0f) }

    // Ambient 120Hz background ticker for float animation
    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - lastTime) * 1e-9f
                lastTime = now
                backgroundTime += dt.coerceAtMost(0.05f)
            }
        }
    }

    // Pre-allocated star background particles
    val stars = remember {
        List(60) {
            Triple(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2.5f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020306))
    ) {
        // 1. Live 3D Deep Space Canvas Backdrop (Stars, Singularity, Floating Bubble)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Starfield
            stars.forEach { (sx, sy, sz) ->
                val pulse = 0.5f + 0.5f * sin(backgroundTime * 2.5f + sx * 10f)
                drawCircle(
                    color = Color.White.copy(alpha = pulse * 0.7f),
                    radius = sz,
                    center = Offset(sx * w, sy * h)
                )
            }

            // Distant Singularity Event Horizon at horizon (W/2, H*0.28)
            val singX = w * 0.5f
            val singY = h * 0.26f
            val pulseR = 36f + 4f * sin(backgroundTime * 1.5f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7000FF).copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(singX, singY),
                    radius = pulseR * 3f
                ),
                radius = pulseR * 3f,
                center = Offset(singX, singY)
            )
            drawCircle(
                color = Color.Black,
                radius = pulseR,
                center = Offset(singX, singY)
            )
            drawCircle(
                color = Color(0xFF00F0FF),
                radius = pulseR,
                center = Offset(singX, singY),
                style = Stroke(width = 2f)
            )

            // Live Floating Bubble Preview in center-lower volume (if in SORTIE or ARSENAL)
            if (activeTab == MenuTab.SORTIE || activeTab == MenuTab.ARSENAL) {
                val floatX = 14f * sin(backgroundTime * 1.2f)
                val floatY = 10f * cos(backgroundTime * 0.9f)
                val bubbleCenterX = w * 0.5f + floatX
                val bubbleCenterY = h * 0.40f + floatY
                val baseRadius = 64f

                // Orbiting satellites (behind)
                val satAngle = backgroundTime * 1.8f
                val satDist = baseRadius * 1.55f
                for (s in 0 until 3) {
                    val angle = satAngle + (s * (2f * PI.toFloat() / 3f))
                    val z = cos(angle) * 0.5f
                    if (z < 0) {
                        val px = bubbleCenterX + cos(angle) * satDist
                        val py = bubbleCenterY + sin(angle) * (satDist * 0.45f)
                        drawCircle(Color(0xFF00FFB2).copy(alpha = 0.6f), radius = 5f, center = Offset(px, py))
                    }
                }

                // 64-vertex soft-body harmonic wobble
                val bubblePath = Path()
                for (v in 0 until 64) {
                    val theta = v * (2f * PI.toFloat() / 64f)
                    val w1 = 0.035f * sin(3f * theta + backgroundTime * 6f)
                    val w2 = 0.020f * sin(5f * theta - backgroundTime * 8f)
                    val r = baseRadius * (1f + w1 + w2)
                    val px = bubbleCenterX + r * cos(theta)
                    val py = bubbleCenterY + r * sin(theta)
                    if (v == 0) bubblePath.moveTo(px, py) else bubblePath.lineTo(px, py)
                }
                bubblePath.close()

                // Translucent membrane & rim
                drawCircle(
                    color = Color(0xFF00F0FF).copy(alpha = 0.15f),
                    radius = baseRadius * 1.4f,
                    center = Offset(bubbleCenterX, bubbleCenterY)
                )
                drawPath(bubblePath, color = Color(0xFF00F0FF).copy(alpha = 0.22f))
                drawPath(bubblePath, color = Color(0xFF00F0FF).copy(alpha = 0.85f), style = Stroke(width = 3.5f))

                // Specular gloss highlight
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = baseRadius * 0.22f,
                    center = Offset(bubbleCenterX - baseRadius * 0.28f, bubbleCenterY - baseRadius * 0.30f)
                )

                // Rotating Aegis shield arcs
                val arcRot = backgroundTime * 120f
                val shieldR = baseRadius + 18f
                drawArc(
                    color = Color(0xFF00F0FF),
                    startAngle = arcRot,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(bubbleCenterX - shieldR, bubbleCenterY - shieldR),
                    size = androidx.compose.ui.geometry.Size(shieldR * 2f, shieldR * 2f),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color(0xFF00FF88),
                    startAngle = arcRot + 180f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(bubbleCenterX - shieldR, bubbleCenterY - shieldR),
                    size = androidx.compose.ui.geometry.Size(shieldR * 2f, shieldR * 2f),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )

                // Orbiting satellites (front)
                for (s in 0 until 3) {
                    val angle = satAngle + (s * (2f * PI.toFloat() / 3f))
                    val z = cos(angle) * 0.5f
                    if (z >= 0) {
                        val px = bubbleCenterX + cos(angle) * satDist
                        val py = bubbleCenterY + sin(angle) * (satDist * 0.45f)
                        drawCircle(Color(0xFF00F0FF), radius = 6f, center = Offset(px, py))
                        drawCircle(Color.White, radius = 2.5f, center = Offset(px, py))
                    }
                }
            }
        }

        // 2. Command Deck UI Layout (Header, Content, Bottom Navigation)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar: Callsign, Rank Badge, Level Progress, Prisms
            TopCommandHeader(
                profile = profile,
                onEditTag = { showEditTagDialog = true }
            )

            // Main Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                when (activeTab) {
                    MenuTab.SORTIE -> SortieTabContent(
                        profile = profile,
                        selectedMode = selectedMode,
                        onSelectMode = { selectedMode = it },
                        onDeploy = { onLaunchMission(selectedMode) }
                    )
                    MenuTab.ARSENAL -> ArsenalTabContent(
                        economyGateway = economyGateway,
                        profile = profile
                    )
                    MenuTab.BOUNTIES -> BountiesTabContent(
                        bounties = bounties,
                        onClaim = { id -> gamesGateway.claimBountyReward(id) }
                    )
                    MenuTab.DATA_LINK -> DataLinkTabContent(
                        scoreboards = scoreboards,
                        profile = profile
                    )
                    MenuTab.SYSTEM -> SystemSettingsTabContent(
                        onReplayIntro = onReplayIntro
                    )
                }
            }

            // Bottom Navigation Tabs
            BottomNavigationDock(
                activeTab = activeTab,
                onSelectTab = { activeTab = it }
            )
        }

        // Edit GamerTag Dialog
        if (showEditTagDialog) {
            EditTagDialog(
                currentTag = profile.gamerTag,
                onConfirm = { newTag ->
                    gamesGateway.updateGamerTag(newTag)
                    showEditTagDialog = false
                },
                onDismiss = { showEditTagDialog = false }
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Header Component
// -------------------------------------------------------------------------------------------------

@Composable
private fun TopCommandHeader(
    profile: com.arcade.bubbledefense.domain.games.PlayerProfile,
    onEditTag: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xD9060B14), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Pilot Profile & Level
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.gamerTag,
                    color = Color(0xFF00F0FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "[EDIT]",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onEditTag() }
                )
            }
            Text(
                text = "${profile.pilotRank} • LVL ${profile.pilotLevel}",
                color = Color(0xFFA5B4FC),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            // XP Bar
            LinearProgressIndicator(
                progress = { profile.progressToNextLevel },
                modifier = Modifier
                    .width(130.dp)
                    .height(4.dp)
                    .padding(top = 2.dp)
                    .clip(CircleShape),
                color = Color(0xFF00F0FF),
                trackColor = Color(0xFF1E293B)
            )
        }

        // Prisms Currency Display
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color(0x3300F0FF), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x6600F0FF), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(text = "💎", fontSize = 13.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${profile.careerPrismsCollected}",
                color = Color(0xFF00F0FF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Tab 1: Sortie (Deployment & Mission Modes)
// -------------------------------------------------------------------------------------------------

@Composable
private fun SortieTabContent(
    profile: com.arcade.bubbledefense.domain.games.PlayerProfile,
    selectedMode: GameMissionMode,
    onSelectMode: (GameMissionMode) -> Unit,
    onDeploy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mission Mode Selector Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameMissionMode.entries.forEach { mode ->
                val isUnlocked = profile.pilotLevel >= mode.minLevel
                val isSelected = mode == selectedMode

                val borderColor = if (isSelected) Color(0xFF00F0FF) else if (isUnlocked) Color(0xFF334155) else Color(0xFF1E293B)
                val bgColor = if (isSelected) Color(0x3300F0FF) else Color(0x800A101D)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(bgColor, RoundedCornerShape(10.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .clickable(enabled = isUnlocked) { onSelectMode(mode) }
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = mode.title,
                            color = if (isUnlocked) Color.White else Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            text = if (isUnlocked) "READY" else "LVL ${mode.minLevel}",
                            color = if (isSelected) Color(0xFF00F0FF) else Color(0xFF94A3B8),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Mission Stats Preview
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xD90A101D), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "BEST ALTITUDE", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(text = "${profile.careerBestAltitude.toInt()}m", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "HIGH SCORE", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(text = "${profile.careerHighScore}", color = Color(0xFFFFB800), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Massive Glowing Sortie Launch Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF00F0FF), Color(0xFF7000FF))
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable { onDeploy() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚡ DEPLOY SORTIE ⚡",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Tab 2: Arsenal (Tech Tree & Skins)
// -------------------------------------------------------------------------------------------------

@Composable
private fun ArsenalTabContent(
    economyGateway: EconomyGateway,
    profile: com.arcade.bubbledefense.domain.games.PlayerProfile
) {
    var droneLevel by remember { mutableIntStateOf(economyGateway.droneUpgradeLevel) }
    var equippedShieldSkin by remember { mutableStateOf(economyGateway.equippedShieldSkinId) }
    var equippedBubbleSkin by remember { mutableStateOf(economyGateway.equippedBubbleSkinId) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                text = "// SENTINEL DRONE TECH BAY",
                color = Color(0xFF00F0FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        item {
            val cost = when (droneLevel) {
                0 -> 15
                1 -> 35
                else -> 0
            }
            val canUpgrade = droneLevel < 2 && profile.careerPrismsCollected >= cost

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xD90A101D), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        text = "SIDELINE SENTINEL DRONES: LEVEL $droneLevel / 2",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (droneLevel) {
                            0 -> "No drones equipped. Level 1 deploys Left Sentinel with auto-targeting laser."
                            1 -> "Left Drone active. Level 2 deploys Right Drone + unlocks 5.0s Overdrive Barrage."
                            else -> "Maximum tech tier! Twin Sentinel Drones with tactical Overdrive Barrages."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (droneLevel < 2) {
                        Button(
                            onClick = {
                                if (economyGateway.upgradeDrone()) {
                                    droneLevel = economyGateway.droneUpgradeLevel
                                }
                            },
                            enabled = canUpgrade,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00F0FF),
                                disabledContainerColor = Color(0xFF1E293B)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "UPGRADE SENTINELS (💎 $cost)",
                                color = if (canUpgrade) Color.Black else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "// PROCEDURAL COSMETIC SKINS",
                color = Color(0xFF00F0FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldSkin.ALL_SHIELDS.forEach { skin ->
                    val isEquipped = skin.id == equippedShieldSkin
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isEquipped) Color(0x3300F0FF) else Color(0x800A101D), RoundedCornerShape(8.dp))
                            .border(1.dp, if (isEquipped) Color(0xFF00F0FF) else Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .clickable {
                                economyGateway.equippedShieldSkinId = skin.id
                                equippedShieldSkin = skin.id
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = skin.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(text = if (skin.costPrisms == 0) "Standard Issue" else "${skin.costPrisms} Prisms", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = if (isEquipped) "EQUIPPED" else "EQUIP",
                            color = if (isEquipped) Color(0xFF00F0FF) else Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Tab 3: Bounties (Missions & Challenges)
// -------------------------------------------------------------------------------------------------

@Composable
private fun BountiesTabContent(
    bounties: List<BountyMission>,
    onClaim: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                text = "// TACTICAL SORTIE BOUNTIES",
                color = Color(0xFF00F0FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        items(bounties) { b ->
            val canClaim = b.isCompleted && !b.isClaimed
            val borderColor = if (canClaim) Color(0xFF00FF88) else Color(0xFF1E293B)
            val bgColor = if (canClaim) Color(0x2600FF88) else Color(0xD90A101D)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(10.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = b.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = b.description,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { b.progressFraction },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(5.dp)
                                    .clip(CircleShape),
                                color = if (b.isCompleted) Color(0xFF00FF88) else Color(0xFF00F0FF),
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${b.currentProgress}/${b.targetProgress}",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (b.isClaimed) {
                        Text(
                            text = "CLAIMED",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (canClaim) {
                        Button(
                            onClick = { onClaim(b.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CLAIM +${b.prismReward}💎",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        Text(
                            text = "+${b.prismReward}💎",
                            color = Color(0xFF00F0FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Tab 4: Data Link (Leaderboards & Scoreboards)
// -------------------------------------------------------------------------------------------------

@Composable
private fun DataLinkTabContent(
    scoreboards: List<ScoreboardEntry>,
    profile: com.arcade.bubbledefense.domain.games.PlayerProfile
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Text(
                text = "// LOCAL SECTOR SCOREBOARDS",
                color = Color(0xFF00F0FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(scoreboards) { entry ->
            val isCurrent = entry.isCurrentPlayer || entry.pilotName == profile.gamerTag
            val borderColor = if (isCurrent) Color(0xFF00F0FF) else Color(0xFF1E293B)
            val bgColor = if (isCurrent) Color(0x3300F0FF) else Color(0xD90A101D)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${entry.rank}",
                        color = if (entry.rank <= 3) Color(0xFFFFB800) else Color(0xFF64748B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(32.dp)
                    )
                    Column {
                        Text(
                            text = entry.pilotName,
                            color = if (isCurrent) Color(0xFF00F0FF) else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Altitude: ${entry.altitudeMeters.toInt()}m • Wave ${entry.wave}",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = "${entry.score}",
                    color = Color(0xFFFFB800),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Tab 5: System Settings
// -------------------------------------------------------------------------------------------------

@Composable
private fun SystemSettingsTabContent(
    onReplayIntro: () -> Unit
) {
    var highRefreshMode by remember { mutableStateOf(true) }
    var hapticsEnabled by remember { mutableStateOf(true) }
    var synthVolume by remember { mutableFloatStateOf(0.85f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "// TACTICAL AVIONICS SETTINGS",
            color = Color(0xFF00F0FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        // 120Hz Mode Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xD90A101D), RoundedCornerShape(8.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "120Hz ULTRA-REFRESH", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(text = "Locked hardware Canvas frame pacing", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Switch(
                checked = highRefreshMode,
                onCheckedChange = { highRefreshMode = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F0FF))
            )
        }

        // Haptics Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xD90A101D), RoundedCornerShape(8.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "TACTILE HAPTIC JUICE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(text = "Vibrations on Perfect Parries and damage", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Switch(
                checked = hapticsEnabled,
                onCheckedChange = { hapticsEnabled = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F0FF))
            )
        }

        // Replay Lore Intro Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xD90A101D), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF00F0FF), RoundedCornerShape(8.dp))
                .clickable { onReplayIntro() }
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "REPLAY CORE LORE INTRO",
                color = Color(0xFF00F0FF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Bottom Navigation Dock
// -------------------------------------------------------------------------------------------------

@Composable
private fun BottomNavigationDock(
    activeTab: MenuTab,
    onSelectTab: (MenuTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(Color(0xF2060B14), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        MenuTab.entries.forEach { tab ->
            val isSelected = tab == activeTab
            val color = if (isSelected) Color(0xFF00F0FF) else Color(0xFF64748B)

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectTab(tab) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = tab.icon, fontSize = 15.sp)
                Text(
                    text = tab.title,
                    color = color,
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Edit Callsign Dialog
// -------------------------------------------------------------------------------------------------

@Composable
private fun EditTagDialog(
    currentTag: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentTag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A101D),
        title = {
            Text(
                text = "PILOT CALLSIGN",
                color = Color(0xFF00F0FF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(16) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00F0FF),
                    unfocusedBorderColor = Color(0xFF334155)
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF))
            ) {
                Text(text = "CONFIRM", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "CANCEL", color = Color(0xFF94A3B8), fontFamily = FontFamily.Monospace)
            }
        }
    )
}
