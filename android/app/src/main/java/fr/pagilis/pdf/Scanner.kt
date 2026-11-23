// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import fr.pagilis.pdf.Theme.dp
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scanner un document : photo (appareil photo d'Android, ou image de la galerie),
 * recadrage des quatre coins, redressement, rendu « Document » (fond blanchi) ou
 * « Couleur », page suivante… puis un PDF d'une page par photo.
 */
class Scanner(private val a: MainActivity) {
    private val pages = mutableListOf<ByteArray>()
    private val photo get() = File(Partage.dossier(a), "scan_photo.jpg")
    var rendu = "Document"

    fun commencer() {
        pages.clear()
        AlertDialog.Builder(a).setTitle("Scanner un document")
            .setItems(arrayOf("Prendre une photo", "Choisir des images")) { _, i ->
                if (i == 0) prendrePhoto() else choisirImages()
            }.setNegativeButton("Annuler", null).show()
    }

    private fun prendrePhoto() {
        val f = photo
        f.delete()
        val uri = Partage.uri(a, f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("photo", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivityForResult(i, MainActivity.PHOTO)
        } catch (e: ActivityNotFoundException) {
            a.erreur(ErreurPdf("Aucune application d'appareil photo n'est disponible. Choisissez plutôt des images."))
        }
    }

    private fun choisirImages() {
        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, MainActivity.IMAGES_SCAN)
    }

    /** Retour de l'appareil photo. */
    fun photoPrise(ok: Boolean) {
        if (!ok || !photo.isFile || photo.length() == 0L) {
            if (pages.isNotEmpty()) proposerFin()
            return
        }
        val bmp = charger(photo.path) ?: return a.erreur(ErreurPdf("Photo illisible."))
        recadrer(bmp) { suite -> if (suite) prendrePhoto() else terminer() }
    }

    /** Images de la galerie : recadrées l'une après l'autre. */
    fun imagesChoisies(uris: List<Uri>) {
        val restantes = uris.toMutableList()
        fun suivante() {
            val u = restantes.removeFirstOrNull() ?: return terminer()
            val f = File(Partage.dossier(a), "scan_image.jpg")
            a.contentResolver.openInputStream(u)?.use { e -> f.outputStream().use { e.copyTo(it) } }
            val bmp = charger(f.path) ?: return a.erreur(ErreurPdf("Image illisible : ${a.nomDe(u)}"))
            // « + Page suivante » : image suivante, ou une photo après la dernière ; « Terminer » : PDF.
            recadrer(bmp, derniere = restantes.isEmpty()) { suite ->
                when {
                    !suite -> terminer()
                    restantes.isNotEmpty() -> suivante()
                    else -> prendrePhoto()
                }
            }
        }
        suivante()
    }

    private fun proposerFin() {
        AlertDialog.Builder(a).setMessage("Terminer le scan avec ${pages.size} page(s) ?")
            .setPositiveButton("Terminer") { _, _ -> terminer() }
            .setNegativeButton("Abandonner") { _, _ -> pages.clear() }.show()
    }

    private fun terminer() {
        if (pages.isEmpty()) return
        val jpegs = pages.toList()
        pages.clear()
        a.travailler("Création du PDF…", { Assemblage.depuisImages(jpegs) }) { octets -> a.ouvrirScan(octets) }
    }

    // ------------------------------------------------------------- recadrage

    private fun recadrer(bmp: Bitmap, derniere: Boolean = false, fin: (Boolean) -> Unit) {
        val d = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val vue = VueRecadrage(a, bmp)
        val corps = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.FOND)
            setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
        }
        corps.addView(Theme.titre(a, "Page ${pages.size + 1} : ajustez les coins", 18f))
        corps.addView(Theme.texte(a, "Faites glisser les quatre ronds sur les coins de la feuille.", Theme.TEXTE_2, 13f)
            .apply { setPadding(0, a.dp(2), 0, a.dp(8)) })
        corps.addView(vue, LinearLayout.LayoutParams(-1, 0, 1f))
        val ligneRendu = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(10), 0, 0) }
        ligneRendu.addView(Theme.texte(a, "Rendu  ", Theme.TEXTE))
        ligneRendu.addView(Theme.Segments(a, listOf("Document", "Couleur"), rendu) { rendu = it }, LinearLayout.LayoutParams(0, -2, 1f))
        corps.addView(ligneRendu)
        val boutons = LinearLayout(a).apply { setPadding(0, a.dp(10), 0, 0) }
        fun garder(): Boolean {
            val pret = redresser(bmp, vue.coinsImage(), rendu == "Document")
            pages += ByteArrayOutputStream().use { pret.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
            return true
        }
        boutons.addView(Theme.bouton(a, "Annuler", Theme.Style.DISCRET) {
            d.dismiss(); if (pages.isNotEmpty()) proposerFin()
        }, LinearLayout.LayoutParams(-2, a.dp(48)))
        boutons.addView(Theme.bouton(a, if (derniere) "＋ Photo" else "＋ Page") { garder(); d.dismiss(); fin(true) },
            LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginStart = a.dp(6) })
        boutons.addView(Theme.bouton(a, "Terminer", Theme.Style.PRINCIPAL) { garder(); d.dismiss(); fin(false) },
            LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginStart = a.dp(6) })
        corps.addView(boutons)
        corps.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(a.dp(16) + b.left, a.dp(12) + b.top, a.dp(16) + b.right, a.dp(12) + b.bottom)
            insets
        }
        d.setContentView(corps)
        d.show()
    }

    companion object {
        /** Charge une photo à 3000 px au plus, tournée selon l'EXIF de l'appareil photo. */
        fun charger(chemin: String): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(chemin, o)
            if (o.outWidth <= 0) return null
            var r = 1
            while (max(o.outWidth, o.outHeight) / r > 3000) r *= 2
            val bmp = BitmapFactory.decodeFile(chemin, BitmapFactory.Options().apply { inSampleSize = r }) ?: return null
            val angle = try {
                when (ExifInterface(chemin).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (angle == 0f) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(angle) }, true)
        }

        /**
         * Redresse le quadrilatère `coins` (8 nombres : haut-gauche, haut-droit, bas-droit,
         * bas-gauche, en pixels de l'image) en rectangle ; « document » blanchit le papier.
         */
        fun redresser(src: Bitmap, coins: FloatArray, document: Boolean): Bitmap {
            fun dist(i: Int, j: Int) = hypot(coins[2 * i] - coins[2 * j], coins[2 * i + 1] - coins[2 * j + 1])
            var w = max(dist(0, 1), dist(3, 2))
            var h = max(dist(0, 3), dist(1, 2))
            val e = min(1f, 2200f / max(w, h))
            w = max(1f, w * e); h = max(1f, h * e)
            val m = Matrix()
            m.setPolyToPoly(coins, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)
            val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (document) p.colorFilter = ColorMatrixColorFilter(filtreDocument())
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, m, p) }
            return out
        }

        /** Gris, contraste relevé : le papier devient blanc, l'encre reste nette. */
        fun filtreDocument(): ColorMatrix {
            val gris = ColorMatrix().apply { setSaturation(0f) }
            val c = 1.6f
            val decalage = -0.32f * 255
            val contraste = ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, decalage,
                0f, c, 0f, 0f, decalage,
                0f, 0f, c, 0f, decalage,
                0f, 0f, 0f, 1f, 0f))
            return ColorMatrix().apply { postConcat(gris); postConcat(contraste) }
        }
    }
}

/** La photo et ses quatre coins à placer au doigt. */
class VueRecadrage(ctx: Context, private val image: Bitmap) : View(ctx) {
    // Coins en fractions de l'image : haut-gauche, haut-droit, bas-droit, bas-gauche.
    private val coins = floatArrayOf(0.04f, 0.04f, 0.96f, 0.04f, 0.96f, 0.96f, 0.04f, 0.96f)
    private var tenu = -1
    private val zone = RectF()
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT; strokeWidth = ctx.dp(2.5f); style = Paint.Style.STROKE }
    private val voile = Paint().apply { color = Color.BLACK; alpha = 110 }
    private val rond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT; alpha = 200 }
    private val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f) }

    fun coinsImage() = FloatArray(8) { i -> coins[i] * (if (i % 2 == 0) image.width else image.height) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val e = min(w.toFloat() / image.width, h.toFloat() / image.height)
        val iw = image.width * e; val ih = image.height * e
        zone.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }

    private fun ecran(i: Int) = floatArrayOf(zone.left + coins[2 * i] * zone.width(), zone.top + coins[2 * i + 1] * zone.height())

    override fun onDraw(c: Canvas) {
        c.drawBitmap(image, null, zone, Paint(Paint.FILTER_BITMAP_FLAG))
        val forme = Path().apply {
            val p0 = ecran(0); moveTo(p0[0], p0[1])
            for (i in 1..3) { val p = ecran(i); lineTo(p[0], p[1]) }
            close()
        }
        // Hors de la feuille : assombri.
        val dehors = Path().apply { addRect(zone, Path.Direction.CW); addPath(forme); fillType = Path.FillType.EVEN_ODD }
        c.drawPath(dehors, voile)
        c.drawPath(forme, trait)
        val r = context.dp(13f)
        for (i in 0..3) { val p = ecran(i); c.drawCircle(p[0], p[1], r, rond); c.drawCircle(p[0], p[1], r, bord) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tenu = (0..3).minByOrNull { i -> val p = ecran(i); hypot(p[0] - e.x, p[1] - e.y) } ?: -1
                val p = ecran(tenu)
                if (hypot(p[0] - e.x, p[1] - e.y) > context.dp(56f)) tenu = -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (tenu >= 0 && zone.width() > 0) {
                coins[2 * tenu] = ((e.x - zone.left) / zone.width()).coerceIn(0f, 1f)
                coins[2 * tenu + 1] = ((e.y - zone.top) / zone.height()).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tenu = -1
        }
        return true
    }
}
