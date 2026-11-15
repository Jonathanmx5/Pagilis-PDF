package fr.pagilis.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CopiePdfTest {
    private val temp: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** PDF de n pages A4, chacune portant « pageN » ; rotation donnée pour la page 0. */
    private fun pdf(n: Int, rotation: Int = 0): ByteArray {
        val d = PDFDocument()
        val police = d.addSimpleFont(com.artifex.mupdf.fitz.Font("Helvetica"), 0)
        for (i in 0 until n) {
            val res = d.newDictionary()
            val polices = d.newDictionary()
            polices.put("F1", police)
            res.put("Font", polices)
            val page = d.addPage(Rect(0f, 0f, 595f, 842f), if (i == 0) rotation else 0, res,
                "BT /F1 12 Tf 50 790 Td (page$i) Tj ET")
            d.insertPage(-1, page)
        }
        return Assemblage.enOctets(d)
    }

    /** Le ramasse-miettes passe : il ne doit rien casser dans le document. */
    private fun ramasser() = repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(50) }

    private fun ordre(c: CopiePdf) = (0 until c.nombrePages).map { texteDePage(c.doc, it).removePrefix("page").toInt() }

    @Test
    fun retouchesAnnulablesEtEtatModifie() {
        val c = CopiePdf(pdf(4), temp)
        assertFalse(c.modifie)
        assertEquals(listOf(1), c.placer(listOf(3), 1))
        assertEquals(listOf(0, 3, 1, 2), ordre(c))
        assertTrue(c.modifie)
        c.annuler()
        assertEquals(listOf(0, 1, 2, 3), ordre(c))
        assertFalse(c.modifie)
    }

    @Test
    fun pivoterSupprimerExtraire() {
        val c = CopiePdf(pdf(3), temp)
        c.pivoter(listOf(1), 90)
        assertEquals(90, c.doc.findPage(1).get("Rotate").asInteger())
        c.supprimer(listOf(0))
        assertEquals(listOf(1, 2), ordre(c))
        val extrait = CopiePdf(c.extraire(listOf(1)), temp)
        assertEquals(listOf(2), ordre(extrait))
    }

    @Test(expected = ErreurPdf::class)
    fun onNeSupprimePasToutesLesPages() {
        CopiePdf(pdf(2), temp).supprimer(listOf(0, 1))
    }

    @Test
    fun texteAjouteAvecAccentsAuPointClique() {
        val c = CopiePdf(pdf(1), temp)
        assertTrue(c.ajouterTexte(0, 100f, 400f, "Lu et approuvé, 12 € — ok", 14f, floatArrayOf(0f, 0f, 0.6f)))
        val relu = CopiePdf(c.enregistrer(), temp)
        assertTrue(texteDePage(relu.doc, 0).contains("Lu et approuvé, 12 € — ok"))
        val zone = relu.doc.loadPage(0).search("approuvé")[0][0].toRect()
        assertTrue("à droite du clic", zone.x0 > 100f && zone.x0 < 160f)
        assertTrue("à hauteur du clic", zone.y0 < 400f && zone.y1 > 400f)
        assertFalse(c.ajouterTexte(0, 10f, 10f, "   ", 12f, floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun texteDroitSurPageTournee() {
        val c = CopiePdf(pdf(1, rotation = 90), temp)
        c.ajouterTexte(0, 150f, 120f, "Bon pour accord", 12f, floatArrayOf(0f, 0f, 0f))
        val zone = c.doc.loadPage(0).search("Bon pour accord")[0][0].toRect()
        assertTrue("droit à l'affichage", zone.x1 - zone.x0 > zone.y1 - zone.y0)
        assertTrue(zone.x0 > 148f && zone.x0 < 155f && zone.y0 < 120f && zone.y1 > 120f)
    }

    @Test
    fun imagePoseeDansLeRectangleAffiche() {
        val png = File(temp, "signature.png")
        val bmp = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 0 until 200) for (y in 30 until 50) bmp.setPixel(x, y, Color.BLUE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (rotation in listOf(0, 90)) {
            val c = CopiePdf(pdf(1, rotation), temp)
            c.poserImage(0, Rect(100f, 200f, 300f, 280f), png.path)
            val rendu = AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
            // Bande bleue au milieu du rectangle (y 230 à 250), blanc autour.
            assertEquals("rotation $rotation", Color.BLUE, rendu.getPixel(200, 240) or 0xFF000000.toInt())
            assertEquals(Color.WHITE, rendu.getPixel(200, 210))
            assertEquals(Color.WHITE, rendu.getPixel(90, 240))
        }
    }

    @Test
    fun ajoutsSuccessifsSeCumulent() {
        val png = File(temp, "bande.png")
        // Comme une vraie signature : grande, fond transparent, encre au milieu.
        val bmp = Bitmap.createBitmap(1200, 480, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 0 until 1200) for (y in 120 until 360) bmp.setPixel(x, y, Color.BLUE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val c = CopiePdf(pdf(1), temp)
        c.poserImage(0, Rect(100f, 200f, 300f, 280f), png.path)
        ramasser()
        c.ajouterTexte(0, 100f, 500f, "Mention", 12f, floatArrayOf(0f, 0f, 0f))
        ramasser()
        c.poserImage(0, Rect(100f, 600f, 300f, 680f), png.path)
        ramasser()
        val rendu = AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        assertEquals("1re image toujours là", Color.BLUE, rendu.getPixel(200, 240) or 0xFF000000.toInt())
        assertEquals("2e image", Color.BLUE, rendu.getPixel(200, 640) or 0xFF000000.toInt())
        assertTrue(texteDePage(c.doc, 0).contains("Mention"))
        val relu = CopiePdf(c.enregistrer(), temp)
        val rendu2 = AndroidDrawDevice.drawPage(relu.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        assertEquals("après enregistrement", Color.BLUE, rendu2.getPixel(200, 240) or 0xFF000000.toInt())
    }

    @Test
    fun scenarioDeLApplication() {
        // Le devis réel, rendu entre chaque retouche comme le fait l'aperçu.
        val octets = InstrumentationRegistry.getInstrumentation().context.assets.open("devis.pdf").readBytes()
        val png = File(temp, "sig.png")
        val bmp = Bitmap.createBitmap(1200, 480, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 0 until 1200) for (y in 120 until 360) bmp.setPixel(x, y, Color.BLUE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val c = CopiePdf(octets, temp)
        AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        c.poserImage(0, Rect(60f, 720f, 220f, 784f), png.path)
        val apres1 = AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        assertEquals("signature posée", Color.BLUE, apres1.getPixel(140, 752) or 0xFF000000.toInt())
        ramasser()
        c.ajouterTexte(0, 330f, 700f, "Lu et approuve", 12f, floatArrayOf(0f, 0f, 0.6f))
        ramasser()
        assertTrue("contenu lisible", c.doc.findPage(0).get("Contents").isIndirect)
        assertTrue(texteDePage(c.doc, 0).contains("Lu et approuve"))
        val apres2 = AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        assertEquals("signature encore là après le texte", Color.BLUE, apres2.getPixel(140, 752) or 0xFF000000.toInt())
    }

    @Test
    fun vraieSignatureVisible() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val png = File(temp, "vraie.png")
        ctx.assets.open("signature_reelle.png").use { e -> png.outputStream().use { e.copyTo(it) } }
        val c = CopiePdf(ctx.assets.open("devis.pdf").readBytes(), temp)
        c.poserImage(0, Rect(60f, 720f, 220f, 772f), png.path)
        val rendu = AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(2f))
        var encre = 0
        for (x in 120 until 440) for (y in 1440 until 1544) {
            val p = rendu.getPixel(x, y)
            if (Color.blue(p) > Color.red(p) + 60) encre++
        }
        assertTrue("pixels d'encre bleue : $encre", encre > 500)
    }

    @Test
    fun fusionEtDecoupe() {
        val a = CopiePdf.ouvrir(pdf(2), null)
        val b = CopiePdf.ouvrir(pdf(3), null)
        val fusion = CopiePdf(Assemblage.fusionner(listOf(a, b)), temp)
        assertEquals(listOf(0, 1, 0, 1, 2), ordre(fusion))
        val morceau = CopiePdf(Assemblage.pages(fusion.doc, listOf(2, 4)), temp)
        assertEquals(listOf(0, 2), ordre(morceau))
    }
}
