// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/** Mot de passe requis (ou faux) pour ouvrir un PDF protégé. */
class MotDePasseRequis : Exception("PDF protégé par mot de passe")

/** Document illisible ou action impossible, avec un message pour l'utilisateur. */
class ErreurPdf(message: String) : Exception(message)

/**
 * Copie de travail d'un PDF, comme edition.py sur PC : chaque retouche garde un
 * instantané pour l'annuler, et le document n'est écrit qu'à l'enregistrement.
 *
 * Toutes les coordonnées sont dans le repère AFFICHÉ de la page (points, origine
 * en haut à gauche, rotation de la page appliquée) : celui de getBounds() et de l'aperçu.
 */
class CopiePdf(octets: ByteArray, private val dossierTemp: File, motDePasse: String? = null,
               private val maxAnnulations: Int = 20) {

    var doc: PDFDocument = ouvrir(octets, motDePasse)
        private set
    private val annulations = ArrayDeque<Pair<ByteArray, Int>>()
    private var etat = 0
    private var prochainEtat = 1
    private var etatEnregistre = 0

    val modifie: Boolean get() = etat != etatEnregistre
    val peutAnnuler: Boolean get() = annulations.isNotEmpty()
    val nombrePages: Int get() = doc.countPages()

    // ------------------------------------------------------------- base

    /** Le PDF tel qu'il est maintenant (sans nettoyage : les objets restent réutilisables). */
    fun octets(): ByteArray = versOctets(doc, "")

    private fun <T> retoucher(action: () -> T): T {
        val instantane = octets()
        val resultat = try {
            action()
        } catch (e: Throwable) {
            doc = ouvrir(instantane, null)
            throw e
        }
        val change = when (resultat) {
            is Boolean -> resultat
            is Collection<*> -> resultat.isNotEmpty()
            else -> true
        }
        if (change) {
            annulations.addLast(instantane to etat)
            etat = prochainEtat++
            while (annulations.size > maxAnnulations) annulations.removeFirst()
        }
        return resultat
    }

    fun annuler(): Boolean {
        val (octets, ancien) = annulations.removeLastOrNull() ?: return false
        doc = ouvrir(octets, null)
        etat = ancien
        return true
    }

    /** Un document neuf (scan) : il n'existe encore nulle part, il faut l'enregistrer. */
    fun marquerNonEnregistre() { etatEnregistre = -1 }

    /**
     * Le PDF à écrire : nettoyé et compressé, sans toucher à la copie de travail.
     * Avec un mot de passe, le PDF est chiffré (AES 256) et ne s'ouvre plus sans lui.
     */
    fun enregistrer(motDePasse: String? = null): ByteArray {
        val copie = ouvrir(octets(), null)
        val resultat = versOctets(copie, "garbage=3,compress" + optionsChiffrement(motDePasse))
        etatEnregistre = etat
        return resultat
    }

    /**
     * Comme enregistrer(), en allégeant les photos et les scans : chaque image en couleur
     * ou en gris est réduite à `cote` pixels au plus et réécrite en JPEG, si elle y gagne.
     */
    fun compresser(cote: Int = 1600, qualite: Int = 70): ByteArray {
        val copie = ouvrir(octets(), null)
        for (num in 1 until copie.countObjects()) {
            try { alleger(copie, copie.newIndirect(num, 0), cote, qualite) } catch (_: Exception) { /* image laissée telle quelle */ }
        }
        val resultat = versOctets(copie, "garbage=4,compress")
        etatEnregistre = etat
        return resultat
    }

    private fun alleger(d: PDFDocument, o: PDFObject, cote: Int, qualite: Int) {
        if (!o.isStream || o.get("Subtype").asName() != "Image") return
        if (!o.get("SMask").isNull || !o.get("Mask").isNull || o.get("ImageMask").asBoolean()) return
        if (o.get("BitsPerComponent").asInteger() < 8) return   // scans noir et blanc : déjà légers
        val brut = o.readRawStream()
        if (brut.size < 40_000) return
        val pix = d.loadImage(o).toPixmap()
        val n = pix.numberOfComponents
        if (pix.alpha || (n != 1 && n != 3)) return
        val w = pix.width; val h = pix.height; val pas = pix.stride
        val ech = pix.samples
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * pas + x * n
            val r = ech[k].toInt() and 0xFF
            val g = if (n == 3) ech[k + 1].toInt() and 0xFF else r
            val b = if (n == 3) ech[k + 2].toInt() and 0xFF else r
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        var bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val e = minOf(1f, cote.toFloat() / maxOf(w, h))
        if (e < 1f) bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (w * e).toInt().coerceAtLeast(1), (h * e).toInt().coerceAtLeast(1), true)
        val jpeg = java.io.ByteArrayOutputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, qualite, it); it.toByteArray() }
        if (jpeg.size >= brut.size * 0.9) return
        o.writeRawStream(Buffer().apply { writeBytes(jpeg) })
        o.put("Filter", d.newName("DCTDecode"))
        o.delete("DecodeParms"); o.delete("Decode")
        o.put("Width", bmp.width); o.put("Height", bmp.height)
        o.put("BitsPerComponent", 8)
        o.put("ColorSpace", d.newName("DeviceRGB"))
    }

    private fun versOctets(d: PDFDocument, options: String): ByteArray {
        val f = File.createTempFile("pagilis", ".pdf", dossierTemp)
        try {
            d.save(f.path, options)
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    // ------------------------------------------------------------- pages

    fun pivoter(pages: Collection<Int>, angle: Int): Int = retoucher {
        for (i in pages) {
            val objet = doc.findPage(i)
            val actuel = (objet.get("Rotate").asInteger() % 360 + 360) % 360
            objet.put("Rotate", ((actuel + angle) % 360 + 360) % 360)
        }
        pages.size
    }

    fun supprimer(pages: Collection<Int>): Int = retoucher {
        if (pages.toSet().size >= doc.countPages()) throw ErreurPdf("Impossible de supprimer toutes les pages.")
        for (i in pages.toSortedSet().reversed()) doc.deletePage(i)
        pages.size
    }

    /** Déplace les pages à la place `cible` (0 = au début, nombrePages = à la fin) ; nouveaux indices. */
    fun placer(pages: Collection<Int>, cible: Int): List<Int> = retoucher {
        val ordre = ordreApresPlacement(doc.countPages(), pages, cible)
        if (ordre == null) emptyList() else {
            doc.rearrangePages(ordre.toIntArray())
            val selection = pages.toSortedSet()
            val k = (0 until doc.countPages()).count { it !in selection && it < cible }
            (k until k + selection.size).toList()
        }
    }

    /** Insère toutes les pages d'un autre PDF après la page `apres` (-1 = au début). */
    fun inserer(autre: ByteArray, apres: Int, motDePasse: String? = null): List<Int> = retoucher {
        val source = ouvrir(autre, motDePasse)
        val debut = apres + 1
        for (i in 0 until source.countPages()) doc.graftPage(debut + i, source, i)
        (debut until debut + source.countPages()).toList()
    }

    /** Un nouveau PDF fait des pages choisies (la copie de travail ne change pas). */
    fun extraire(pages: Collection<Int>): ByteArray = Assemblage.pages(doc, pages.sorted())

    // ------------------------------------------------------------- ajouts

    /** Écrit `texte` à partir du point (milieu de la hauteur des lettres). Faux si vide. */
    fun ajouterTexte(index: Int, x: Float, y: Float, texte: String, taille: Float, rvb: FloatArray): Boolean =
        retoucher {
            val propre = texte.trim()
            if (propre.isEmpty()) false else {
                val page = doc.loadPage(index) as PDFPage
                val police = doc.addSimpleFont(Font("Helvetica"), Font.SIMPLE_ENCODING_LATIN)
                val nom = nomLibre(ressource(page, "Font"), "PgF")
                ressource(page, "Font").put(nom, police)
                val m = Matrix(1f, 0f, 0f, -1f, x, y + taille * 0.35f).concat(Matrix.Inverted(page.transform))
                val octets = propre.toByteArray(WINANSI)
                val hex = octets.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                ajouterContenu(page, String.format(Locale.US,
                    "q BT /%s %.2f Tf %.3f %.3f %.3f rg %s Tm <%s> Tj ET Q",
                    nom, taille, rvb[0], rvb[1], rvb[2], matrice(m), hex))
                true
            }
        }

    /** Pose une image (PNG transparent possible) dans le rectangle affiché, à l'endroit. */
    fun poserImage(index: Int, rect: Rect, cheminImage: String, opacite: Float = 1f): Boolean = retoucher {
        val page = doc.loadPage(index) as PDFPage
        val image = doc.addImage(Image(cheminImage))
        val xobjets = ressource(page, "XObject")
        val nom = nomLibre(xobjets, "PgImg")
        xobjets.put(nom, image)
        var transparence = ""
        if (opacite < 0.999f) {
            val etats = ressource(page, "ExtGState")
            val nomEtat = nomLibre(etats, "PgGs")
            val gs = doc.newDictionary()
            gs.put("ca", opacite)
            gs.put("CA", opacite)
            etats.put(nomEtat, doc.addObject(gs))
            transparence = "/$nomEtat gs "
        }
        val m = Matrix(rect.x1 - rect.x0, 0f, 0f, -(rect.y1 - rect.y0), rect.x0, rect.y1)
            .concat(Matrix.Inverted(page.transform))
        ajouterContenu(page, "q $transparence${matrice(m)} cm /$nom Do Q")
        true
    }

    // ------------------------------------------------------------- formulaires

    /** Une case de formulaire : son rectangle affiché, son type (PDFWidget.TYPE_*), sa valeur. */
    data class Champ(val numero: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val type: Int,
                     val valeur: String, val options: List<String>, val nom: String,
                     val lectureSeule: Boolean, val multiligne: Boolean) {
        fun contient(x: Float, y: Float, marge: Float = 4f) = x in x0 - marge..x1 + marge && y in y0 - marge..y1 + marge
    }

    val aFormulaire: Boolean get() {
        val champs = doc.trailer.get("Root").get("AcroForm").get("Fields")
        return champs.isArray && champs.size() > 0
    }

    fun champs(index: Int): List<Champ> {
        val page = doc.loadPage(index) as PDFPage
        return (page.widgets ?: emptyArray()).mapIndexed { k, w ->
            val b = w.bounds
            Champ(k, b.x0, b.y0, b.x1, b.y1, w.fieldType, w.value ?: "",
                if (w.isChoice) (w.options ?: emptyArray()).toList() else emptyList(),
                (w.label ?: "").ifBlank { w.name ?: "" }, w.isReadOnly, w.isText && w.isMultiline)
        }
    }

    /** Texte ou choix d'une liste. Faux si le PDF refuse la valeur. */
    fun remplirChamp(index: Int, numero: Int, valeur: String): Boolean = retoucher {
        val page = doc.loadPage(index) as PDFPage
        val w = page.widgets[numero]
        val ok = if (w.isChoice) w.setChoiceValue(valeur) else w.setTextValue(valeur)
        w.update(); page.update()
        ok
    }

    /** Case à cocher ou bouton radio. */
    fun basculerChamp(index: Int, numero: Int): Boolean = retoucher {
        val page = doc.loadPage(index) as PDFPage
        val w = page.widgets[numero]
        w.toggle(); w.update(); page.update()
        true
    }

    // ------------------------------------------------------------- annotations

    /** Surligne le texte entre deux points (comme une sélection). Faux s'il n'y a pas de texte. */
    fun surligner(index: Int, xa: Float, ya: Float, xb: Float, yb: Float, rvb: FloatArray): Boolean = retoucher {
        val page = doc.loadPage(index) as PDFPage
        val quads = page.toStructuredText().highlight(Point(xa, ya), Point(xb, yb))
        if (quads.isNullOrEmpty()) false else {
            val an = page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
            an.setColor(rvb)
            an.setQuadPoints(quads)
            an.update(); page.update()
            true
        }
    }

    /** Trait à main levée : une liste de traits, chacun une suite de points [x, y]. */
    fun dessiner(index: Int, traits: List<List<FloatArray>>, rvb: FloatArray, epaisseur: Float): Boolean = retoucher {
        val bons = traits.filter { it.size >= 2 }
        if (bons.isEmpty()) false else {
            val page = doc.loadPage(index) as PDFPage
            val an = page.createAnnotation(PDFAnnotation.TYPE_INK)
            an.setInkList(bons.map { t -> t.map { Point(it[0], it[1]) }.toTypedArray() }.toTypedArray())
            an.setColor(rvb)
            an.setBorderWidth(epaisseur)
            an.update(); page.update()
            true
        }
    }

    fun encadrer(index: Int, rect: Rect, rvb: FloatArray): Boolean = retoucher {
        if (rect.x1 - rect.x0 < 3 || rect.y1 - rect.y0 < 3) false else {
            val page = doc.loadPage(index) as PDFPage
            val an = page.createAnnotation(PDFAnnotation.TYPE_SQUARE)
            an.setRect(rect)
            an.setColor(rvb)
            an.setBorderWidth(2f)
            an.update(); page.update()
            true
        }
    }

    /** Note repliée (icône), son texte s'affiche dans les lecteurs PDF. */
    fun noter(index: Int, x: Float, y: Float, texte: String): Boolean = retoucher {
        if (texte.isBlank()) false else {
            val page = doc.loadPage(index) as PDFPage
            val an = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
            an.setRect(Rect(x - 10, y - 10, x + 10, y + 10))
            an.setContents(texte.trim())
            an.setColor(floatArrayOf(1f, 0.8f, 0f))
            an.setIcon("Comment")
            an.update(); page.update()
            true
        }
    }

    /** Supprime l'annotation sous le point (la plus haute). Faux s'il n'y en a pas. */
    fun effacerAnnotation(index: Int, x: Float, y: Float): Boolean = retoucher {
        val page = doc.loadPage(index) as PDFPage
        val cible = (page.annotations ?: emptyArray()).lastOrNull { a ->
            val b = a.bounds
            x in b.x0 - 6..b.x1 + 6 && y in b.y0 - 6..b.y1 + 6
        }
        if (cible == null) false else { page.deleteAnnotation(cible); page.update(); true }
    }

    // ------------------------------------------------------------- recherche

    /** Un résultat : la page et les rectangles du mot trouvé (plusieurs s'il passe à la ligne). */
    data class Trouve(val page: Int, val rects: List<FloatArray>)

    fun rechercher(texte: String): List<Trouve> {
        val cherche = texte.trim()
        if (cherche.isEmpty()) return emptyList()
        return (0 until nombrePages).flatMap { i ->
            (doc.loadPage(i).search(cherche) ?: emptyArray()).map { quads ->
                Trouve(i, quads.map { q -> val r = q.toRect(); floatArrayOf(r.x0, r.y0, r.x1, r.y1) })
            }
        }
    }

    /**
     * Ajoute `contenu` par-dessus la page. Le contenu de la page devient UN flux :
     * « q » + ancien contenu + « Q » + ajout. On évite PDFObject.push(), qui perd
     * l'élément ajouté quand le ramasse-miettes de Java passe (défaut de MuPDF Java).
     */
    private fun ajouterContenu(page: PDFPage, contenu: String) {
        val objet = page.`object`
        val actuel = objet.get("Contents")
        val tampon = Buffer()
        tampon.writeLine("q")
        when {
            actuel.isArray -> for (i in 0 until actuel.size()) { tampon.writeBytes(actuel.get(i).readStream()); tampon.writeLine("") }
            !actuel.isNull -> { tampon.writeBytes(actuel.readStream()); tampon.writeLine("") }
        }
        tampon.writeLine("Q")
        tampon.writeLine(contenu)
        objet.put("Contents", doc.addStream(tampon))
    }

    /** Sous-dictionnaire des ressources de la page (créé au besoin, ressources héritées comprises). */
    private fun ressource(page: PDFPage, cle: String): PDFObject {
        val objet = page.`object`
        var ressources = objet.get("Resources")
        if (ressources.isNull) {
            var parent = objet.get("Parent")
            while (!parent.isNull && parent.get("Resources").isNull) parent = parent.get("Parent")
            ressources = if (parent.isNull) doc.newDictionary() else parent.get("Resources")
            objet.put("Resources", ressources)
        }
        var dict = ressources.get(cle)
        if (dict.isNull) {
            dict = doc.newDictionary()
            ressources.put(cle, dict)
        }
        return dict
    }

    private fun nomLibre(dict: PDFObject, prefixe: String): String {
        var k = 1
        while (!dict.get("$prefixe$k").isNull) k++
        return "$prefixe$k"
    }

    private fun matrice(m: Matrix) = String.format(Locale.US, "%.4f %.4f %.4f %.4f %.4f %.4f", m.a, m.b, m.c, m.d, m.e, m.f)

    companion object {
        val WINANSI: Charset = Charset.forName("windows-1252")

        /** Options MuPDF de chiffrement ; la virgule sépare les options, elle est donc refusée. */
        fun optionsChiffrement(motDePasse: String?): String {
            if (motDePasse == null) return ""
            if (motDePasse.isEmpty()) throw ErreurPdf("Le mot de passe est vide.")
            if (',' in motDePasse) throw ErreurPdf("Le mot de passe ne peut pas contenir de virgule.")
            return ",encrypt=aes-256,user-password=$motDePasse,owner-password=$motDePasse"
        }

        /**
         * MuPDF lit le PDF directement dans le tableau d'octets, au fil des besoins,
         * sans le copier : le tableau doit vivre aussi longtemps que le document,
         * sinon le ramasse-miettes le reprend et des objets deviennent illisibles.
         */
        private val octetsDesDocuments = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, ByteArray>())

        fun ouvrir(octets: ByteArray, motDePasse: String?): PDFDocument {
            val d = try {
                Document.openDocument(octets, "application/pdf")
            } catch (e: Exception) {
                throw ErreurPdf("Ce fichier n'est pas un PDF lisible.")
            }
            if (d.needsPassword() && (motDePasse == null || !d.authenticatePassword(motDePasse))) throw MotDePasseRequis()
            val pdf = d.asPDF() ?: throw ErreurPdf("Ce fichier n'est pas un PDF.")
            octetsDesDocuments[pdf] = octets
            octetsDesDocuments[d] = octets
            return pdf
        }

        /** Nouvel ordre des pages après le placement, ou null si rien ne bouge. */
        fun ordreApresPlacement(n: Int, pages: Collection<Int>, cible: Int): List<Int>? {
            val selection = pages.toSortedSet()
            if (selection.isEmpty()) return null
            val reste = (0 until n).filter { it !in selection }
            val k = reste.count { it < cible }
            val ordre = reste.take(k) + selection + reste.drop(k)
            return if (ordre == (0 until n).toList()) null else ordre
        }
    }
}

/** Fusionner, découper : des PDF neufs faits de pages d'autres PDF. */
object Assemblage {
    fun pages(source: PDFDocument, pages: List<Int>): ByteArray {
        val cible = PDFDocument()
        for (i in pages) cible.graftPage(cible.countPages(), source, i)
        return enOctets(cible)
    }

    fun fusionner(documents: List<PDFDocument>): ByteArray {
        val cible = PDFDocument()
        for (d in documents) for (i in 0 until d.countPages()) cible.graftPage(cible.countPages(), d, i)
        return enOctets(cible)
    }

    /** « 1-3, 5, 8- » -> listes d'indices (à partir de 0), une par plage. */
    fun plages(texte: String, n: Int): List<List<Int>> {
        val resultat = mutableListOf<List<Int>>()
        for (morceau in texte.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val bornes = morceau.split('-').map { it.trim() }
            val debut = bornes[0].ifEmpty { "1" }.toIntOrNull() ?: throw ErreurPdf("Plage illisible : « $morceau ».")
            val fin = when {
                bornes.size == 1 -> debut
                bornes[1].isEmpty() -> n
                else -> bornes[1].toIntOrNull() ?: throw ErreurPdf("Plage illisible : « $morceau ».")
            }
            if (debut < 1 || fin > n || debut > fin) throw ErreurPdf("Plage hors du document : « $morceau » (1 à $n).")
            resultat += (debut - 1 until fin).toList()
        }
        if (resultat.isEmpty()) throw ErreurPdf("Indiquez au moins une plage, par exemple 1-3, 5.")
        return resultat
    }

    /** Un PDF d'une page par image JPEG (scan), chaque page à la largeur d'un A4. */
    fun depuisImages(jpegs: List<ByteArray>): ByteArray {
        val d = PDFDocument()
        for (j in jpegs) {
            val img = Image(j)
            val pw = 595f
            val ph = pw * img.height / img.width
            val xobjets = d.newDictionary()
            xobjets.put("Im0", d.addImage(img))
            val ressources = d.newDictionary()
            ressources.put("XObject", xobjets)
            val page = d.addPage(Rect(0f, 0f, pw, ph), 0, ressources,
                String.format(Locale.US, "q %.3f 0 0 %.3f 0 0 cm /Im0 Do Q", pw, ph))
            d.insertPage(d.countPages(), page)
        }
        return enOctets(d)
    }

    fun enOctets(d: PDFDocument): ByteArray {
        val f = File.createTempFile("pagilis", ".pdf")
        try {
            d.save(f.path, "garbage=3,compress")
            return f.readBytes()
        } finally {
            f.delete()
        }
    }
}

/** Texte d'une page (pour la recherche et les vérifications). */
fun texteDePage(doc: Document, i: Int): String = doc.loadPage(i).toStructuredText().asText().trim()
