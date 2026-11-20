// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Un seul fil pour MuPDF : un document ne doit jamais servir à deux fils à la fois. */
object Moteur {
    private val fil = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun <T> lancer(travail: () -> T, erreur: (Throwable) -> Unit = {}, fin: (T) -> Unit) {
        fil.execute {
            try {
                val r = travail()
                ui.post { fin(r) }
            } catch (e: Throwable) {
                ui.post { erreur(e) }
            }
        }
    }
}

/** Ce que fait un outil quand on touche la page (coordonnées du repère affiché de la page). */
interface Interaction {
    fun appui(x: Float, y: Float) {}
    fun glisse(x: Float, y: Float) {}
    fun relache(x: Float, y: Float) {}
    fun dessiner(canvas: Canvas, vue: VuePage) {}
}

/**
 * Aperçu d'une page : ajustée à l'écran, zoom à deux doigts, glisser pour changer
 * de page. Quand un outil est actif, le doigt agit sur la page au lieu de la faire défiler.
 */
class VuePage(ctx: Context) : View(ctx) {
    var copie: CopiePdf? = null
        private set
    var index = 0
        private set
    var largeurPage = 595f
        private set
    var hauteurPage = 842f
        private set
    var interaction: Interaction? = null
        set(v) {
            field = v
            invalidate()
        }
    var pageChangee: (Int) -> Unit = {}

    /** Toucher simple sans outil actif (cases de formulaire), en coordonnées de page. */
    var tape: ((Float, Float) -> Boolean)? = null

    /** Rectangles à montrer sur la page : cases de formulaire (contour), mots trouvés (plein). */
    data class Repere(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val couleur: Int, val plein: Boolean,
                      val etiquette: String? = null)
    var reperes: List<Repere> = emptyList()
        set(v) { field = v; invalidate() }

    private var image: Bitmap? = null
    private var echelleImage = 1f
    private var zoom = 1f          // 1 = page entière à l'écran
    private var decalX = 0f        // déplacement quand on a zoomé
    private var decalY = 0f
    private var generation = 0
    private val peintureImage = Paint(Paint.FILTER_BITMAP_FLAG)
    private val peintureOmbre = Paint().apply { color = Color.BLACK; alpha = 90 }

    fun montrer(c: CopiePdf?, page: Int = index, garderZoom: Boolean = false) {
        copie = c
        index = if (c == null) 0 else page.coerceIn(0, max(0, c.nombrePages - 1))
        if (!garderZoom) { zoom = 1f; decalX = 0f; decalY = 0f }
        rendre()
    }

    fun aller(page: Int) {
        val c = copie ?: return
        if (page !in 0 until c.nombrePages || page == index) return
        index = page
        zoom = 1f; decalX = 0f; decalY = 0f
        rendre()
        pageChangee(index)
    }

    /** Échelle page -> écran et origine de la page à l'écran. */
    /** Hauteur laissée libre en bas pour la barre de navigation des pages. */
    private val reserveBas = 72 * ctx.resources.displayMetrics.density
    private fun ajuste() = min(width * 0.94f / largeurPage, (height - reserveBas) * 0.95f / hauteurPage)
    fun echelle(): Float = ajuste() * zoom
    private fun origineX() = (width - largeurPage * echelle()) / 2 + decalX
    private fun origineY() = (height - reserveBas - hauteurPage * echelle()) / 2 + decalY + 8
    fun versEcran(x: Float, y: Float) = floatArrayOf(origineX() + x * echelle(), origineY() + y * echelle())
    fun versPage(x: Float, y: Float) = floatArrayOf((x - origineX()) / echelle(), (y - origineY()) / echelle())
    fun rectEcran(x0: Float, y0: Float, x1: Float, y1: Float): RectF {
        val a = versEcran(x0, y0); val b = versEcran(x1, y1)
        return RectF(a[0], a[1], b[0], b[1])
    }

    fun rendre() {
        val c = copie
        val gen = ++generation
        if (c == null || width == 0) { image = null; invalidate(); return }
        val i = index
        val cible = min(echelle(), ajuste() * 2.5f)
        Moteur.lancer({
            val page = c.doc.loadPage(i)
            val b = page.bounds
            val e = min(cible, 4096f / max(b.x1 - b.x0, b.y1 - b.y0))
            Triple(Rendu.page(page, Matrix(e)), b, e)
        }) { (bmp, b, e) ->
            if (gen != generation) return@lancer
            largeurPage = b.x1 - b.x0
            hauteurPage = b.y1 - b.y0
            image = bmp
            echelleImage = e
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = rendre()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.DOCUMENT)
        val bmp = image ?: return
        val zone = rectEcran(0f, 0f, largeurPage, hauteurPage)
        canvas.drawRect(zone.left + 4, zone.top + 6, zone.right + 4, zone.bottom + 6, peintureOmbre)
        canvas.drawBitmap(bmp, null, zone, peintureImage)
        for (r in reperes) {
            val e = rectEcran(r.x0, r.y0, r.x1, r.y1)
            canvas.drawRect(e, Paint().apply {
                color = r.couleur
                if (r.plein) alpha = 90 else { style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density; alpha = 170 }
            })
            r.etiquette?.let { t ->
                // Case de signature vide : fond bleuté et « Signer ici : nom », comme dans les autres lecteurs.
                canvas.drawRect(e, Paint().apply { color = Color.rgb(240, 245, 255); alpha = 200 })
                val d = resources.displayMetrics.density
                val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(77, 89, 115); textSize = minOf(12 * d, e.height() / 3)
                }
                canvas.drawText(t, e.left + 6 * d, e.bottom - 6 * d, texte)
            }
        }
        interaction?.dessiner(canvas, this)
    }

    // ------------------------------------------------------------- gestes

    private var multiDoigts = false
    private val zoomeur = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ancien = zoom
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
            val f = zoom / ancien
            decalX = (decalX - (d.focusX - width / 2f)) * f + (d.focusX - width / 2f)
            decalY = (decalY - (d.focusY - height / 2f)) * f + (d.focusY - height / 2f)
            if (zoom == 1f) { decalX = 0f; decalY = 0f }
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) = rendre()
    })

    private val gestes = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom > 1f) { decalX -= dx; decalY -= dy; invalidate() }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoom > 1f || e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) > width / 6f && abs(dx) > abs(e2.y - e1.y)) aller(if (dx < 0) index + 1 else index - 1)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val p = versPage(e.x, e.y)
            return tape?.invoke(p[0], p[1]) ?: false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1f) 1f else 2f
            decalX = 0f; decalY = 0f
            rendre(); invalidate()
            return true
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        zoomeur.onTouchEvent(ev)
        if (ev.pointerCount > 1) multiDoigts = true
        val outil = interaction
        if (outil == null || multiDoigts || zoomeur.isInProgress) {
            if (!zoomeur.isInProgress) gestes.onTouchEvent(ev)
        } else {
            val p = versPage(ev.x, ev.y)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> outil.appui(p[0], p[1])
                MotionEvent.ACTION_MOVE -> outil.glisse(p[0], p[1])
                MotionEvent.ACTION_UP -> outil.relache(p[0], p[1])
            }
            invalidate()
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) multiDoigts = false
        return true
    }
}
