// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Trait de stylo plume, comme signature.py sur PC : un bec biseauté tenu à 40°
 * (vers le haut à droite). Un trait dans le sens du bec est un délié, en travers
 * un plein ; le trait s'affine en début et en fin. Géométrie pure, testée sur PC.
 */
object Plume {
    const val ANGLE_BEC = 40.0
    const val AMINCI = 4

    /** Adoucit le tracé du doigt (coins coupés de Chaikin), extrémités conservées. */
    fun lisser(trait: List<FloatArray>, passes: Int = 2): List<FloatArray> {
        var t = trait
        repeat(passes) {
            if (t.size < 3) return t
            val lisse = mutableListOf(t[0])
            for (i in 0 until t.size - 1) {
                val (x0, y0) = t[i]
                val (x1, y1) = t[i + 1]
                lisse += floatArrayOf(0.75f * x0 + 0.25f * x1, 0.75f * y0 + 0.25f * y1)
                lisse += floatArrayOf(0.25f * x0 + 0.75f * x1, 0.25f * y0 + 0.75f * y1)
            }
            lisse += t.last()
            t = lisse
        }
        return t
    }

    /** Quadrilatères balayés par le bec le long du trait : [x0,y0, x1,y1, x2,y2, x3,y3]. */
    fun polygones(trait: List<FloatArray>, largeurBec: Float): List<FloatArray> {
        if (trait.isEmpty()) return emptyList()
        val angle = Math.toRadians(ANGLE_BEC)
        val bx = (cos(angle) * largeurBec / 2).toFloat()
        val by = (-sin(angle) * largeurBec / 2).toFloat()
        if (trait.size == 1) {  // un simple appui : une goutte d'encre
            val (x, y) = trait[0]
            return listOf(floatArrayOf(x + bx, y + by, x - by / 2, y + bx / 2, x - bx, y - by, x + by / 2, y - bx / 2))
        }
        val n = trait.size
        fun f(i: Int) = min(1f, 0.35f + 0.65f * min(i, n - 1 - i) / AMINCI)
        return (0 until n - 1).map { i ->
            val (x0, y0) = trait[i]
            val (x1, y1) = trait[i + 1]
            val a = f(i)
            val b = f(i + 1)
            floatArrayOf(x0 + bx * a, y0 + by * a, x1 + bx * b, y1 + by * b,
                x1 - bx * b, y1 - by * b, x0 - bx * a, y0 - by * a)
        }
    }

    /** Aire d'un quadrilatère (pour les tests). */
    fun aire(p: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return kotlin.math.abs(s) / 2
    }
}
