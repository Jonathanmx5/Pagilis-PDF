// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Icônes de l'application, dessinées en traits fins (grille de 24, comme celles du PC)
 * au lieu d'émojis : même style partout, nettes à toutes les tailles, teintables.
 */
enum class Picto {
    LIRE, SIGNER, TEXTE, ANNOTER, PAGES, FUSION, DECOUPE, SCANNER, DOCUMENT, SUPPRIMER, CASE;

    /** Tracé sur une grille de 24 x 24. */
    fun chemin(): Path = Path().apply {
        when (this@Picto) {
            LIRE -> {           // livre ouvert
                moveTo(12f, 6.5f); cubicTo(10f, 5f, 6.5f, 4.5f, 3f, 5f); lineTo(3f, 18.5f)
                cubicTo(6.5f, 18f, 10f, 18.5f, 12f, 20f); cubicTo(14f, 18.5f, 17.5f, 18f, 21f, 18.5f)
                lineTo(21f, 5f); cubicTo(17.5f, 4.5f, 14f, 5f, 12f, 6.5f); close()
                moveTo(12f, 6.5f); lineTo(12f, 20f)
            }
            SIGNER -> {         // stylo et trait de signature
                moveTo(15.5f, 3.5f); lineTo(20.5f, 8.5f); lineTo(10f, 19f); lineTo(5f, 19f); lineTo(5f, 14f); close()
                moveTo(13.5f, 5.5f); lineTo(18.5f, 10.5f)
                moveTo(13f, 21f); lineTo(21f, 21f)
            }
            TEXTE -> {          // T
                moveTo(5f, 7f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 7f)
                moveTo(12f, 4f); lineTo(12f, 20f)
                moveTo(9f, 20f); lineTo(15f, 20f)
            }
            ANNOTER -> {        // bulle de commentaire
                addRoundRect(RectF(3.5f, 4f, 20.5f, 16f), 2.5f, 2.5f, Path.Direction.CW)
                moveTo(8f, 16f); lineTo(8f, 20.5f); lineTo(12.5f, 16f)
                moveTo(7.5f, 8.5f); lineTo(16.5f, 8.5f)
                moveTo(7.5f, 12f); lineTo(13f, 12f)
            }
            PAGES -> {          // pages empilées
                addRoundRect(RectF(8f, 3f, 20f, 17f), 2f, 2f, Path.Direction.CW)
                moveTo(16f, 21f); lineTo(6f, 21f); quadTo(4f, 21f, 4f, 19f); lineTo(4f, 7f)
            }
            FUSION -> {         // deux flèches qui se rejoignent
                moveTo(6f, 3f); lineTo(6f, 7f); cubicTo(6f, 10.5f, 12f, 10.5f, 12f, 14f); lineTo(12f, 21f)
                moveTo(18f, 3f); lineTo(18f, 7f); cubicTo(18f, 10.5f, 12f, 10.5f, 12f, 14f)
                moveTo(8.5f, 18.5f); lineTo(12f, 21.5f); lineTo(15.5f, 18.5f)
            }
            DECOUPE -> {        // ciseaux
                addCircle(6f, 6.5f, 2.8f, Path.Direction.CW)
                addCircle(6f, 17.5f, 2.8f, Path.Direction.CW)
                moveTo(8.4f, 8f); lineTo(20f, 18.5f)
                moveTo(8.4f, 16f); lineTo(20f, 5.5f)
            }
            SCANNER -> {        // cadre de visée et ligne de lecture
                moveTo(3.5f, 8f); lineTo(3.5f, 3.5f); lineTo(8f, 3.5f)
                moveTo(16f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 8f)
                moveTo(20.5f, 16f); lineTo(20.5f, 20.5f); lineTo(16f, 20.5f)
                moveTo(8f, 20.5f); lineTo(3.5f, 20.5f); lineTo(3.5f, 16f)
                moveTo(7f, 12f); lineTo(17f, 12f)
            }
            DOCUMENT -> {       // feuille au coin plié
                moveTo(14f, 3f); lineTo(7f, 3f); quadTo(5f, 3f, 5f, 5f); lineTo(5f, 19f); quadTo(5f, 21f, 7f, 21f)
                lineTo(17f, 21f); quadTo(19f, 21f, 19f, 19f); lineTo(19f, 8f); close()
                moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
                moveTo(8.5f, 13f); lineTo(15.5f, 13f)
                moveTo(8.5f, 16.5f); lineTo(13f, 16.5f)
            }
            SUPPRIMER -> {      // corbeille
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(9f, 6.5f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 6.5f)
                moveTo(6f, 6.5f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 6.5f)
                moveTo(10f, 10.5f); lineTo(10f, 16f)
                moveTo(14f, 10.5f); lineTo(14f, 16f)
            }
            CASE -> {           // case en pointillés avec trait de signature
                addRoundRect(RectF(3f, 6f, 21f, 18f), 2f, 2f, Path.Direction.CW)
                moveTo(6.5f, 14.5f); cubicTo(8f, 10f, 9.5f, 10f, 10f, 13f); cubicTo(10.5f, 15f, 12f, 11f, 13.5f, 12.5f)
                moveTo(15.5f, 14.5f); lineTo(18f, 14.5f)
            }
        }
    }

    fun dessin(couleur: Int) = Icone(this, couleur)
}

/** Une icône au trait, à la taille des bornes qu'on lui donne. */
class Icone(private val picto: Picto, couleur: Int) : Drawable() {
    private val chemin = picto.chemin()
    private val trace = Path()
    private val m = android.graphics.Matrix()
    private val crayon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = couleur
    }
    var couleur: Int
        get() = crayon.color
        set(v) { crayon.color = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val e = minOf(b.width(), b.height()) / 24f
        m.setScale(e, e)
        m.postTranslate(b.left + (b.width() - 24 * e) / 2, b.top + (b.height() - 24 * e) / 2)
        chemin.transform(m, trace)
        crayon.strokeWidth = 1.8f * e
        canvas.drawPath(trace, crayon)
    }

    override fun setAlpha(alpha: Int) { crayon.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(f: ColorFilter?) { crayon.colorFilter = f; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
