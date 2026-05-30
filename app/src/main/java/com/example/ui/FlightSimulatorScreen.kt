package com.example.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.math.Orientation
import com.example.math.Vector3D
import com.example.model.X15Model
import com.example.physics.FlightPhysics
import com.example.physics.MissionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun SimulatorHeader(hasCaution: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF1C1B1F))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Color(0xFFE6E1E5),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "X-15 Flight Sim",
                color = Color(0xFFE6E1E5),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.5).sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasCaution) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50.dp))
                        .background(Color(0xFF31111D))
                        .border(1.dp, Color(0xFF93000A), RoundedCornerShape(50.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "MASTER CAUTION",
                        color = Color(0xFFFFB4AB),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color(0xFFD0BCFF),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlightSimulatorScreen() {
    val scope = rememberCoroutineScope()
    val flightPhysics = remember { FlightPhysics() }
    val x15Model = remember { X15Model() }
    val soundManager = remember { FlightSoundManager() }

    // Interactive cockpit state observables
    var pitchInput by remember { mutableStateOf(0.0) }  // -1.0 to 1.0
    var rollInput by remember { mutableStateOf(0.0) }   // -1.0 to 1.0
    var yawInput by remember { mutableStateOf(0.0) }    // -1.0 to 1.0

    var isMuted by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf("Chase") } // "Chase", "Cockpit", "Orbit"
    var showMissionSelect by remember { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }

    // Dynamic orbital camera variables
    var orbitYaw by remember { mutableStateOf(0.0) }
    var orbitPitch by remember { mutableStateOf(0.15) }

    // Sound manager ignition sync
    LaunchedEffect(flightPhysics.isRocketIgnited, flightPhysics.throttle, flightPhysics.dynamicPressureQ, pitchInput, rollInput, yawInput, isMuted) {
        soundManager.throttleValue = if (flightPhysics.isRocketIgnited && flightPhysics.propellantPercent > 0.0) {
            flightPhysics.throttle.toFloat() / 100.0f
        } else {
            0.0f
        }
        soundManager.dynamicPressureQ = flightPhysics.dynamicPressureQ.toFloat()
        val hasRcsInput = (abs(pitchInput) > 0.15 || abs(rollInput) > 0.15 || abs(yawInput) > 0.15)
        soundManager.rcsBursts = if (flightPhysics.isRcsActive && flightPhysics.rcsFuelPercent > 0.0 && hasRcsInput) 1.0f else 0.0f
        soundManager.isMuted = isMuted
    }

    // Active life-cycle start sound manager
    DisposableEffect(Unit) {
        soundManager.start()
        onDispose {
            soundManager.stop()
        }
    }

    // 120 PROCEDURAL STARS SEEDED ONCE
    val stars = remember {
        List(100) {
            val y = (Math.random() * 2.0 - 1.0)
            val theta = Math.random() * 2.0 * Math.PI
            val r = sqrt(1.0 - y * y)
            val x = r * cos(theta)
            val z = r * sin(theta)
            Vector3D(x, y, z).normalized()
        }
    }

    // Continuous physics update coroutine loop
    var gameTicks by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val dt = 1.0 / 30.0 // 30 FPS physics update rate
        while (isActive) {
            val startFrame = System.currentTimeMillis()

            flightPhysics.update(dt, pitchInput, rollInput, yawInput)
            gameTicks++

            // Orbit camera automatic return glide
            if (selectedCamera == "Orbit") {
                orbitYaw += 0.005
            } else {
                orbitYaw = 0.0
                orbitPitch = 0.18
            }

            // Target frame latency delay
            val elapsed = System.currentTimeMillis() - startFrame
            val rest = (dt * 1000 - elapsed).toLong().coerceIn(2, 33)
            delay(rest)
        }
    }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current

    // Set focus on keyboard capture for arrow keys
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                // Capture arrow controls for streaming environment ease of play
                val isDown = keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                val modifier = if (keyEvent.nativeKeyEvent.isShiftPressed) 1.0 else 0.5
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        pitchInput = if (isDown) -modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        pitchInput = if (isDown) modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        rollInput = if (isDown) -modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        rollInput = if (isDown) modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_W -> {
                        pitchInput = if (isDown) -modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_S -> {
                        pitchInput = if (isDown) modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_A -> {
                        rollInput = if (isDown) -modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_D -> {
                        rollInput = if (isDown) modifier else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_Q -> {
                        yawInput = if (isDown) -0.5 else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_E -> {
                        yawInput = if (isDown) 0.5 else 0.0
                        true
                    }
                    KeyEvent.KEYCODE_SPACE -> {
                        if (isDown) {
                            if (flightPhysics.phase == MissionPhase.MOTHERSHIP_ATTACHED) {
                                flightPhysics.phase = MissionPhase.GRAVITY_FALL
                            } else {
                                flightPhysics.isRocketIgnited = !flightPhysics.isRocketIgnited
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        val totalWidth = this.maxWidth
        val totalHeight = this.maxHeight
        val hasCaution = (flightPhysics.altitudeFt < 6000.0 && !flightPhysics.isLowerFinJettisoned && flightPhysics.phase == MissionPhase.FINAL_APPROACH) ||
                         (flightPhysics.propellantPercent < 15.0 && flightPhysics.isRocketIgnited && flightPhysics.propellantPercent > 0.0) ||
                         (flightPhysics.gForce > 5.5) ||
                         (flightPhysics.isSpeedBrakeDeployed && flightPhysics.machNumber > 2.0)

        Column(modifier = Modifier.fillMaxSize()) {
            SimulatorHeader(hasCaution = hasCaution)

            val availableWidth = totalWidth
            val availableHeight = totalHeight
            val isLandscape = availableWidth > availableHeight

            if (isLandscape) {
                // LANDSCAPE: SIDE-BY-SIDE PANELS WITH FLOATING SCREEN aesthetics
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 3D Screen Canvas View (70% Wide)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1.7f)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
                            .background(Color.Black)
                    ) {
                        SimulationViewport(
                            flightPhysics = flightPhysics,
                            x15Model = x15Model,
                            stars = stars,
                            cameraType = selectedCamera,
                            orbitYaw = orbitYaw,
                            orbitPitch = orbitPitch,
                            ticks = gameTicks
                        )

                        // Overlay HUD gauges over 3D Canvas
                        CockpitHudOverlay(flightPhysics = flightPhysics, ticks = gameTicks)

                        // Quick Camera/Mute toggles on canvas corner
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { selectedCamera = when(selectedCamera) {
                                    "Chase" -> "Cockpit"
                                    "Cockpit" -> "Orbit"
                                    else -> "Chase"
                                }},
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0, 0, 0, 140))
                            ) {
                                Text("CAM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            IconButton(
                                onClick = { isMuted = !isMuted },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0, 0, 0, 140))
                            ) {
                                Text(if (isMuted) "MUTE" else "SOUND", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Interactive Flight Deck Dashboard controls (30% Wide)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1.0f)
                            .background(Color(0xFF1C1B1F))
                    ) {
                        FlightDeckPanel(
                            flightPhysics = flightPhysics,
                            pitchInput = pitchInput,
                            rollInput = rollInput,
                            yawInput = yawInput,
                            onPitchChange = { pitchInput = it },
                            onRollChange = { rollInput = it },
                            onYawChange = { yawInput = it },
                            onChangeMission = { showMissionSelect = true },
                            onShowHelp = { showHelp = true }
                        )
                    }
                }
            } else {
                // PORTRAIT: TOP CANVAS, BOTTOM DECK WITH FLOATING SCREEN aesthetics
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 3D Canvas Screen (takes up ~55% height)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.2f)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
                            .background(Color.Black)
                    ) {
                        SimulationViewport(
                            flightPhysics = flightPhysics,
                            x15Model = x15Model,
                            stars = stars,
                            cameraType = selectedCamera,
                            orbitYaw = orbitYaw,
                            orbitPitch = orbitPitch,
                            ticks = gameTicks
                        )

                        CockpitHudOverlay(flightPhysics = flightPhysics, ticks = gameTicks)

                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { selectedCamera = when(selectedCamera) {
                                    "Chase" -> "Cockpit"
                                    "Cockpit" -> "Orbit"
                                    else -> "Chase"
                                }},
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0, 0, 0, 140))
                            ) {
                                Text("CAM", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            IconButton(
                                onClick = { isMuted = !isMuted },
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0, 0, 0, 140))
                            ) {
                                Text(if (isMuted) "MUTE" else "SOUND", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Interactive Control deck board (takes up ~45% height)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.0f)
                            .background(Color(0xFF1C1B1F))
                    ) {
                        FlightDeckPanel(
                            flightPhysics = flightPhysics,
                            pitchInput = pitchInput,
                            rollInput = rollInput,
                            yawInput = yawInput,
                            onPitchChange = { pitchInput = it },
                            onRollChange = { rollInput = it },
                            onYawChange = { yawInput = it },
                            onChangeMission = { showMissionSelect = true },
                            onShowHelp = { showHelp = true }
                        )
                    }
                }
            }
        }

        // POP-UP MISSION SELECT BRIEFING DIALOG
        if (showMissionSelect) {
            MissionSelectDialog(
                currentPhase = flightPhysics.phase,
                onSelectMission = { missionKey ->
                    flightPhysics.resetState(missionKey)
                    showMissionSelect = false
                },
                onDismiss = { showMissionSelect = false }
            )
        }

        // TUTORIAL & SCHEMATICS COOP BOARD
        if (showHelp) {
            HelpDialog(onDismiss = { showHelp = false })
        }

        // CRASH COMPROMISED SCREEN DIALOG
        if (flightPhysics.phase == MissionPhase.CRASHED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD01C1B1F)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(360.dp)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF31111D)),
                    border = BorderStroke(1.dp, Color(0xFFF2B8B5)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Hull compromised",
                            tint = Color(0xFFF2B8B5),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "CRITICAL FAILURE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFFF2B8B5),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            flightPhysics.crashMessage,
                            color = Color(0xFFCAC4D0),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                flightPhysics.resetState(MissionPhase.MOTHERSHIP_ATTACHED)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF93000A)),
                            border = BorderStroke(1.dp, Color(0xFFF2B8B5)),
                            modifier = Modifier.fillMaxWidth().testTag("restart_button")
                        ) {
                            Text("RE-LAUNCH B-52 MISSION", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        // FLIGHT SUCCESSFULLY COMPLETED CELEBRATION CARD
        if (flightPhysics.phase == MissionPhase.LOCKED_LANDED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD01C1B1F)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(380.dp)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                    border = BorderStroke(1.dp, Color(0xFFD0BCFF)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Mission Success",
                            tint = Color(0xFFD0BCFF),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "MISSION COMPLETED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFFD0BCFF),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "You have successfully piloted the North American X-15 to a historic touchdown at Rogers Dry Lake!",
                            color = Color(0xFFE6E1E5),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFF49454F))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Max Altitude:", color = Color(0xFFCAC4D0), fontSize = 12.sp)
                            Text("${(flightPhysics.maxAltitudeReached).toInt()} ft", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Max Velocity:", color = Color(0xFFCAC4D0), fontSize = 12.sp)
                            Text("Mach ${String.format("%.2f", flightPhysics.maxMachReached)}", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                showMissionSelect = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF381E72)),
                            border = BorderStroke(1.dp, Color(0xFFD0BCFF)),
                            modifier = Modifier.fillMaxWidth().testTag("complete_ok_button")
                        ) {
                            Text("SELECT NEXT MISSION", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// 3D SCENE RENDER ENGINE VIEWPORT
@Composable
fun SimulationViewport(
    flightPhysics: FlightPhysics,
    x15Model: X15Model,
    stars: List<Vector3D>,
    cameraType: String,
    orbitYaw: Double,
    orbitPitch: Double,
    ticks: Long
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("flight_sim_canvas")
    ) {
        val w = size.width
        val h = size.height

        // 1. SKYBOX SPACE GRADIENT (Altitude-dependent)
        val skyBrush = when {
            flightPhysics.altitudeFt < 35000.0 -> {
                Brush.verticalGradient(listOf(Color(18, 55, 93), Color(41, 128, 185)))
            }
            flightPhysics.altitudeFt < 125000.0 -> {
                val f = ((flightPhysics.altitudeFt - 35000.0) / 90000.0).toFloat().coerceIn(0f, 1f)
                val topCol = lerpColor(Color(18, 55, 93), Color(2, 3, 5), f)
                val botCol = lerpColor(Color(41, 128, 185), Color(10, 15, 25), f)
                Brush.verticalGradient(listOf(topCol, botCol))
            }
            else -> {
                Brush.verticalGradient(listOf(Color(0, 0, 1), Color(2, 3, 5)))
            }
        }

        drawRect(brush = skyBrush, size = size)

        // 2. DEFINE CAMERA ORIENTATION & BASIS FOR 3D ENGINE
        // In flight physics system: Plane is at position.
        // We calculate all 3D vectors relative to the aircraft, rotated into the aircraft's local basis.
        val camPos: Vector3D
        val camPitch: Double
        val camYaw: Double
        val camRoll: Double

        when (cameraType) {
            "Chase" -> {
                // Sitted behind the plane trailing forward
                camPos = Vector3D(0.0, 1.4, -13.0)
                camPitch = 0.12 // look down slightly
                camYaw = 0.0
                camRoll = 0.0
            }
            "Cockpit" -> {
                // Centered inside the glass canopy, looking forward
                camPos = Vector3D(0.0, 0.44, 2.50)
                camPitch = 0.05
                camYaw = 0.0
                // Match the visual tilt/roll of the plane inside cockpit view!
                camRoll = 0.0
            }
            else -> {
                // Orbit mode
                camPos = Vector3D(
                    x = 15.0 * sin(orbitYaw),
                    y = 3.0 + 12.0 * sin(orbitPitch),
                    z = -15.0 * cos(orbitYaw)
                )
                camPitch = orbitPitch
                camYaw = orbitYaw
                camRoll = 0.0
            }
        }

        // 3. DRAW STATIONARY STARS SPHERE (Infinite Distance rotation projection)
        // Stars stay fixed against movement but slide dynamically against plane rotation!
        clipRect(0f, 0f, w, h) {
            for (vStar in stars) {
                // Rotated purely by current aircraft orientation *and* camera direction
                // 1. Transform star relative to airplane basis
                val localStar = Vector3D(
                    vStar.dot(flightPhysics.orientation.right),
                    vStar.dot(flightPhysics.orientation.up),
                    vStar.dot(flightPhysics.orientation.forward)
                )

                // 2. Adjust camera orbit/look direction offsets
                var pStarCam = localStar
                if (camYaw != 0.0) pStarCam = pStarCam.rotateY(camYaw)
                if (camPitch != 0.0) pStarCam = pStarCam.rotateX(camPitch)

                // Render star points if in front of nose viewport
                if (pStarCam.z > 0.05) {
                    val fov = w * 0.75
                    val px = w / 2f + (pStarCam.x * fov / pStarCam.z).toFloat()
                    val py = h / 2f - (pStarCam.y * fov / pStarCam.z).toFloat()

                    // Star brightness scales with altitude
                    val altitudeStarglow = (flightPhysics.altitudeFt / 120000.0).coerceIn(0.1, 1.0)
                    val starSize = if (vStar.x > 0.8) 4f else if (vStar.y > 0.5) 3f else 2f

                    drawCircle(
                        color = Color.White.copy(alpha = (altitudeStarglow * 0.95f).toFloat()),
                        radius = starSize,
                        center = androidx.compose.ui.geometry.Offset(px, py)
                    )
                }
            }

            // 4. DRAW EARTH HORIZON (A vast curved grid surface beneath our simulation)
            val earthRadius = 6371000.0
            val altitudeMeters = flightPhysics.position.y
            // Calculate horizon distance based on altitude curvature
            val horizonDistance = sqrt(2.0 * earthRadius * altitudeMeters + altitudeMeters * altitudeMeters)

            // Let's project desert terrain lines
            // We use a modular ground grid positioned globally under Rogers Dry Lake Bed
            val gridSpacing = 1600.0
            val maxDrawDist = 18000.0

            // Base ground point transformation projection
            fun transformGroundPoint(gx: Double, gz: Double): Vector3D {
                val relP = Vector3D(gx, 0.0, gz) - flightPhysics.position
                return Vector3D(
                    relP.dot(flightPhysics.orientation.right),
                    relP.dot(flightPhysics.orientation.up),
                    relP.dot(flightPhysics.orientation.forward)
                )
            }

            val startZ = (flightPhysics.position.z - maxDrawDist).roundToMultiple(gridSpacing)
            val endZ = (flightPhysics.position.z + maxDrawDist).roundToMultiple(gridSpacing)
            val startX = (flightPhysics.position.x - maxDrawDist).roundToMultiple(gridSpacing)
            val endX = (flightPhysics.position.x + maxDrawDist).roundToMultiple(gridSpacing)

            // Dynamic grid colors matching Mojave Desert orange salt clay
            val activeGridColor = Color(222, 142, 60, 110)

            // Draw lateral grid wires
            var curZ = startZ
            while (curZ <= endZ) {
                val pathZ = Path()
                var begun = false

                var curX = startX
                while (curX <= endX) {
                    val pLocal = transformGroundPoint(curX, curZ)
                    var pCam = pLocal - camPos
                    if (camYaw != 0.0) pCam = pCam.rotateY(camYaw)
                    if (camPitch != 0.0) pCam = pCam.rotateX(camPitch)

                    if (pCam.z > 0.15) {
                        val fov = w * 0.75
                        val px = w / 2f + (pCam.x * fov / pCam.z).toFloat()
                        val py = h / 2f - (pCam.y * fov / pCam.z).toFloat()

                        if (!begun) {
                            pathZ.moveTo(px, py)
                            begun = true
                        } else {
                            pathZ.lineTo(px, py)
                        }
                    } else {
                        begun = false
                    }
                    curX += 200.0 // Sample dense line increments
                }
                drawPath(pathZ, activeGridColor, style = Stroke(width = 1.5f))
                curZ += gridSpacing
            }

            // Draw longitudinal grid wires
            var curX = startX
            while (curX <= endX) {
                val pathX = Path()
                var begun = false

                var curZ = startZ
                while (curZ <= endZ) {
                    val pLocal = transformGroundPoint(curX, curZ)
                    var pCam = pLocal - camPos
                    if (camYaw != 0.0) pCam = pCam.rotateY(camYaw)
                    if (camPitch != 0.0) pCam = pCam.rotateX(camPitch)

                    if (pCam.z > 0.15) {
                        val fov = w * 0.75
                        val px = w / 2f + (pCam.x * fov / pCam.z).toFloat()
                        val py = h / 2f - (pCam.y * fov / pCam.z).toFloat()

                        if (!begun) {
                            pathX.moveTo(px, py)
                            begun = true
                        } else {
                            pathX.lineTo(px, py)
                        }
                    } else {
                        begun = false
                    }
                    curZ += 200.0
                }
                drawPath(pathX, activeGridColor, style = Stroke(width = 1.5f))
                curX += gridSpacing
            }

            // 5. DRAW CLAY RUNWAY STRIP (Edwards Dry Lakebed Runway 18/36: wide red lines)
            val runwayWidth = 300.0
            val runwayLength = 12000.0
            val runwayZBorder = 6000.0

            val rwLeftStart = transformGroundPoint(-runwayWidth/2, -runwayZBorder)
            val rwLeftEnd = transformGroundPoint(-runwayWidth/2, runwayZBorder)
            val rwRightStart = transformGroundPoint(runwayWidth/2, -runwayZBorder)
            val rwRightEnd = transformGroundPoint(runwayWidth/2, runwayZBorder)

            fun projectCameraSpace(pLocal: Vector3D): android.graphics.PointF? {
                var pCam = pLocal - camPos
                if (camYaw != 0.0) pCam = pCam.rotateY(camYaw)
                if (camPitch != 0.0) pCam = pCam.rotateX(camPitch)
                if (pCam.z <= 0.15) return null
                val fov = w * 0.75
                val px = w / 2f + (pCam.x * fov / pCam.z).toFloat()
                val py = h / 2f - (pCam.y * fov / pCam.z).toFloat()
                return android.graphics.PointF(px, py)
            }

            val pLStart = projectCameraSpace(rwLeftStart)
            val pLEnd = projectCameraSpace(rwLeftEnd)
            val pRStart = projectCameraSpace(rwRightStart)
            val pREnd = projectCameraSpace(rwRightEnd)

            if (pLStart != null && pLEnd != null && pRStart != null && pREnd != null) {
                val runwayOutline = Path()
                runwayOutline.moveTo(pLStart.x, pLStart.y)
                runwayOutline.lineTo(pRStart.x, pRStart.y)
                runwayOutline.lineTo(pREnd.x, pREnd.y)
                runwayOutline.lineTo(pLEnd.x, pLEnd.y)
                runwayOutline.close()

                // Sand runway fill
                drawPath(runwayOutline, Color(245, 230, 210, 100), style = Fill)
                // Red boundaries
                drawPath(runwayOutline, Color(231, 76, 60), style = Stroke(width = 4f))

                // Center dash markings
                var dashZ = -runwayZBorder
                while (dashZ <= runwayZBorder) {
                    val pDashStart = projectCameraSpace(transformGroundPoint(0.0, dashZ))
                    val pDashEnd = projectCameraSpace(transformGroundPoint(0.0, dashZ + 400.0))
                    if (pDashStart != null && pDashEnd != null) {
                        drawLine(
                            color = Color(231, 76, 60),
                            start = androidx.compose.ui.geometry.Offset(pDashStart.x, pDashStart.y),
                            end = androidx.compose.ui.geometry.Offset(pDashEnd.x, pDashEnd.y),
                            strokeWidth = 6f
                        )
                    }
                    dashZ += 1200.0
                }
            }

            // 6. RENDER 3D X-15 MODEL (Skipped in Cockpit view since we are inside)
            if (cameraType != "Cockpit") {
                val thrustFlameValue = if (flightPhysics.isRocketIgnited) flightPhysics.throttle.toFloat() / 100.0f else 0.0f
                x15Model.render(
                    drawScope = this,
                    width = w,
                    height = h,
                    cameraPos = camPos,
                    cameraPitchRad = camPitch,
                    cameraYawRad = camYaw,
                    cameraRollRad = camRoll,
                    isLowerFinJettisoned = flightPhysics.isLowerFinJettisoned,
                    isGearDeployed = flightPhysics.isGearDeployed,
                    thrustFlameLevel = thrustFlameValue,
                    timeMs = System.currentTimeMillis()
                )
            }
        }
    }
}

// ELECTRONIC COCKPIT GLASS HUD DISPLAY OVERLAY
@Composable
fun CockpitHudOverlay(flightPhysics: FlightPhysics, ticks: Long) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cockpit_hud_overlay")
    ) {
        // Dynamic horizontal pitch/roll scale in HUD center
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. HORIZONTAL PITCH & ROLL LADDER HUD LINES
            val hudGreen = Color(0xFFD0BCFF)
            val strokeThick = 2f

            // Clean overlay bounding lines
            clipRect(w * 0.15f, h * 0.12f, w * 0.85f, h * 0.88f) {
                // Pitch crosshair marker (aircraft symbol at exact screen center)
                drawCircle(color = hudGreen, radius = 4f, center = androidx.compose.ui.geometry.Offset(w/2, h/2))
                drawLine(hudGreen, androidx.compose.ui.geometry.Offset(w/2 - 25f, h/2), androidx.compose.ui.geometry.Offset(w/2 - 8f, h/2), strokeWidth = strokeThick)
                drawLine(hudGreen, androidx.compose.ui.geometry.Offset(w/2 + 8f, h/2), androidx.compose.ui.geometry.Offset(w/2 + 25f, h/2), strokeWidth = strokeThick)
                drawLine(hudGreen, androidx.compose.ui.geometry.Offset(w/2 - 25f, h/2), androidx.compose.ui.geometry.Offset(w/2 - 25f, h/2 + 8f), strokeWidth = strokeThick)
                drawLine(hudGreen, androidx.compose.ui.geometry.Offset(w/2 + 25f, h/2), androidx.compose.ui.geometry.Offset(w/2 + 25f, h/2 + 8f), strokeWidth = strokeThick)

                // Pitch ladder increments (+20, +10, 0, -10, -20)
                // Pitch ladder rotates and moves vertically depending on plane parameters
                val pitch = flightPhysics.angleOfAttackAoA // simplify HUD orientation reading
                val factor = 8f // pixels per pitch degree
                val yOffset = (pitch * factor).toFloat()

                // Horizontal ladder pitch cross bars
                val drawPitchBars = listOf(-40, -20, 0, 20, 40)
                for (bar in drawPitchBars) {
                    val barY = h / 2f + yOffset - (bar * factor).toFloat()
                    val barW = 120f

                    drawLine(
                        color = hudGreen.copy(alpha = 0.65f),
                        start = androidx.compose.ui.geometry.Offset(w/2 - barW, barY),
                        end = androidx.compose.ui.geometry.Offset(w/2 - 30f, barY),
                        strokeWidth = strokeThick
                    )
                    drawLine(
                        color = hudGreen.copy(alpha = 0.65f),
                        start = androidx.compose.ui.geometry.Offset(w/2 + 30f, barY),
                        end = androidx.compose.ui.geometry.Offset(w/2 + barW, barY),
                        strokeWidth = strokeThick
                    )
                }

                // Sideslip beta indicator indicator
                drawCircle(
                    color = hudGreen,
                    radius = 4f,
                    center = androidx.compose.ui.geometry.Offset(w / 2f, (h / 2f + yOffset))
                )
            }
        }

        // Telemetry Left Text Blocks: Airspeed/Mach indicators
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
                .background(Color(0xD01C1B1F), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text("AIRSPEED", color = Color(0xFFCCC48E), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("${(flightPhysics.airspeedKnots).toInt()} KT", color = Color(0xFFD0BCFF), fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            HorizontalDivider(color = Color(0xFF49454F), modifier = Modifier.padding(vertical = 4.dp))
            Text("VELOCITY", color = Color(0xFFCCC48E), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("MACH ${String.format("%.2f", flightPhysics.machNumber)}", color = Color(0xFFD0BCFF), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        // Telemetry Right Text Blocks: Altimeter, VSI Rate of Climb indicators
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
                .background(Color(0xD01C1B1F), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text("ALTITUDE", color = Color(0xFFCCC48E), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("${(flightPhysics.altitudeFt).toInt()} FT", color = Color(0xFFD0BCFF), fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            HorizontalDivider(color = Color(0xFF49454F), modifier = Modifier.padding(vertical = 4.dp))
            Text("CLIMB RATIO", color = Color(0xFFCCC48E), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val isClimbing = flightPhysics.rateOfClimbVsi >= 0
            Text(
                "${if (isClimbing) "+" else ""}${(flightPhysics.rateOfClimbVsi).toInt()} FPM",
                color = if (isClimbing) Color(0xFFD0BCFF) else Color(0xFFF2B8B5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Top Status Telemetry: Dynamic Dynamic Pressure Q & G-Loads
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color(0xD01C1B1F), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("G-LOAD:", color = Color(0xFFCCC48E), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                val gVal = flightPhysics.gForce
                val gCol = if (gVal > 6.0) Color(0xFFF2B8B5) else if (gVal > 4.5) Color(0xFFFFB4AB) else Color(0xFFD0BCFF)
                Text("${String.format("%.1f", gVal)} G", color = gCol, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            // Interactive Phase Nameplate Badge
            val phaseColorAndName = when (flightPhysics.phase) {
                MissionPhase.MOTHERSHIP_ATTACHED -> Pair("B-52 RECON", Color(0xFFD0BCFF))
                MissionPhase.GRAVITY_FALL -> Pair("GRAVITY DROP", Color(0xFFCCC48E))
                MissionPhase.POWERED_ASCENT -> Pair("POWERED BOOST", Color(0xFFF2B8B5))
                MissionPhase.SPACE_GLIDE -> Pair("SUBORBITAL SPACE", Color(0xFFD0BCFF))
                MissionPhase.REENTRY -> Pair("RE-ENTRY RECOVERY", Color(0xFFFFB4AB))
                MissionPhase.FINAL_APPROACH -> Pair("GLIDE SLOPE", Color(0xFFD0BCFF))
                else -> Pair("PILOT SECURED", Color.Gray)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(phaseColorAndName.second.copy(alpha = 0.15f))
                    .border(1.dp, phaseColorAndName.second.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    phaseColorAndName.first.uppercase(),
                    color = phaseColorAndName.second,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AOA:", color = Color(0xFFCCC48E), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("${String.format("%.1f", flightPhysics.angleOfAttackAoA)}°", color = Color(0xFFD0BCFF), fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// INTERACTIVE FLIGHT DECK PANEL SYSTEM (BOTTOM BOARD / SIDE PANEL)
@Composable
fun FlightDeckPanel(
    flightPhysics: FlightPhysics,
    pitchInput: Double,
    rollInput: Double,
    yawInput: Double,
    onPitchChange: (Double) -> Unit,
    onRollChange: (Double) -> Unit,
    onYawChange: (Double) -> Unit,
    onChangeMission: () -> Unit,
    onShowHelp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        // Core telemetry status gauges row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡", color = Color(0xFFD0BCFF), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(
                    "NORTH AMERICAN X-15 COCKPIT",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Buttons to trigger mission selection / schematics board
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onShowHelp,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0BCFF)),
                    modifier = Modifier.height(28.dp).padding(0.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("SCHEMATICS", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Button(
                    onClick = onChangeMission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF381E72)),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("✈", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("MISSIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Grid deck columns
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f)
        ) {
            // LEFT COLUMN: THROTTLE ENGINE INDICATORS AND SYSTEMS
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // XLR99 Propellant fuel indicator card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("AMMONIA/LOX FUEL", color = Color(0xFFCAC4D0), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("${flightPhysics.propellantPercent.toInt()}%", color = if (flightPhysics.propellantPercent > 20) Color(0xFFD0BCFF) else Color(0xFFF2B8B5), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (flightPhysics.propellantPercent / 100.0).toFloat() },
                            color = if (flightPhysics.propellantPercent > 20) Color(0xFFD0BCFF) else Color(0xFFF2B8B5),
                            trackColor = Color(0xFF49454F),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }

                // XLR99 Dynamic Thrust & Ignition button block
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Variable throttle slider lever
                    Card(
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxHeight(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                        border = BorderStroke(1.dp, Color(0xFF49454F)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("THROTTLE", color = Color(0xFFCAC4D0), fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("${flightPhysics.throttle.toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Slider(
                                value = flightPhysics.throttle.toFloat(),
                                onValueChange = { flightPhysics.throttle = it.toDouble() },
                                valueRange = 30f..100f, // XLR99 throttle range
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("throttle_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFD0BCFF),
                                    activeTrackColor = Color(0xFFD0BCFF),
                                    inactiveTrackColor = Color(0xFF49454F)
                                )
                            )
                        }
                    }

                    // Igniter click button & drops
                    Column(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Drop button or igniter button
                        val isAttached = flightPhysics.phase == MissionPhase.MOTHERSHIP_ATTACHED
                        Button(
                            onClick = {
                                if (isAttached) {
                                    flightPhysics.phase = MissionPhase.GRAVITY_FALL
                                } else {
                                    flightPhysics.isRocketIgnited = !flightPhysics.isRocketIgnited
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAttached) Color(0xFF381E72) else if (flightPhysics.isRocketIgnited) Color(0xFF31111D) else Color(0xFF381E72)
                            ),
                            border = BorderStroke(1.dp, if (flightPhysics.isRocketIgnited && !isAttached) Color(0xFF93000A) else Color(0xFF49454F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("igniter_launch_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            val btnText = if (isAttached) "B-52 RELEASE" else if (flightPhysics.isRocketIgnited) "SHUT DOWN" else "IGNITE ENGINE"
                            Text(
                                btnText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                color = if (flightPhysics.isRocketIgnited && !isAttached) Color(0xFFFFB4AB) else Color.White
                            )
                        }

                        // JETTISON LOWER FIN SAFETY BUTTON
                        // X-15 needs lower tail fin jettisoned to avoid striking dry lake runway during landing flare!
                        Button(
                            onClick = { flightPhysics.isLowerFinJettisoned = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (flightPhysics.isLowerFinJettisoned) Color(0xFF1C1B1F) else Color(0xFF381E72)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF49454F)),
                            enabled = !flightPhysics.isLowerFinJettisoned,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("jettison_fin_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                if (flightPhysics.isLowerFinJettisoned) "LWR FIN JETTISONED" else "JETTISON LWR FIN",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (flightPhysics.isLowerFinJettisoned) Color.Gray else Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // RIGHT COLUMN: COCKPIT PNEUMATIC SWITCH SYSTEM AND THE VIRTUAL JOYSTICK
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pneumatic Struts systems status card toggles
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { flightPhysics.isGearDeployed = !flightPhysics.isGearDeployed }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LANDING SKIDS", color = Color(0xFFCAC4D0), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (flightPhysics.isGearDeployed) Color(46, 204, 113) else Color(0xFFF2B8B5))
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { flightPhysics.isSpeedBrakeDeployed = !flightPhysics.isSpeedBrakeDeployed }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SPEED BRAKE", color = Color(0xFFCAC4D0), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (flightPhysics.isSpeedBrakeDeployed) Color(0xFFD0BCFF) else Color(0xFF49454F))
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { flightPhysics.isRcsActive = !flightPhysics.isRcsActive }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("RCS THRUSTERS", color = Color(0xFFCAC4D0), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (flightPhysics.isRcsActive) Color(0xFFD0BCFF) else Color(0xFF49454F))
                            )
                        }
                    }
                }

                // JOYSTICK DRAG PAD INTEGRATION
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val maxDragW = constraints.maxWidth.toFloat()
                        val maxDragH = constraints.maxHeight.toFloat()
                        val padRadius = min(maxDragW, maxDragH) / 2.2f

                        // Back pad visuals
                        Canvas(modifier = Modifier.size((padRadius * 2).dp)) {
                            drawCircle(color = Color(0xFF1C1B1F), radius = size.minDimension / 2.2f)
                            drawCircle(color = Color(0xFF49454F), radius = size.minDimension / 2.2f, style = Stroke(width = 2f))
                            // crosshairs
                            drawLine(Color(0xFF49454F), androidx.compose.ui.geometry.Offset(0f, size.height/2), androidx.compose.ui.geometry.Offset(size.width, size.height/2))
                            drawLine(Color(0xFF49454F), androidx.compose.ui.geometry.Offset(size.width/2, 0f), androidx.compose.ui.geometry.Offset(size.width/2, size.height))
                        }

                        // Joystick thumb draggable pad
                        var jOffsetStateX by remember { mutableStateOf(0f) }
                        var jOffsetStateY by remember { mutableStateOf(0f) }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(jOffsetStateX.toInt(), jOffsetStateY.toInt()) }
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFFD0BCFF),
                                            Color(0xFF381E72)
                                        )
                                    )
                                )
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            jOffsetStateX =
                                                (jOffsetStateX + dragAmount.x).coerceIn(
                                                    -padRadius,
                                                    padRadius
                                                )
                                            jOffsetStateY =
                                                (jOffsetStateY + dragAmount.y).coerceIn(
                                                    -padRadius,
                                                    padRadius
                                                )

                                            // Linearize parameters to flight rates: Y-drag shapes pitch, X-drag shapes roll
                                            onPitchChange((jOffsetStateY / padRadius).toDouble())
                                            onRollChange((jOffsetStateX / padRadius).toDouble())
                                        },
                                        onDragEnd = {
                                            // Spring back center releases rates
                                            jOffsetStateX = 0f
                                            jOffsetStateY = 0f
                                            onPitchChange(0.0)
                                            onRollChange(0.0)
                                        }
                                    )
                                }
                                .testTag("joystick_knob")
                        )
                    }
                }
            }
        }
    }
}

// MULTI-MISSION SELECTOR DIALOG
@Composable
fun MissionSelectDialog(
    currentPhase: MissionPhase,
    onSelectMission: (MissionPhase) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "SELECT FLIGHT RESEARCH MISSION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MissionCard(
                    title = "1. B-52 Mothership Launch",
                    desc = "Suspended under the B-52 NB-52A wing at 45,000 ft over Mud Lake. Pull drop toggle, glide free, start systems checklist, ignite the XLR99 and pull up for climbing arc.",
                    label = "B52",
                    color = Color(52, 152, 219),
                    onClick = { onSelectMission(MissionPhase.MOTHERSHIP_ATTACHED) }
                )

                MissionCard(
                    title = "2. High Altitude Speed Run",
                    desc = "Starts with rocket ignited at 75,000 ft. Accelerate straight through Mach 4 to Mach 6+. Maintain horizontal posture to avoid vertical structural damage.",
                    label = "SPD",
                    color = Color(241, 196, 15),
                    onClick = { onSelectMission(MissionPhase.POWERED_ASCENT) }
                )

                MissionCard(
                    title = "3. Suborbital Space Glide",
                    desc = "Suborbital cruise above 150,000 ft. Atmosphere is near vacuum. Toggle RCS thrusters to orient the plane to space high attitude. Glide in weightlessness.",
                    label = "SPC",
                    color = Color(155, 89, 182),
                    onClick = { onSelectMission(MissionPhase.SPACE_GLIDE) }
                )

                MissionCard(
                    title = "4. Rogers Lakebed Recovery",
                    desc = "Unpowered glider recovery at 15,000 ft. Guide the X-15 down, jettison the lower fin, deploy skids, line up with red-marked runway and flared touchdown at 220 KT.",
                    label = "LDG",
                    color = Color(46, 204, 113),
                    onClick = { onSelectMission(MissionPhase.FINAL_APPROACH) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
        containerColor = Color(20, 22, 26),
        tonalElevation = 6.dp
    )
}

@Composable
fun MissionCard(
    title: String,
    desc: String,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("mission_card_${title.first()}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(30, 34, 42)),
        border = BorderStroke(1.dp, Color(48, 54, 66))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f))
                    .border(1.dp, color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(desc, color = Color.LightGray, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

// AEROSPACE SCHEMATICS COMPASS BOARD DIALOG
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "X-15 FLIGHT STUDY GUIDE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "HISTORY & CONTEXT:\n" +
                    "The North American X-15 is a hypersonic, rocket-powered flight research aircraft. It bridged the gap between atmosphere flight and suborbital spaceflight.",
                    color = Color.LightGray, fontSize = 11.sp, lineHeight = 14.sp
                )
                Text(
                    "FLIGHT PROFILE STEPS:\n" +
                    "1. B-52 DROP: Release from mothership at 45,000 ft.\n" +
                    "2. ASCENT BOOST: Ignite XLR99 engine, nose pitch-up ~35° to shoot for 150K+ feet.\n" +
                    "3. VACUUM FLIGHT: In suborbital vacuum, flight control surfaces are dead. Enable RCS (Reaction Controls) nitrogen jets to pitch & roll.\n" +
                    "4. JETTISON FIN: Before touchdown, jettison the lower tail fin, otherwise it crashes!",
                    color = Color.LightGray, fontSize = 11.sp, lineHeight = 14.sp
                )
                Text(
                    "PNEUMATIC SKID GEAR:\n" +
                    "Deploy skids below 5,000 ft. Sink speed must be below 1,000 ft/m. Slide to a stop on Rogers Lake bed.",
                    color = Color.LightGray, fontSize = 11.sp, lineHeight = 14.sp
                )
                Text(
                    "CONGRUENT CONTROLS:\n" +
                    "• Keyboard Arrow / WASD keys steer pitch/roll.\n" +
                    "• Space key ignites/shutdowns XLR99 engine.\n" +
                    "• Virtual joystick on right panel supports fluid gestures.",
                    color = Color.Yellow, fontSize = 11.sp, lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(52, 152, 219))
            ) {
                Text("DISMISS BRIEFING", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(20, 22, 26)
    )
}

// Math scaling utility
fun <T : Comparable<T>> T.coerceIn(minimumValue: T, maximumValue: T): T {
    if (this < minimumValue) return minimumValue
    if (this > maximumValue) return maximumValue
    return this
}

fun Double.roundToMultiple(multiple: Double): Double {
    return Math.round(this / multiple) * multiple
}

fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val r = start.red + (end.red - start.red) * fraction
    val g = start.green + (end.green - start.green) * fraction
    val b = start.blue + (end.blue - start.blue) * fraction
    val a = start.alpha + (end.alpha - start.alpha) * fraction
    return Color(r, g, b, a)
}
