package com.example.physics

import com.example.math.Orientation
import com.example.math.Vector3D
import kotlin.math.*

enum class MissionPhase {
    MOTHERSHIP_ATTACHED,   // B-52 carry at 45,000 ft
    GRAVITY_FALL,          // Separated from B-52, dropping free
    POWERED_ASCENT,        // XLR99 ignited, rocket thrusting
    SPACE_GLIDE,           // Space attitude, vacuum RCS maneuvers (above 130K ft)
    REENTRY,              // Descent back into thick atmosphere, extreme drag & speed
    FINAL_APPROACH,        // Visual gliding approach to Rogers lakebed
    LOCKED_LANDED,         // Safely stopped on Edwards dry lake bed
    CRASHED                // Impact termination
}

class FlightPhysics {
    // 1. POSITION, VELOCITY AND ATTITUDE
    var position = Vector3D(0.0, 13716.0, 0.0) // initial 45,000 ft
    var velocity = Vector3D(0.0, 0.0, 240.0)  // initial Mach 0.8 travel speed

    val orientation = Orientation()

    // Rotational velocities (radians per sec)
    var pitchRate = 0.0
    var rollRate = 0.0
    var yawRate = 0.0

    // 2. MASS & PROPULSION
    val emptyMass = 6350.0 // kg
    val initialPropellantMass = 7710.0 // kg
    var propellantPercent = 100.0 // 0 to 100%
    var throttle = 100.0 // percent (30% to 100% variable thrust on XLR99)
    var isRocketIgnited = false

    // 3. REACTION CONTROL SYSTEM (RCS)
    var isRcsActive = false
    var rcsFuelPercent = 100.0 // nitrogen gas supply

    // 4. FLIGHT SURFACES
    var isGearDeployed = false
    var isSpeedBrakeDeployed = false
    var isLowerFinJettisoned = false

    // 5. DIAGNOSTICS & FLIGHT INSIGHTS
    var altitudeFt = 45000.0
    var airspeedKnots = 466.0
    var machNumber = 0.8
    var dynamicPressureQ = 0.0 // Pascals
    var angleOfAttackAoA = 0.0 // degrees
    var rateOfClimbVsi = 0.0 // feet per minute
    var maxAltitudeReached = 0.0
    var maxMachReached = 0.0
    var gForce = 1.0
    var crashMessage = ""

    var phase = MissionPhase.MOTHERSHIP_ATTACHED
    private var phaseTime = 0.0

    init {
        resetState(MissionPhase.MOTHERSHIP_ATTACHED)
    }

    fun resetState(selectedPhase: MissionPhase) {
        orientation.reset()
        pitchRate = 0.0
        rollRate = 0.0
        yawRate = 0.0

        propellantPercent = 100.0
        throttle = 100.0
        isRocketIgnited = false
        isRcsActive = false
        rcsFuelPercent = 100.0
        isGearDeployed = false
        isSpeedBrakeDeployed = false
        isLowerFinJettisoned = false
        crashMessage = ""
        phase = selectedPhase
        phaseTime = 0.0

        when (selectedPhase) {
            MissionPhase.MOTHERSHIP_ATTACHED -> {
                // Suspended under B-52
                position = Vector3D(0.0, 13716.0, 0.0) // 45,000 ft
                velocity = Vector3D(0.0, 0.0, 240.0)  // Mach 0.8 speed forward
                orientation.rotateLocal(0.03, 0.0, 0.0) // slight nose high attitude
            }
            MissionPhase.GRAVITY_FALL -> {
                position = Vector3D(0.0, 13716.0, 0.0)
                velocity = Vector3D(0.0, -15.0, 240.0)
                orientation.rotateLocal(-0.05, 0.0, 0.0)
            }
            MissionPhase.POWERED_ASCENT -> {
                // Hot ignition start
                position = Vector3D(0.0, 15000.0, 5000.0)
                velocity = Vector3D(0.0, 100.0, 320.0)
                orientation.rotateLocal(0.35, 0.0, 0.0) // 20 degree climb
                isRocketIgnited = true
            }
            MissionPhase.SPACE_GLIDE -> {
                // High altitude suborbital space flight
                position = Vector3D(0.0, 48000.0, 45000.0) // ~157,000 ft
                velocity = Vector3D(0.0, 500.0, 1200.0)     // Hypersonic upward arc
                orientation.rotateLocal(0.40, 0.0, 0.0)
                isRcsActive = true
                propellantPercent = 25.0
            }
            MissionPhase.REENTRY -> {
                // Hypersonic descent
                position = Vector3D(0.0, 32000.0, 85000.0) // ~104,000 ft
                velocity = Vector3D(0.0, -180.0, 1100.0)   // Mach 3.8 back down
                orientation.rotateLocal(0.12, 0.0, 0.0)    // High AoA nose-high pitch
                propellantPercent = 0.0
            }
            MissionPhase.FINAL_APPROACH -> {
                // Unpowered gliding approach
                position = Vector3D(0.0, 4500.0, 15000.0) // 15,000 ft align
                velocity = Vector3D(0.0, -25.0, 160.0)     // ~310 knots Glide speed
                orientation.rotateLocal(-0.10, 0.0, 0.0)   // Nose down glide
                propellantPercent = 0.0
                isLowerFinJettisoned = true
            }
            else -> {}
        }

        updateCalculatedSpecs()
        maxAltitudeReached = altitudeFt
        maxMachReached = machNumber
    }

    // UPDATE FLIGHT CYCLE
    fun update(dt: Double, pitchInput: Double, rollInput: Double, yawInput: Double) {
        phaseTime += dt

        if (phase == MissionPhase.CRASHED || phase == MissionPhase.LOCKED_LANDED) {
            return // Physics locked
        }

        // 1. UPDATE MISSION PHASES AUTOMATICALLY
        when (phase) {
            MissionPhase.MOTHERSHIP_ATTACHED -> {
                // Locked to B-52's path, flies straight, no response to flight controls except throttle/igniter
                position = Vector3D(0.0, 13716.0, position.z + 240.0 * dt)
                velocity = Vector3D(0.0, 0.0, 240.0)
                orientation.reset()
                orientation.rotateLocal(0.03, 0.0, 0.0)
                updateCalculatedSpecs()
                return // skip active physics
            }
            MissionPhase.GRAVITY_FALL -> {
                if (isRocketIgnited && propellantPercent > 0.0) {
                    phase = MissionPhase.POWERED_ASCENT
                } else if (phaseTime > 15.0) {
                    phase = MissionPhase.FINAL_APPROACH // fallback gliding
                }
            }
            MissionPhase.POWERED_ASCENT -> {
                if (!isRocketIgnited || propellantPercent <= 0.0) {
                    isRocketIgnited = false
                    if (altitudeFt > 100000.0) {
                        phase = MissionPhase.SPACE_GLIDE
                    } else {
                        phase = MissionPhase.FINAL_APPROACH
                    }
                }
            }
            MissionPhase.SPACE_GLIDE -> {
                if (altitudeFt < 100000.0) {
                    phase = MissionPhase.REENTRY
                }
            }
            MissionPhase.REENTRY -> {
                if (altitudeFt < 30000.0) {
                    phase = MissionPhase.FINAL_APPROACH
                }
            }
            else -> {}
        }

        // 2. MASS INTEGRATION
        var currentFuelMass = initialPropellantMass * (propellantPercent / 100.0)
        if (isRocketIgnited && currentFuelMass > 0.0) {
            // XLR99 burns roughly 72 kg propellant per second at 100% thrust
            val fuelBurnRate = 72.0 * (throttle / 100.0)
            val burnThisFrame = fuelBurnRate * dt
            currentFuelMass = max(0.0, currentFuelMass - burnThisFrame)
            propellantPercent = (currentFuelMass / initialPropellantMass) * 100.0
            if (currentFuelMass <= 0.0) {
                isRocketIgnited = false
            }
        }
        val currentMass = emptyMass + currentFuelMass

        // Deplete RCS fuel if active
        if (isRcsActive && rcsFuelPercent > 0.0) {
            val rcsInputActive = abs(pitchInput) > 0.1 || abs(rollInput) > 0.1 || abs(yawInput) > 0.1
            if (rcsInputActive) {
                rcsFuelPercent = max(0.0, rcsFuelPercent - 0.7 * dt)
            }
        }

        // 3. AIR DENSITY & DYNAMIC PRESSURE (Q)
        // Standard atmospheric density lapse equation
        val seaLevelDensity = 1.225
        val scaleHeight = 8500.0
        val airDensity = seaLevelDensity * exp(-position.y / scaleHeight)

        val speed = velocity.length()
        dynamicPressureQ = 0.5 * airDensity * speed * speed

        // 4. ANGULAR ACCELERATIONS & ROTATIONAL INERTIA
        // Aerodynamic control surfaces effectiveness scales with dynamic pressure Q
        val maxControlQ = 14000.0 // Dynamic pressure where control surfaces are fully effective
        val aerodynamicEffectiveness = (dynamicPressureQ / maxControlQ).coerceIn(0.0, 1.0)

        // RCS is highly effective in thin space and vacuum
        val rcsEffectiveness = if (isRcsActive && rcsFuelPercent > 0.0) {
            // RCS is used in vacuum. As dynamic pressure rises, aerodynamic forces overpower it.
            (1.0 - aerodynamicEffectiveness).coerceIn(0.1, 1.0)
        } else {
            0.0
        }

        // Rotational inputs trigger roll/pitch/yaw acceleration
        val pitchAcc = (pitchInput * 0.22) * aerodynamicEffectiveness + (pitchInput * 0.12) * rcsEffectiveness
        val rollAcc = (rollInput * 0.45) * aerodynamicEffectiveness + (rollInput * 0.22) * rcsEffectiveness
        val yawAcc = (yawInput * 0.10) * aerodynamicEffectiveness + (yawInput * 0.06) * rcsEffectiveness

        // Angular velocity damping (aerodynamic damping is higher in thick atmosphere)
        val damping = 1.0 - (0.01 + 0.15 * aerodynamicEffectiveness) * dt
        pitchRate = (pitchRate + pitchAcc * dt) * damping
        rollRate = (rollRate + rollAcc * dt) * damping
        yawRate = (yawRate + yawAcc * dt) * damping

        // Pitch up limitation under heavy G or stalls
        orientation.rotateLocal(pitchRate * dt, rollRate * dt, yawRate * dt)

        // 5. TRANSLATIONAL ACCELERATION & FORCES
        var totalForce = Vector3D()

        // XLR99 Rocket Thrust Force
        if (isRocketIgnited && propellantPercent > 0.0) {
            // XLR99 generates 254,000 N sea-level thrust to 290,000 N vacuum thrust
            val seaLevelThrust = 254000.0
            val vacuumThrust = 290000.0
            val altitudeFactor = (1.0 - exp(-position.y / 15000.0)).coerceIn(0.0, 1.0)
            val currentMaxThrust = seaLevelThrust + (vacuumThrust - seaLevelThrust) * altitudeFactor
            val thrustMagnitude = currentMaxThrust * (throttle / 100.0)
            totalForce += orientation.forward * thrustMagnitude
        }

        // Gravity Force
        val forceGravity = Vector3D(0.0, -9.80665 * currentMass, 0.0)
        totalForce += forceGravity

        // Aerodynamics (Lift & Drag)
        if (speed > 1.0) {
            val travelDir = velocity.normalized()

            // Angle of Attack (AoA) in Radians: angle between fuselage forward and flight travel vector
            val cosAoA = orientation.forward.dot(travelDir).coerceIn(-1.0, 1.0)
            var aoaRad = acos(cosAoA)

            // Determine sign of AoA
            val signAoA = if (travelDir.dot(orientation.up) >= 0.0) -1.0 else 1.0
            aoaRad *= signAoA
            angleOfAttackAoA = aoaRad * (180.0 / Math.PI)

            // Wing surface area
            val wingSurfaceArea = 18.6 // sq meters

            // Lift: points perpendicular to travel path, towards cockpit roof (local +Y)
            val liftDirection = travelDir.cross(orientation.right).normalized()
            // Lift Coefficient lift curve slope for supersonic wings. Lift cl scales linearly up to stall.
            val cl = 2.2 * sin(aoaRad)
            val liftMagnitude = cl * dynamicPressureQ * wingSurfaceArea
            val forceLift = liftDirection * liftMagnitude
            totalForce += forceLift

            // Drag: opposes flight velocity vector
            var cd = 0.035 + (0.12 * cl * cl) // base + induced profile drag
            if (isGearDeployed) cd += 0.15     // skid and wheel strut drag
            if (isSpeedBrakeDeployed) cd += 0.22 // petal fuselage air brakes drag
            val dragMagnitude = cd * dynamicPressureQ * wingSurfaceArea
            val forceDrag = travelDir * (-dragMagnitude)
            totalForce += forceDrag

            // Record dynamic load G-forces
            val structuralForceNorm = forceLift.length() + forceThrustDamping(dt)
            gForce = (structuralForceNorm / (currentMass * 9.80665)).coerceAtLeast(0.0)
        } else {
            gForce = 1.0
            angleOfAttackAoA = 0.0
        }

        // Ground Interaction: Dry Lake Bed landing
        var forceGround = Vector3D()
        val runwayAlts = 0.0
        if (position.y <= runwayAlts) {
            position = Vector3D(position.x, runwayAlts, position.z)

            // If vertical sink rate is extreme, structural disintegration occurs
            // Real X-15 landing sink limits: ~7 m/s (1400 ft/m) was structural limit, normal is < 3 m/s
            if (velocity.y < -5.5) {
                phase = MissionPhase.CRASHED
                crashMessage = "CRASH IN LAKE BED: Hard impact structural collapse (${(abs(velocity.y * 196.85)).toInt()} ft/min sink rate)"
                return
            }

            // Normal glide or skid landing contact
            velocity = Vector3D(velocity.x, max(0.0, velocity.y), velocity.z)

            if (isGearDeployed) {
                // Skids friction (High friction on dry salt lake clay!)
                // Skid friction coefficients of steel rails on packed salt-clay is ~0.35-0.45
                val frictionCoefficient = if (isLowerFinJettisoned) 0.38 else 0.55 // lower fin strikes ground if NOT jettisoned, causing tail drag/yaw instability
                if (!isLowerFinJettisoned && velocity.length() > 20.0) {
                    phase = MissionPhase.CRASHED
                    crashMessage = "CRASH DETAILS: Lower fin not jettisoned. Struck ground at high speed, causing aircraft to cartwheel."
                    return
                }

                val groundSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
                if (groundSpeed > 0.5) {
                    val frictionMag = currentMass * 9.80665 * frictionCoefficient
                    val frictionDir = Vector3D(-velocity.x, 0.0, -velocity.z).normalized()
                    forceGround += frictionDir * frictionMag
                } else {
                    // Stopped completely! Safe termination
                    velocity = Vector3D()
                    phase = MissionPhase.LOCKED_LANDED
                }
            } else {
                // Fuselage belly landing (No landing gear deployed!)
                // Instant belly friction coefficient ~0.85
                val groundSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
                if (groundSpeed > 45.0) {
                    phase = MissionPhase.CRASHED
                    crashMessage = "CRASH: Belly landing at hypersonic speed (${(groundSpeed * 1.94).toInt()} kts) without skids deployed caused catastrophic fire."
                    return
                } else if (groundSpeed > 0.5) {
                    val frictionMag = currentMass * 9.81 * 0.82
                    val frictionDir = Vector3D(-velocity.x, 0.0, -velocity.z).normalized()
                    forceGround += frictionDir * frictionMag
                } else {
                    velocity = Vector3D()
                    phase = MissionPhase.LOCKED_LANDED
                }
            }
        }

        totalForce += forceGround

        // 6. ACCELERATION AND INTEGRATION (Euler-Cromer)
        val acceleration = totalForce / currentMass
        velocity += acceleration * dt
        position += velocity * dt

        // Extra safety boundary check
        if (position.y < runwayAlts) {
            position = Vector3D(position.x, runwayAlts, position.z)
        }

        // 7. DIAGNOSTICS CALCULATIONS
        updateCalculatedSpecs()
    }

    private fun forceThrustDamping(dt: Double): Double {
        return if (isRocketIgnited) 3000.0 else 0.0
    }

    private fun updateCalculatedSpecs() {
        altitudeFt = position.y * 3.28084

        val speedMs = velocity.length()
        airspeedKnots = speedMs * 1.94384

        // Speed of sound varies with air temperature / altitude
        // Linear approximation scaling down from 340m/s to 295m/s in cold stratosphere
        val tempGrad = (position.y / 11000.0).coerceIn(0.0, 1.0)
        val speedOfSound = 340.29 - (45.3 * tempGrad)
        machNumber = speedMs / speedOfSound

        rateOfClimbVsi = velocity.y * 196.8504 // meters/sec to ft/min

        if (altitudeFt > maxAltitudeReached) {
            maxAltitudeReached = altitudeFt
        }
        if (machNumber > maxMachReached) {
            maxMachReached = machNumber
        }

        // Outer Space high altitude black sky boundaries check
        if (altitudeFt > 264000.0) { // 50 miles - NASA Astronaut border
            isRcsActive = true // force rcs available
        }
    }
}
