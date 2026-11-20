package fr.pagilis.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Formulaires, annotations, recherche, copie allégée, mot de passe, scan (0.3.0). */
@RunWith(AndroidJUnit4::class)
class NouveautesTest {
    private val temp: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private fun asset(n: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(n).readBytes()
    private fun ramasser() = repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(50) }

    /** Enregistré puis rouvert : ce qu'un autre lecteur PDF verra. */
    private fun relu(c: CopiePdf) = CopiePdf(c.enregistrer(), temp)

    @Test
    fun formulaireRempliEtGarde() {
        val c = CopiePdf(asset("formulaire.pdf"), temp)
        assertTrue(c.aFormulaire)
        val champs = c.champs(0)
        val nom = champs.first { it.type == PDFWidget.TYPE_TEXT }
        val accord = champs.first { it.type == PDFWidget.TYPE_CHECKBOX }
        val ville = champs.first { it.type == PDFWidget.TYPE_COMBOBOX }
        assertEquals(listOf("Lyon", "Paris", "Nantes"), ville.options)
        assertTrue(nom.contient((nom.x0 + nom.x1) / 2, (nom.y0 + nom.y1) / 2))

        assertTrue(c.remplirChamp(0, nom.numero, "Élodie Martin"))
        ramasser()
        assertTrue(c.basculerChamp(0, accord.numero))
        ramasser()
        assertTrue(c.remplirChamp(0, ville.numero, "Nantes"))
        ramasser()

        val r = relu(c)
        val apres = r.champs(0)
        assertEquals("Élodie Martin", apres.first { it.type == PDFWidget.TYPE_TEXT }.valeur)
        assertFalse(apres.first { it.type == PDFWidget.TYPE_CHECKBOX }.valeur in listOf("", "Off"))
        assertEquals("Nantes", apres.first { it.type == PDFWidget.TYPE_COMBOBOX }.valeur)
        // Visible : des pixels sombres sont apparus dans la case du nom.
        fun encre(doc: com.artifex.mupdf.fitz.Document): Int {
            val bmp = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(2f))
            var n = 0
            for (y in (nom.y0 * 2).toInt() until (nom.y1 * 2).toInt()) for (x in (nom.x0 * 2).toInt() until (nom.x1 * 2).toInt())
                if (Color.red(bmp.getPixel(x, y)) < 100) n++
            return n
        }
        val vide = encre(CopiePdf(asset("formulaire.pdf"), temp).doc)
        assertTrue("le texte rempli doit être visible ($vide -> ${encre(r.doc)})", encre(r.doc) > vide + 200)

        // Annulable comme toute retouche.
        c.annuler(); c.annuler(); c.annuler()
        assertEquals("", c.champs(0).first { it.type == PDFWidget.TYPE_TEXT }.valeur)
    }

    @Test
    fun annotationsGardeesEtEffacables() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        val trouve = c.rechercher("Atelier Moreau").first()
        val r = trouve.rects.first()
        assertTrue(c.surligner(0, r[0] + 1, (r[1] + r[3]) / 2, r[2] - 1, (r[1] + r[3]) / 2, floatArrayOf(1f, 0.85f, 0f)))
        ramasser()
        assertTrue(c.dessiner(0, listOf(listOf(floatArrayOf(100f, 600f), floatArrayOf(150f, 620f), floatArrayOf(200f, 600f))), floatArrayOf(1f, 0f, 0f), 2f))
        ramasser()
        assertTrue(c.encadrer(0, Rect(60f, 640f, 300f, 700f), floatArrayOf(0f, 0f, 1f)))
        ramasser()
        assertTrue(c.noter(0, 500f, 100f, "À vérifier"))
        ramasser()
        assertFalse("pas de surlignage hors du texte", c.surligner(0, 10f, 830f, 20f, 835f, floatArrayOf(1f, 1f, 0f)))
        assertFalse(c.noter(0, 1f, 1f, "  "))

        val relue = relu(c)
        val types = ((relue.doc.loadPage(0) as PDFPage).annotations ?: emptyArray()).map { it.type }.sorted()
        assertEquals(listOf(PDFAnnotation.TYPE_TEXT, PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_HIGHLIGHT, PDFAnnotation.TYPE_INK).sorted(), types)
        assertEquals("À vérifier", ((relue.doc.loadPage(0) as PDFPage).annotations.first { it.type == PDFAnnotation.TYPE_TEXT }).contents)

        assertTrue(c.effacerAnnotation(0, 180f, 670f))   // le cadre
        assertFalse(c.effacerAnnotation(0, 400f, 400f))
        assertEquals(3, ((relu(c).doc.loadPage(0) as PDFPage).annotations ?: emptyArray()).size)
    }

    @Test
    fun rechercheSurToutesLesPages() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        val l = c.rechercher("eur")   // sans tenir compte des majuscules
        assertTrue(l.size >= 6)
        assertTrue(l.all { it.rects.isNotEmpty() && it.page in 0 until c.nombrePages })
        assertTrue(c.rechercher("introuvable-xyz").isEmpty())
        assertTrue(c.rechercher("   ").isEmpty())
    }

    @Test
    fun copieAllegeeBienPlusPetiteEtLisible() {
        val avant = asset("photo.pdf")
        val c = CopiePdf(avant, temp)
        val apres = c.compresser()
        assertTrue("allégé : ${avant.size} -> ${apres.size}", apres.size < avant.size / 3)
        val r = CopiePdf(apres, temp)
        assertEquals(1, r.nombrePages)
        // L'image est toujours là et en couleur (à droite, en haut de l'image : rouge).
        fun pixel(c: CopiePdf): Int {
            val bmp = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(c.doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(0.5f))
            return bmp.getPixel(bmp.width - 8, bmp.height / 10)
        }
        val ref = pixel(CopiePdf(avant, temp))
        assertTrue("repère valable", Color.red(ref) > 200 && Color.blue(ref) < 160)
        val px = pixel(r)
        assertTrue("même couleur après allègement", Color.red(px) > 200 && Color.blue(px) < 160 && Color.green(px) < 80)
        // Un PDF sans grosse image n'est pas abîmé.
        assertEquals(6, CopiePdf(CopiePdf(asset("devis.pdf"), temp).compresser(), temp).nombrePages)
    }

    @Test
    fun copieProtegeeParMotDePasse() {
        val c = CopiePdf(asset("devis.pdf"), temp)
        val protege = c.enregistrer("Secret123")
        try { CopiePdf(protege, temp); fail("aurait dû demander le mot de passe") } catch (_: MotDePasseRequis) {}
        try { CopiePdf(protege, temp, "mauvais"); fail("mauvais mot de passe accepté") } catch (_: MotDePasseRequis) {}
        assertEquals(6, CopiePdf(protege, temp, "Secret123").nombrePages)
        try { c.enregistrer("a,b"); fail("virgule acceptée") } catch (_: ErreurPdf) {}
    }

    @Test
    fun tamponPngTransparentLaisseVoirLaPage() {
        // Tampon : anneau rouge sur fond transparent.
        val png = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(png).drawCircle(200f, 200f, 170f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = 30f
        })
        val f = File(temp, "tampon.png")
        f.outputStream().use { png.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Sur une page noire : là où le tampon est transparent, on doit voir le noir.
        val d = com.artifex.mupdf.fitz.PDFDocument()
        d.insertPage(-1, d.addPage(Rect(0f, 0f, 400f, 400f), 0, d.newDictionary(), "0 0 0 rg 0 0 400 400 re f"))
        val c = CopiePdf(Assemblage.enOctets(d), temp)
        assertTrue(c.poserImage(0, Rect(0f, 0f, 400f, 400f), f.path))
        ramasser()
        val bmp = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(relu(c).doc.loadPage(0), com.artifex.mupdf.fitz.Matrix(1f))
        val centre = bmp.getPixel(200, 200)
        val anneau = bmp.getPixel(200, 30)
        assertTrue("centre transparent : la page noire se voit", Color.red(centre) < 30 && Color.green(centre) < 30)
        assertTrue("anneau rouge", Color.red(anneau) > 200 && Color.green(anneau) < 60)
    }

    @Test
    fun scanEnPdfUnePageParPhoto() {
        // Une « photo » : feuille blanche penchée sur fond gris, texte noir.
        val photo = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(photo)
        cv.drawColor(Color.rgb(90, 90, 90))
        cv.drawRect(150f, 200f, 1050f, 1400f, android.graphics.Paint().apply { color = Color.rgb(205, 200, 190) })
        cv.drawRect(300f, 500f, 900f, 560f, android.graphics.Paint().apply { color = Color.rgb(40, 40, 40) })
        val coins = floatArrayOf(150f, 200f, 1050f, 200f, 1050f, 1400f, 150f, 1400f)
        val page = Scanner.redresser(photo, coins, document = true)
        assertEquals(900, page.width); assertEquals(1200, page.height)
        assertTrue("papier blanchi", Color.red(page.getPixel(20, 20)) > 235)
        assertTrue("encre noire", Color.red(page.getPixel(450, 330)) < 40)

        val jpeg = ByteArrayOutputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
        val pdf = Assemblage.depuisImages(listOf(jpeg, jpeg))
        ramasser()
        val c = CopiePdf(pdf, temp)
        assertEquals(2, c.nombrePages)
        val b = c.doc.loadPage(0).bounds
        assertEquals(595f, b.x1 - b.x0, 0.5f)
        assertEquals(793.3f, b.y1 - b.y0, 1f)
        c.marquerNonEnregistre()
        assertTrue("un scan est à enregistrer", c.modifie)
    }
}
