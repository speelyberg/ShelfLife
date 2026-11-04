package com.shelflife.tv

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Position, rotation (unit quaternion x,y,z,w) and uniform scale; eases cleanly between two poses. */
data class Pose(val x: Float, val y: Float, val z: Float, val q: FloatArray, val scale: Float = 1f) {
    fun matrix(): FloatArray {
        val (qx, qy, qz, qw) = q.toList()
        val s = scale
        // Column-major 4x4, as Filament expects.
        return floatArrayOf(
            s * (1 - 2 * (qy * qy + qz * qz)), s * (2 * (qx * qy + qz * qw)), s * (2 * (qx * qz - qy * qw)), 0f,
            s * (2 * (qx * qy - qz * qw)), s * (1 - 2 * (qx * qx + qz * qz)), s * (2 * (qy * qz + qx * qw)), 0f,
            s * (2 * (qx * qz + qy * qw)), s * (2 * (qy * qz - qx * qw)), s * (1 - 2 * (qx * qx + qy * qy)), 0f,
            x, y, z, 1f,
        )
    }

    fun mix(to: Pose, t: Float): Pose {
        fun m(a: Float, b: Float) = a + (b - a) * t
        return Pose(m(x, to.x), m(y, to.y), m(z, to.z), slerp(q, to.q, t), m(scale, to.scale))
    }

    companion object {
        fun yaw(angle: Float) = floatArrayOf(0f, sin(angle / 2), 0f, cos(angle / 2))
        fun pitch(angle: Float) = floatArrayOf(sin(angle / 2), 0f, 0f, cos(angle / 2))

        fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        )

        /** Rotation part of a column-major rigid matrix as a quaternion. */
        fun rotation(m: FloatArray): FloatArray {
            val trace = m[0] + m[5] + m[10]
            return if (trace > 0) {
                val s = sqrt(trace + 1f) * 2
                floatArrayOf((m[6] - m[9]) / s, (m[8] - m[2]) / s, (m[1] - m[4]) / s, 0.25f * s)
            } else if (m[0] > m[5] && m[0] > m[10]) {
                val s = sqrt(1f + m[0] - m[5] - m[10]) * 2
                floatArrayOf(0.25f * s, (m[4] + m[1]) / s, (m[8] + m[2]) / s, (m[6] - m[9]) / s)
            } else if (m[5] > m[10]) {
                val s = sqrt(1f + m[5] - m[0] - m[10]) * 2
                floatArrayOf((m[4] + m[1]) / s, 0.25f * s, (m[9] + m[6]) / s, (m[8] - m[2]) / s)
            } else {
                val s = sqrt(1f + m[10] - m[0] - m[5]) * 2
                floatArrayOf((m[8] + m[2]) / s, (m[9] + m[6]) / s, 0.25f * s, (m[1] - m[4]) / s)
            }
        }

        private fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
            var dot = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
            val b = if (dot < 0) { dot = -dot; FloatArray(4) { -b0[it] } } else b0
            if (dot > 0.9995f) {
                val r = FloatArray(4) { a[it] + (b[it] - a[it]) * t }
                val n = sqrt(r.sumOf { (it * it).toDouble() }).toFloat()
                return FloatArray(4) { r[it] / n }
            }
            val theta = acos(dot)
            val wa = sin((1 - t) * theta) / sin(theta)
            val wb = sin(t * theta) / sin(theta)
            return FloatArray(4) { a[it] * wa + b[it] * wb }
        }
    }
}
