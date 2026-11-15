package fr.pagilis.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Calculs sans MuPDF : ils tournent sur le PC, sans appareil. */
class PlacementTest {
    @Test
    fun placementDeplaceLaSelection() {
        assertEquals(listOf(0, 4, 1, 2, 3, 5), CopiePdf.ordreApresPlacement(6, listOf(4), 1))
        assertEquals(listOf(1, 3, 4, 5, 0, 2), CopiePdf.ordreApresPlacement(6, listOf(0, 2), 6))
    }

    @Test
    fun placementSurPlaceNeChangeRien() {
        assertNull(CopiePdf.ordreApresPlacement(4, listOf(1, 2), 2))
        assertNull(CopiePdf.ordreApresPlacement(4, listOf(1, 2), 3))
        assertNull(CopiePdf.ordreApresPlacement(4, emptyList(), 0))
    }

    @Test
    fun plagesLisibles() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(7, 8, 9)), Assemblage.plages("1-3, 5, 8-", 10))
        assertEquals(listOf(listOf(0, 1)), Assemblage.plages("-2", 10))
    }

    @Test(expected = ErreurPdf::class)
    fun plageHorsDocument() {
        Assemblage.plages("3-12", 10)
    }
}
