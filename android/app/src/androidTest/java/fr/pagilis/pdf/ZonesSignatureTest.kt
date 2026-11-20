package fr.pagilis.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Cases de signature : créées pour quelqu'un d'autre, remplies par soi-même. */
@RunWith(AndroidJUnit4::class)
class ZonesSignatureTest {
    private val temp: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private fun asset(n: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(n).readBytes()
    private fun ramasser() = repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(50) }
    private fun relu(c: CopiePdf) = CopiePdf(c.enregistrer(), temp)
    private fun cases(c: CopiePdf, i: Int = 0) = c.champs(i).filter { it.type == PDFWidget.TYPE_SIGNATURE }

    /** Signature noire, large et basse, sur fond transparent. */
    private fun signature(): File {
        val bmp = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawRect(20f, 20f, 380f, 80f, android.graphics.Paint().apply { color = Color.BLACK })
        return File(temp, "sig_test.png").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun caseCreeeVisibleDansLesAutresLecteurs() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        assertTrue(c.creerZoneSignature(0, Rect(320f, 700f, 520f, 760f), "Client"))
        ramasser()
        assertTrue(c.creerZoneSignature(0, Rect(60f, 700f, 260f, 760f), "Client"))   // même nom : rendu unique
        ramasser()
        assertFalse("trop petite", c.creerZoneSignature(0, Rect(10f, 10f, 12f, 12f), "X"))

        val r = relu(c)
        assertTrue(r.aFormulaire)
        val l = cases(r)
        assertEquals(2, l.size)
        assertEquals(2, l.map { it.nom }.toSet().size)
        assertTrue(l.any { it.nom == "Client" })
        val k = l.first { it.nom == "Client" }
        assertEquals(320f, k.x0, 1f); assertEquals(700f, k.y0, 1f); assertEquals(520f, k.x1, 1f); assertEquals(760f, k.y1, 1f)

        c.annuler(); c.annuler()
        assertTrue(cases(c).isEmpty())
    }

    /** Pixels orangés (l'étiquette « SIGN » de MuPDF) et sombres (le texte) dans un rectangle. */
    private fun compter(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Int, Int> {
        var orange = 0; var sombre = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = bmp.getPixel(x, y)
            if (Color.red(p) > 180 && Color.green(p) < 120 && Color.blue(p) < 100) orange++
            if (Color.red(p) < 110 && Color.green(p) < 110 && Color.blue(p) < 110) sombre++
        }
        return orange to sombre
    }

    @Test
    fun caseAfficheSignerIciEnFrancais() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        c.creerZoneSignature(0, Rect(320f, 700f, 520f, 760f), "Client"); ramasser()
        c.ajouterTexte(0, 60f, 100f, "Autre retouche", 12f, floatArrayOf(0f, 0f, 0f)); ramasser()
        val r = relu(c)
        // Ce que lisent Adobe, Aperçu, Chrome : l'apparence enregistrée dans le PDF.
        val ap = String((r.doc.loadPage(0) as com.artifex.mupdf.fitz.PDFPage).widgets[0].getObject().get("AP").get("N").readStream(), Charsets.ISO_8859_1)
        val attendu = "Signer ici : Client".toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertTrue("apparence enregistrée en français", ap.contains(attendu))
        // Ce que montre l'application : la page sans l'étiquette « SIGN » de MuPDF.
        val bmp = Rendu.page(r.doc.loadPage(0), Matrix(1f))
        assertEquals("pas d'étiquette « SIGN » à l'écran", 0, compter(bmp, 321, 701, 519, 759).first)
        assertEquals("toujours une vraie case", 1, cases(r).size)
        // Une case signée ou un autre champ reste dessiné normalement.
        val f = CopiePdf(asset("formulaire.pdf"), temp)
        f.remplirChamp(0, f.champs(0).first { it.type == PDFWidget.TYPE_TEXT }.numero, "Visible"); ramasser()
        val n = f.champs(0).first { it.type == PDFWidget.TYPE_TEXT }
        val vue = Rendu.page(relu(f).doc.loadPage(0), Matrix(2f))
        var encre = 0
        for (y in (n.y0 * 2).toInt() until (n.y1 * 2).toInt()) for (x in (n.x0 * 2).toInt() until (n.x1 * 2).toInt())
            if (Color.red(vue.getPixel(x, y)) < 100) encre++
        assertTrue("les autres champs restent visibles ($encre)", encre > 100)
    }

    @Test
    fun signaturePoseeRetrouveeEtDeplacee() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        assertTrue(c.poserImage(0, Rect(320f, 700f, 520f, 750f), signature().path, 0.8f)); ramasser()
        val l = c.imagesPosees(0)
        assertEquals(1, l.size)
        val p = l.single()
        assertEquals(320f, p.x0, 1f); assertEquals(700f, p.y0, 1f); assertEquals(520f, p.x1, 1f); assertEquals(750f, p.y1, 1f)
        assertTrue(p.contient(420f, 725f))

        assertTrue(c.deplacerImage(0, p.nom, Rect(60f, 600f, 260f, 650f))); ramasser()
        val bmp = AndroidDrawDevice.drawPage(relu(c).doc.loadPage(0), Matrix(1f))
        assertTrue("encre au nouvel endroit", Color.red(bmp.getPixel(160, 625)) < 120)
        assertTrue("plus rien à l'ancien", Color.red(bmp.getPixel(420, 725)) > 200)
        val q = c.imagesPosees(0).single()
        assertEquals(60f, q.x0, 1f); assertEquals(600f, q.y0, 1f)
        assertFalse(c.deplacerImage(0, "Inconnue", Rect(0f, 0f, 10f, 10f)))

        c.annuler()
        assertEquals(320f, c.imagesPosees(0).single().x0, 1f)
    }

    @Test
    fun caseRempliePorteLaSignatureEtDisparait() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        c.creerZoneSignature(0, Rect(320f, 700f, 520f, 760f), "Moi"); ramasser()
        c.creerZoneSignature(0, Rect(60f, 700f, 260f, 760f), "Client"); ramasser()
        val moi = cases(c).first { it.nom == "Moi" }
        assertTrue(c.signerZone(0, moi.numero, signature().path))
        ramasser()

        val r = relu(c)
        assertEquals("la case du client reste à signer", listOf("Client"), cases(r).map { it.nom })
        val bmp = AndroidDrawDevice.drawPage(r.doc.loadPage(0), Matrix(1f))
        // Signature ajustée au cadre sans déformation : 200 x 50 pt centrée en hauteur (705-755).
        assertTrue("encre au centre de la case", Color.red(bmp.getPixel(420, 730)) < 60)
        assertTrue("pas d'encre au-dessus de la signature ajustée", Color.red(bmp.getPixel(420, 702)) > 200)

        c.annuler()
        assertEquals(2, cases(c).size)
    }
}
