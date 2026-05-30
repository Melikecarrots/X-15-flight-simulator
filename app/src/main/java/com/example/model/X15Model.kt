package com.example.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.math.Vector3D
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Face3D(
    val vertexIndices: List<Int>,
    val baseColor: Color,
    val isDoubleSided: Boolean = false,
    val tag: String = "",
    val lightIntensity: Float = 1.0f
)

class X15Model {
    val vertices = mutableListOf<Vector3D>()
    val faces = mutableListOf<Face3D>()

    init {
        generateModel()
    }

    private fun generateModel() {
        vertices.clear()
        faces.clear()

        // 1. GENERATE FUSELAGE (Oval cross-section representing the X-15 side tunnels)
        val zSections = listOf(7.5, 6.0, 4.5, 3.0, 1.5, 0.0, -1.5, -3.0, -4.5, -6.0, -7.5)
        val sectionRadii = listOf(0.00, 0.30, 0.48, 0.58, 0.63, 0.65, 0.65, 0.65, 0.60, 0.50, 0.42)
        val sectionYOffset = listOf(0.00, 0.05, 0.07, 0.05, 0.02, 0.00, -0.01, -0.02, -0.03, -0.03, -0.02)

        val radialSegments = 8
        val sectionVertexOffsets = mutableListOf<Int>()

        // Generate fuselage vertices
        for (i in zSections.indices) {
            val z = zSections[i]
            val r = sectionRadii[i]
            val yOff = sectionYOffset[i]

            sectionVertexOffsets.add(vertices.size)

            if (r == 0.0) {
                // Nose tip (single vertex)
                vertices.add(Vector3D(0.0, yOff, z))
                // Add duplicates to align indices for circular algorithms
                for (s in 1 until radialSegments) {
                    vertices.add(Vector3D(0.0, yOff, z))
                }
            } else {
                for (s in 0 until radialSegments) {
                    val theta = s * (2.0 * Math.PI / radialSegments)
                    // Oval fairing elongation in X axis
                    val xStretch = if (cos(theta) > 0.3 || cos(theta) < -0.3) 1.25 else 1.0
                    val x = r * xStretch * cos(theta)
                    val y = r * sin(theta) + yOff
                    vertices.add(Vector3D(x, y, z))
                }
            }
        }

        // Add fuselage faces (Quads connecting circular sections)
        // Dark metallic Inconel X-15 alloy: almost black charcoal slate
        val fuselageColor = Color(22, 23, 24)

        for (i in 0 until zSections.size - 1) {
            val idxCurrent = sectionVertexOffsets[i]
            val idxNext = sectionVertexOffsets[i + 1]

            for (s in 0 until radialSegments) {
                val sNext = (s + 1) % radialSegments

                val p0 = idxCurrent + s
                val p1 = idxCurrent + sNext
                val p2 = idxNext + sNext
                val p3 = idxNext + s

                faces.add(
                    Face3D(
                        vertexIndices = listOf(p0, p1, p2, p3),
                        baseColor = fuselageColor,
                        tag = "fuselage"
                    )
                )
            }
        }

        // 2. COCKPIT CANOPY
        val canopyFrontIdx = vertices.size
        vertices.add(Vector3D(0.0, 0.82, 4.3)) // Canopy top nose
        val canopyRearIdx = vertices.size
        vertices.add(Vector3D(0.0, 0.76, 2.7)) // Canopy back window

        // Cockpit canopy faces (Glass screen blue-cyan, framing black)
        val glassColor = Color(48, 126, 178)
        val frameColor = Color(16, 17, 18)

        // Canopy triangles on top of Section 2 (around z=4.5) and Section 3 (around z=3.0)
        // Let's connect canopyFront and canopyRear to fuselage section vertices
        val fusSec2Base = sectionVertexOffsets[2] // Z = 4.5
        val fusSec3Base = sectionVertexOffsets[3] // Z = 3.0
        val fusSec4Base = sectionVertexOffsets[4] // Z = 1.5

        // Front glass window
        faces.add(Face3D(listOf(fusSec2Base + 0, canopyFrontIdx, fusSec2Base + 1), glassColor, true, "canopy"))
        faces.add(Face3D(listOf(fusSec2Base + 0, fusSec2Base + radialSegments - 1, canopyFrontIdx), glassColor, true, "canopy"))

        // Side windows
        faces.add(Face3D(listOf(fusSec2Base + 1, fusSec3Base + 1, canopyRearIdx, canopyFrontIdx), glassColor, true, "canopy"))
        faces.add(Face3D(listOf(fusSec2Base + radialSegments - 1, canopyFrontIdx, canopyRearIdx, fusSec3Base + radialSegments - 1), glassColor, true, "canopy"))

        // Canopy roof frame
        faces.add(Face3D(listOf(canopyFrontIdx, canopyRearIdx, fusSec3Base + 0), frameColor, true, "canopy"))
        // Canopy rear slope
        faces.add(Face3D(listOf(canopyRearIdx, fusSec3Base + 1, fusSec4Base + 1, fusSec4Base + 0), frameColor, true, "canopy"))
        faces.add(Face3D(listOf(canopyRearIdx, fusSec4Base + 0, fusSec4Base + radialSegments - 1, fusSec3Base + radialSegments - 1), frameColor, true, "canopy"))


        // 3. WINGS (Trapezoid stubby wings, thin, slightly dipped)
        // Root chord extends along side tunnels from z = 1.0 to z = -2.5
        val wingYOffset = -0.05
        val wingColor = Color(26, 27, 28)

        // LEFT WING VERTICES
        val lw0 = vertices.size // Root Front
        vertices.add(Vector3D(0.65, wingYOffset + 0.05, 1.0))
        val lw1 = vertices.size // Root Rear
        vertices.add(Vector3D(0.65, wingYOffset - 0.05, -2.5))
        val lw2 = vertices.size // Tip Front
        vertices.add(Vector3D(3.00, wingYOffset - 0.12, -1.8))
        val lw3 = vertices.size // Tip Rear
        vertices.add(Vector3D(2.70, wingYOffset - 0.16, -2.7))

        // Left wing Top & Bottom faces
        faces.add(Face3D(listOf(lw0, lw2, lw3, lw1), wingColor, true, "wings")) // Top
        // Left wing edges (front and tip)
        faces.add(Face3D(listOf(lw0, lw1, lw3, lw2), Color(20, 20, 20), true, "wings"))

        // NASA / USAF decals on Left Wing
        val nasayellow = Color(230, 180, 0)
        val usafred = Color(200, 30, 30)

        // RIGHT WING VERTICES (Symmetric on negative X)
        val rw0 = vertices.size // Root Front
        vertices.add(Vector3D(-0.65, wingYOffset + 0.05, 1.0))
        val rw1 = vertices.size // Root Rear
        vertices.add(Vector3D(-0.65, wingYOffset - 0.05, -2.5))
        val rw2 = vertices.size // Tip Front
        vertices.add(Vector3D(-3.00, wingYOffset - 0.12, -1.8))
        val rw3 = vertices.size // Tip Rear
        vertices.add(Vector3D(-2.70, wingYOffset - 0.16, -2.7))

        // Right wing Top & Bottom
        faces.add(Face3D(listOf(rw0, rw1, rw3, rw2), wingColor, true, "wings")) // Top
        faces.add(Face3D(listOf(rw0, rw2, rw3, rw1), Color(20, 20, 20), true, "wings"))


        // 4. UPPER FIN (Wedge-shaped thick vertical stabilizer)
        val ufLeftRootFront = vertices.size
        vertices.add(Vector3D(-0.03, 0.60, -3.2))
        val ufLeftRootRear = vertices.size
        vertices.add(Vector3D(-0.15, 0.45, -7.0)) // Thick trailing edge
        val ufLeftTipFront = vertices.size
        vertices.add(Vector3D(-0.02, 2.40, -5.5))
        val ufLeftTipRear = vertices.size
        vertices.add(Vector3D(-0.12, 2.30, -7.0))

        val ufRightRootFront = vertices.size
        vertices.add(Vector3D(0.03, 0.60, -3.2))
        val ufRightRootRear = vertices.size
        vertices.add(Vector3D(0.15, 0.45, -7.0))
        val ufRightTipFront = vertices.size
        vertices.add(Vector3D(0.02, 2.40, -5.5))
        val ufRightTipRear = vertices.size
        vertices.add(Vector3D(0.12, 2.30, -7.0))

        // Upper Fin faces
        // Left flank
        faces.add(Face3D(listOf(ufLeftRootFront, ufLeftTipFront, ufLeftTipRear, ufLeftRootRear), Color(24, 25, 26), true, "upper_fin"))
        // Right flank
        faces.add(Face3D(listOf(ufRightRootFront, ufRightRootRear, ufRightTipRear, ufRightTipFront), Color(24, 25, 26), true, "upper_fin"))
        // Front leading edge cone
        faces.add(Face3D(listOf(ufLeftRootFront, ufRightRootFront, ufRightTipFront, ufLeftTipFront), Color(15, 15, 16), true, "upper_fin"))
        // Rear trailing edge wedge face (flat silver-gray exhaust / structural channel)
        faces.add(Face3D(listOf(ufLeftRootRear, ufLeftTipRear, ufRightTipRear, ufRightRootRear), Color(60, 62, 65), true, "upper_fin_rear"))
        // NASA logo stripe sub-polygon decoration (yellow stripe on left and right)
        // Rendered as overlapping layers, let's just make the fin tips yellow for a spectacular NASA theme!
        faces.add(Face3D(listOf(ufLeftTipFront, ufLeftTipRear, ufRightTipRear, ufRightTipFront), nasayellow, true, "upper_fin_tip"))


        // 5. LOWER JETTISONABLE FIN (Symmetric on Y, goes down)
        val lfLeftRootFront = vertices.size
        vertices.add(Vector3D(-0.03, -0.60, -3.5))
        val lfLeftRootRear = vertices.size
        vertices.add(Vector3D(-0.15, -0.45, -6.8))
        val lfLeftTipFront = vertices.size
        vertices.add(Vector3D(-0.02, -2.10, -5.5))
        val lfLeftTipRear = vertices.size
        vertices.add(Vector3D(-0.12, -2.00, -6.8))

        val lfRightRootFront = vertices.size
        vertices.add(Vector3D(0.03, -0.60, -3.5))
        val lfRightRootRear = vertices.size
        vertices.add(Vector3D(0.15, -0.45, -6.8))
        val lfRightTipFront = vertices.size
        vertices.add(Vector3D(0.02, -2.10, -5.5))
        val lfRightTipRear = vertices.size
        vertices.add(Vector3D(0.12, -2.00, -6.8))

        // Lower Fin faces (Check tag "lower_fin" to skip rendering when jettisoned)
        faces.add(Face3D(listOf(lfLeftRootFront, lfLeftTipFront, lfLeftTipRear, lfLeftRootRear), Color(24, 25, 26), true, "lower_fin"))
        faces.add(Face3D(listOf(lfRightRootFront, lfRightRootRear, lfRightTipRear, lfRightTipFront), Color(24, 25, 26), true, "lower_fin"))
        faces.add(Face3D(listOf(lfLeftRootFront, lfRightRootFront, lfRightTipFront, lfLeftTipFront), Color(15, 15, 16), true, "lower_fin"))
        faces.add(Face3D(listOf(lfLeftRootRear, lfLeftTipRear, lfRightTipRear, lfRightRootRear), Color(60, 62, 65), true, "lower_fin"))


        // 6. ROCKET ENGINE NOZZLE (XLR99 Dark bronze cone)
        val nozzleRootBase = sectionVertexOffsets[10] // Z = -7.5
        val nozzleTipBase = vertices.size // We will make a simple ring at Z = -8.2
        val nozzleR = 0.35
        for (s in 0 until radialSegments) {
            val theta = s * (2.0 * Math.PI / radialSegments)
            val x = nozzleR * cos(theta)
            val y = nozzleR * sin(theta) - 0.02 // slight alignment with section offset
            vertices.add(Vector3D(x, y, -8.3))
        }

        // Add nozzle faces
        val nozzleColor = Color(90, 75, 65) // Dark bronze-copper
        for (s in 0 until radialSegments) {
            val sNext = (s + 1) % radialSegments
            faces.add(
                Face3D(
                    vertexIndices = listOf(nozzleRootBase + s, nozzleRootBase + sNext, nozzleTipBase + sNext, nozzleTipBase + s),
                    baseColor = nozzleColor,
                    tag = "nozzle"
                )
            )

            // Inner exhaust face (glowing safety orange)
            faces.add(
                Face3D(
                    vertexIndices = listOf(nozzleTipBase + s, nozzleTipBase + sNext, nozzleRootBase + sNext),
                    baseColor = Color(211, 84, 0),
                    tag = "nozzle_inner"
                )
            )
        }
    }

    // PROJECT AND RENDER ENGINE (PAINTER'S ALGORITHM)
    fun render(
        drawScope: DrawScope,
        width: Float,
        height: Float,
        cameraPos: Vector3D, // position in local craft coordinates
        cameraPitchRad: Double, // camera pitch up/down tilt (to look down at target)
        cameraYawRad: Double, // camera yaw relative to plane
        cameraRollRad: Double, // camera roll
        isLowerFinJettisoned: Boolean,
        isGearDeployed: Boolean,
        thrustFlameLevel: Float, // 0.0 to 1.0
        timeMs: Long
    ) {
        val lightDir = Vector3D(0.4, 0.9, -0.3).normalized()

        fun transformCam(vRel: Vector3D): Vector3D {
            var pCam = vRel
            if (cameraYawRad != 0.0) pCam = pCam.rotateY(cameraYawRad)
            if (cameraPitchRad != 0.0) pCam = pCam.rotateX(cameraPitchRad)
            if (cameraRollRad != 0.0) pCam = pCam.rotateZ(cameraRollRad)
            return pCam
        }

        // 1. PROJECT VERTICES TO CAMERA SPACE
        // Camera lies at cameraPos in the aircraft's coordinate system.
        // In aircraft system, forward is +Z, up is +Y, right is +X.
        // Translate relative to camera: pCam = V - cameraPos
        // Then we rotate camera frame to account for looking angle.
        // The camera target is the center of the aircraft (0.0, 0.0, 1.0)
        // Let's rotate standard around X-axis (camera tilt) and Y-axis (camera orbit)
        val projectedPoints = Array<android.graphics.PointF?>(vertices.size) { null }
        val cameraSpaceZ = DoubleArray(vertices.size)

        for (i in vertices.indices) {
            val v = vertices[i]
            // Translate by camera position
            var pCam = v - cameraPos

            // Rotate around Y by cameraYawRad (for orbiting the plane)
            if (cameraYawRad != 0.0) {
                pCam = pCam.rotateY(cameraYawRad)
            }
            // Rotate around X by cameraPitchRad
            if (cameraPitchRad != 0.0) {
                pCam = pCam.rotateX(cameraPitchRad)
            }
            // Rotate around Z by cameraRollRad (keeps the horizon tilted in cockpit view etc.)
            if (cameraRollRad != 0.0) {
                pCam = pCam.rotateZ(cameraRollRad)
            }

            cameraSpaceZ[i] = pCam.z

            // Perspective Projection
            if (pCam.z > 0.15) {
                val fov = width * 0.75 // standard focal depth matching viewport
                val px = width / 2f + (pCam.x * fov / pCam.z).toFloat()
                val py = height / 2f - (pCam.y * fov / pCam.z).toFloat()
                projectedPoints[i] = android.graphics.PointF(px, py)
            }
        }

        // 2. FILTER AND MEASURE DEPTH FOR FACES (Z-sorting)
        class ActiveFace(
            val face: Face3D,
            val avgZ: Double,
            val projectedPointsList: List<android.graphics.PointF>,
            val shadedColor: Color
        )

        val activeFaces = mutableListOf<ActiveFace>()

        for (f in faces) {
            // Check lower fin jettison trigger
            if (isLowerFinJettisoned && f.tag == "lower_fin") continue

            // Determine if we can project all vertices of this face
            val pts = mutableListOf<android.graphics.PointF>()
            var sumZ = 0.0
            var valid = true

            // Gather local vertices to compute 3D normal
            val localVerts = f.vertexIndices.map { vertices[it] }
            if (localVerts.size < 3) continue

            // 3D face normal for flat shading (prior to rotation / world translation)
            val edge1 = localVerts[1] - localVerts[0]
            val edge2 = localVerts[2] - localVerts[0]
            val normal = edge1.cross(edge2).normalized()

            // Backface culling: If camera is in front of the side, and it is single sided...
            // Let's compute dot product of normal with camera direction
            val lookDir = (localVerts[0] - cameraPos).normalized()
            val angleCos = normal.dot(lookDir)
            if (!f.isDoubleSided && angleCos > 0.0) {
                // Pointing away from camera, cull
                continue
            }

            for (idx in f.vertexIndices) {
                val pt = projectedPoints[idx]
                if (pt == null) {
                    valid = false
                    break
                }
                pts.add(pt)
                sumZ += cameraSpaceZ[idx]
            }

            if (!valid) continue

            val avgZ = sumZ / f.vertexIndices.size

            // Compute lighting flat shading factor
            // Diffuse diffuse intensity based on dot product of normal with sunlight direction
            val diffuse = normal.dot(lightDir)
            val lightingFactor = 0.35f + 0.65f * Math.max(0.0f, diffuse.toFloat())

            // Apply base color shading
            val r = (f.baseColor.red * lightingFactor).coerceIn(0f, 1f)
            val g = (f.baseColor.green * lightingFactor).coerceIn(0f, 1f)
            val b = (f.baseColor.blue * lightingFactor).coerceIn(0f, 1f)
            val shaded = Color(r, g, b, f.baseColor.alpha)

            activeFaces.add(ActiveFace(f, avgZ, pts, shaded))
        }

        // Sort faces by depth: largest depth (furthest away) is drawn first
        val sortedFaces = activeFaces.sortedByDescending { it.avgZ }

        // Draw the faces on the canvas
        for (af in sortedFaces) {
            val path = Path()
            val pts = af.projectedPointsList
            path.moveTo(pts[0].x, pts[0].y)
            for (pIdx in 1 until pts.size) {
                path.lineTo(pts[pIdx].x, pts[pIdx].y)
            }
            path.close()

            drawScope.drawPath(
                path = path,
                color = af.shadedColor,
                style = Fill
            )

            // Dynamic panel grid lines overlay for detail
            val outlineColor = Color(0, 0, 0, 45)
            drawScope.drawPath(
                path = path,
                color = outlineColor,
                style = Stroke(width = 1f)
            )
        }

        // 7. RENDER CUSTOM EXPANDED EXHAUST PLUME (Special translucent glowing layers)
        if (thrustFlameLevel > 0.05f) {
            // Let's project a set of glowing points representing nozzle exhaust expansion
            // Center is at (0.0, -0.02, -8.3) locally. Extends to Z = -8.3 - 5.0 * thrust
            val baseNozzleLocal = Vector3D(0.0, -0.02, -8.3)
            val flameLength = (5.0f + 2f * sin(timeMs * 0.05f).toFloat()) * thrustFlameLevel
            val endFlameLocal = Vector3D(0.0, -0.05, (-8.3 - flameLength).toDouble())

            // Projection points
            val p0_rel = baseNozzleLocal - cameraPos
            val p1_rel = endFlameLocal - cameraPos

            val pCamStart = transformCam(p0_rel)
            val pCamEnd = transformCam(p1_rel)

            if (pCamStart.z > 0.1 && pCamEnd.z > 0.1) {
                val fov = width * 0.75
                val startX = (width / 2f + (pCamStart.x * fov / pCamStart.z).toFloat())
                val startY = (height / 2f - (pCamStart.y * fov / pCamStart.z).toFloat())

                val endX = (width / 2f + (pCamEnd.x * fov / pCamEnd.z).toFloat())
                val endY = (height / 2f - (pCamEnd.y * fov / pCamEnd.z).toFloat())

                // Draw exhaust plume cones
                val plumeWidthStart = (0.35 * fov / pCamStart.z).toFloat()
                val plumeWidthEnd = (0.75 * fov / pCamEnd.z).toFloat()

                val pathFlame = Path()
                // Wedge polygon
                pathFlame.moveTo(startX - plumeWidthStart, startY)
                pathFlame.lineTo(startX + plumeWidthStart, startY)
                pathFlame.lineTo(endX + plumeWidthEnd, endY)
                pathFlame.lineTo(endX - plumeWidthEnd, endY)
                pathFlame.close()

                // Layer 1: Core blue shock-cone
                drawScope.drawPath(
                    path = pathFlame,
                    color = Color(41, 128, 185, 120),
                    style = Fill
                )

                // Layer 2: Main fiery expansion orange center
                val innerFlamePath = Path()
                innerFlamePath.moveTo(startX - plumeWidthStart * 0.6f, startY)
                innerFlamePath.lineTo(startX + plumeWidthStart * 0.6f, startY)
                innerFlamePath.lineTo(endX + plumeWidthEnd * 0.3f, endY)
                innerFlamePath.lineTo(endX - plumeWidthEnd * 0.3f, endY)
                innerFlamePath.close()

                drawScope.drawPath(
                    path = innerFlamePath,
                    color = Color(243, 156, 18, 200),
                    style = Fill
                )

                // Layer 3: Needle white-hot core
                val coreFlamePath = Path()
                coreFlamePath.moveTo(startX - plumeWidthStart * 0.3f, startY)
                coreFlamePath.lineTo(startX + plumeWidthStart * 0.3f, startY)
                coreFlamePath.lineTo(endX, endY)
                coreFlamePath.close()

                drawScope.drawPath(
                    path = coreFlamePath,
                    color = Color(255, 255, 255, 230),
                    style = Fill
                )
            }
        }

        // 8. RENDER NOSE GEAR AND SKIDS (Drawn if deployed as wireframes with thickness)
        if (isGearDeployed) {
            val skidVertices = listOf(
                // Left rear skid: pivot from (0.5, -0.4, -4.5) to contact (1.3, -1.5, -5.5)
                Pair(Vector3D(0.5, -0.3, -4.5), Vector3D(1.4, -1.35, -5.5)),
                // Right rear skid: pivot from (-0.5, -0.4, -4.5) to contact (-1.3, -1.5, -5.55)
                Pair(Vector3D(-0.5, -0.3, -4.5), Vector3D(-1.4, -1.35, -5.5)),
                // Nose twin gear: pivot from (0.0, -0.5, 5.2) to contact (0.0, -1.35, 5.5)
                Pair(Vector3D(0.0, -0.5, 5.2), Vector3D(0.0, -1.35, 5.5))
            )

            for (skid in skidVertices) {
                val pStartCam = transformCam(skid.first - cameraPos)
                val pEndCam = transformCam(skid.second - cameraPos)

                if (pStartCam.z > 0.1 && pEndCam.z > 0.1) {
                    val fov = width * 0.75
                    val sx = (width / 2f + (pStartCam.x * fov / pStartCam.z).toFloat())
                    val sy = (height / 2f - (pStartCam.y * fov / pStartCam.z).toFloat())
                    val ex = (width / 2f + (pEndCam.x * fov / pEndCam.z).toFloat())
                    val ey = (height / 2f - (pEndCam.y * fov / pEndCam.z).toFloat())

                    // Strut core
                    drawScope.drawLine(
                        color = Color(160, 160, 160),
                        start = androidx.compose.ui.geometry.Offset(sx, sy),
                        end = androidx.compose.ui.geometry.Offset(ex, ey),
                        strokeWidth = 8f
                    )
                    // Contact skid shoe or nose wheels
                    drawScope.drawCircle(
                        color = Color(40, 40, 40),
                        center = androidx.compose.ui.geometry.Offset(ex, ey),
                        radius = 12f
                    )
                }
            }
        }
    }
}
