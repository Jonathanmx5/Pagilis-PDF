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
