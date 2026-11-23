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

    @Test
    fun pressionDuStyletAffineOuEpaissit() {
        fun trait(p: Float) = (0..20).map { floatArrayOf(it * 5f, it * 5f, p) }
        val leger = surface(trait(0.5f))
        val appuye = surface(trait(1.2f))
        assertTrue("léger $leger, appuyé $appuye", appuye > 2 * leger)
        // Le lissage garde la pression avec chaque point.
        val l = Plume.lisser(listOf(floatArrayOf(0f, 0f, 0.5f), floatArrayOf(10f, 20f, 1f), floatArrayOf(30f, 0f, 0.5f)))
        assertTrue(l.all { it.size == 3 && it[2] in 0.5f..1f })
    }
}

class NomsSignaturesTest {
    @get:org.junit.Rule val dossier = org.junit.rules.TemporaryFolder()

    @Test
    fun nomGardeACoteDeLImage() {
        val f = dossier.newFile("sig_1.png")
        assertEquals("Signature", Signature.nom(f))
        Signature.nommer(f, "  Initiales ")
        assertEquals("Initiales", Signature.nom(f))
        Signature.nommer(f, "   ")
        assertEquals("Signature", Signature.nom(f))
        Signature.nommer(f, "Paraphe")
        Signature.supprimer(f)
        assertTrue(dossier.root.listFiles()!!.isEmpty())
    }

    @Test
    fun nomProposeSelonCeQuiExiste() {
        assertEquals("Signature", Signature.nomPropose(emptyList()))
        val a = dossier.newFile("sig_1.png").also { Signature.nommer(it, "Signature") }
        assertEquals("Initiales", Signature.nomPropose(listOf(a)))
        val b = dossier.newFile("sig_2.png").also { Signature.nommer(it, "Initiales") }
        val c = dossier.newFile("sig_3.png").also { Signature.nommer(it, "Paraphe") }
        assertEquals("Signature 2", Signature.nomPropose(listOf(a, b, c)))
    }
}
