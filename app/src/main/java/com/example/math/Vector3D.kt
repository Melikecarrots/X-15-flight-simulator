package com.example.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vector3D(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(other: Vector3D) = Vector3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3D) = Vector3D(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vector3D(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Double) = if (scalar != 0.0) Vector3D(x / scalar, y / scalar, z / scalar) else Vector3D()

    fun dot(other: Vector3D): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D = Vector3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun normalized(): Vector3D {
        val len = length()
        return if (len > 1e-7) this / len else Vector3D()
    }

    fun rotateX(angleRad: Double): Vector3D {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vector3D(x, y * c - z * s, y * s + z * c)
    }

    fun rotateY(angleRad: Double): Vector3D {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vector3D(x * c + z * s, y, -x * s + z * c)
    }

    fun rotateZ(angleRad: Double): Vector3D {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vector3D(x * c - y * s, x * s + y * c, z)
    }

    companion object {
        fun rotateAround(vector: Vector3D, axis: Vector3D, angleRad: Double): Vector3D {
            val c = cos(angleRad)
            val s = sin(angleRad)
            val cross = axis.cross(vector)
            val dot = axis.dot(vector)
            return vector * c + cross * s + axis * (dot * (1.0 - c))
        }
    }
}

class Orientation {
    var forward = Vector3D(0.0, 0.0, 1.0)
    var up = Vector3D(0.0, 1.0, 0.0)
    var right = Vector3D(1.0, 0.0, 0.0)

    fun reset() {
        forward = Vector3D(0.0, 0.0, 1.0)
        up = Vector3D(0.0, 1.0, 0.0)
        right = Vector3D(1.0, 0.0, 0.0)
    }

    fun rotateLocal(pitchRad: Double, rollRad: Double, yawRad: Double) {
        if (pitchRad != 0.0) {
            forward = Vector3D.rotateAround(forward, right, pitchRad).normalized()
            up = Vector3D.rotateAround(up, right, pitchRad).normalized()
        }
        if (rollRad != 0.0) {
            right = Vector3D.rotateAround(right, forward, rollRad).normalized()
            up = Vector3D.rotateAround(up, forward, rollRad).normalized()
        }
        if (yawRad != 0.0) {
            forward = Vector3D.rotateAround(forward, up, yawRad).normalized()
            right = Vector3D.rotateAround(right, up, yawRad).normalized()
        }

        // Renormalize and ensure orthogonality to prevent numerical drift
        val f = forward.normalized()
        val r = up.cross(f).normalized()
        val u = f.cross(r).normalized()

        forward = f
        right = r
        up = u
    }
}
