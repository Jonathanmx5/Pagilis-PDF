package fr.pagilis.pdf

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Annulation sur disque, copie verrouillée (0.4.0). */
@RunWith(AndroidJUnit4::class)
class SuretesTest {
    private val temp: File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "suretes").apply { deleteRecursively(); mkdirs() }
    private fun asset(n: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(n).readBytes()
    private fun ramasser() = repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(50) }
    private fun instantanes() = temp.listFiles { f -> f.name.startsWith("annul_") }.orEmpty()

    @Test
    fun annulationGardeeSurDisqueEtNettoyee() {
        val c = CopiePdf(asset("devis.pdf"), temp, maxAnnulations = 5)
        repeat(8) { k -> assertTrue(c.ajouterTexte(0, 60f, 100f + 20 * k, "Ligne $k", 12f, floatArrayOf(0f, 0f, 0f))); ramasser() }
        assertEquals("un instantané par annulation possible, pas plus", 5, instantanes().size)
        repeat(5) { assertTrue(c.annuler()) }
        assertFalse(c.annuler())
        assertEquals("annuler libère l'instantané", 0, instantanes().size)
        assertTrue(texteDePage(c.doc, 0).contains("Ligne 2"))
        assertFalse(texteDePage(c.doc, 0).contains("Ligne 3"))

        c.ajouterTexte(0, 60f, 400f, "Encore", 12f, floatArrayOf(0f, 0f, 0f)); ramasser()
        assertEquals(1, instantanes().size)
        c.fermer()
        assertEquals("fermer efface tout", 0, instantanes().size)
    }

    @Test
    fun copieVerrouilleeLisibleMaisPasModifiable() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        c.encadrer(0, Rect(60f, 640f, 300f, 700f), floatArrayOf(1f, 0f, 0f)); ramasser()
        c.creerZoneSignature(0, Rect(320f, 700f, 520f, 760f), "Client"); ramasser()
        assertEquals(1, c.casesVides())

        val v = c.verrouiller()
        val d = PDFDocument.openDocument(v, "application/pdf") as PDFDocument
        assertFalse("s'ouvre sans mot de passe", d.needsPassword())
        assertTrue(d.hasPermission(PDFDocument.PERMISSION_PRINT))
        assertTrue(d.hasPermission(PDFDocument.PERMISSION_COPY))
        assertFalse(d.hasPermission(PDFDocument.PERMISSION_EDIT))
        assertFalse(d.hasPermission(PDFDocument.PERMISSION_ANNOTATE))
        assertFalse(d.hasPermission(PDFDocument.PERMISSION_FORM))
        val page = d.loadPage(0) as PDFPage
        assertTrue("annotations intégrées à la page", (page.annotations ?: emptyArray()).isEmpty())
        assertTrue("plus de cases", (page.widgets ?: emptyArray()).isEmpty())
        // Le cadre rouge est toujours visible.
        val bmp = AndroidDrawDevice.drawPage(page, Matrix(1f))
        assertTrue(Color.red(bmp.getPixel(180, 640)) > 200 && Color.green(bmp.getPixel(180, 640)) < 80)
        assertEquals(6, d.countPages())
        // La copie de travail n'est pas touchée.
        assertEquals(1, c.casesVides())
    }
}
