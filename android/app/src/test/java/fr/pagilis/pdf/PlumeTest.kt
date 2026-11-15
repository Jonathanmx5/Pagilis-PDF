package fr.pagilis.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlumeTest {
    private fun surface(trait: List<FloatArray>) = Plume.polygones(trait, 7f).sumOf { Plume.aire(it).toDouble() }

    @Test
    fun pleinsEtDelies() {
        // Vers le haut à droite (sens du bec) : délié ; vers le bas à droite : plein.
        val delie = surface((0..20).map { floatArrayOf(it * 5f, 100f - it * 5f) })
        val plein = surface((0..20).map { floatArrayOf(it * 5f, it * 5f) })
        assertTrue("plein $plein, délié $delie", plein > 4 * delie)
    }

    @Test
    fun unAppuiFaitUneGoutte() {
        assertEquals(1, Plume.polygones(listOf(floatArrayOf(10f, 10f)), 7f).size)
        assertTrue(Plume.polygones(emptyList(), 7f).isEmpty())
    }

    @Test
    fun lissageGardeLesExtremites() {
        val t = listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 20f), floatArrayOf(30f, 0f))
        val l = Plume.lisser(t)
        assertTrue(l.size > t.size)
        assertEquals(0f, l.first()[0]); assertEquals(30f, l.last()[0])
    }
}
