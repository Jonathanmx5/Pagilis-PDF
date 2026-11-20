package fr.pagilis.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecoursTest {
    @get:Rule val dossier = TemporaryFolder()

    @Test
    fun gardeLesPlusRecentesSeulement() {
        val d = dossier.root
        for (k in 1..12) Secours.garder(d, "devis.pdf", byteArrayOf(k.toByte()), max = 10, quand = 1_000L * k)
        val l = Secours.liste(d)
        assertEquals(10, l.size)
        assertEquals("la plus récente d'abord", 12, l.first().fichier.readBytes()[0].toInt())
        assertEquals(3, l.last().fichier.readBytes()[0].toInt())
        assertTrue(l.all { it.nom == "devis.pdf" })
    }

    @Test
    fun nomAvecCaracteresGenantsGarde() {
        val d = dossier.root
        val s = Secours.garder(d, "Contrat: v2/final?.pdf", byteArrayOf(1), quand = 5_000L)
        assertTrue(s.fichier.isFile)
        assertEquals("Contrat: v2/final?.pdf", Secours.liste(d).single().nom)
        assertEquals(5_000L, Secours.liste(d).single().quand)
    }
}
