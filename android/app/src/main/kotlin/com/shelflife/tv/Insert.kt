package com.shelflife.tv

/**
 * The disc coming out of the case in your hands, as a function of time: the lid swings open, the disc
 * lifts out and tips one way, then slowly the other, catching the light, then flies off to the right
 * as the lid closes.
 *
 * The baked clip (InsertClip in RoomRenderer.kt) is this, rendered once on the TV box; the live label
 * and covers are placed from the same function at the shown frame's own time, so they stay put on it.
 * Change anything here and bake the clip again (see work/make_insert_clip.py).
 */
object InsertTimeline {
    const val SECONDS = 2.85f
    const val FPS = 30
    val FRAMES = (SECONDS * FPS).toInt() + 1
    private const val OPEN = -2.5f  // the lid swung toward you, about 145°
    private fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    fun lid(t: Float) = if (t < 2.4f) OPEN * ease(t / 0.75f) else OPEN * (1 - ease((t - 2.4f) / 0.4f))

    fun disc(t: Float, shelf: Shelf): Pose {
        val inCase = shelf.discInCase()
        // Lifted out and tipped one way, then slowly the other; then off to the right.
        val tipOne = shelf.inFrontOfCamera(-0.01f, 0.006f, -0.24f, 1f, Pose.multiply(Pose.yaw(0.45f), Pose.pitch(-0.3f)))
        val tipOther = shelf.inFrontOfCamera(0.012f, 0.006f, -0.235f, 1f, Pose.multiply(Pose.yaw(-0.42f), Pose.pitch(0.22f)))
        val away = shelf.inFrontOfCamera(0.62f, 0.07f, -0.33f, 1f, Pose.yaw(-0.9f))
        return when {
            t < 0.85f -> inCase
            t < 1.45f -> inCase.mix(tipOne, ease((t - 0.85f) / 0.6f))
            t < 2.4f -> tipOne.mix(tipOther, ease((t - 1.45f) / 0.95f))
            else -> tipOther.mix(away, ease((t - 2.4f) / 0.45f))
        }
    }
}
