// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import fr.pagilis.pdf.Theme.dp
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Encres proposées : RVB. */
val ENCRES = linkedMapOf("Bleue" to Color.rgb(20, 50, 140), "Noire" to Color.rgb(25, 25, 35))

/** Zone où l'on signe au doigt ou au stylet ; le trait imite un stylo plume. */
class PadSignature(ctx: Context) : View(ctx) {
    val traits = mutableListOf<MutableList<FloatArray>>()
    var encre = ENCRES.getValue("Bleue")
        set(v) { field = v; invalidate() }
    val largeurBec get() = context.dp(7f)
    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.5f }
    private val guide = Paint().apply { color = Color.rgb(208, 212, 218); strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) }

    fun effacer() { traits.clear(); invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Stylet : on garde aussi les points intermédiaires, pour un trait fidèle.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> traits += mutableListOf(floatArrayOf(e.x, e.y))
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) traits.last() += floatArrayOf(e.getHistoricalX(h), e.getHistoricalY(h))
                traits.last() += floatArrayOf(e.x, e.y)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        c.drawLine(width * 0.06f, height * 0.75f, width * 0.94f, height * 0.75f, guide)
        peinture.color = encre
        dessinerTraits(c, traits, largeurBec, peinture, 1f, 0f, 0f)
    }

    /** La signature en PNG transparent, rognée au tracé, 3 fois plus fine que l'écran. */
    fun image(): Bitmap? {
        val pts = traits.flatten()
        if (pts.isEmpty()) return null
        val marge = largeurBec * 1.5f
        val x0 = pts.minOf { it[0] } - marge; val y0 = pts.minOf { it[1] } - marge
        val x1 = pts.maxOf { it[0] } + marge; val y1 = pts.maxOf { it[1] } + marge
        val e = 3f * min(1f, 1500f / max(x1 - x0, y1 - y0))
        val bmp = Bitmap.createBitmap(((x1 - x0) * e).toInt().coerceAtLeast(1), ((y1 - y0) * e).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        val p = Paint(peinture).apply { color = encre; strokeWidth = 1.5f * e }
        dessinerTraits(Canvas(bmp), traits, largeurBec, p, e, -x0, -y0)
        return bmp
    }

    companion object {
        fun dessinerTraits(c: Canvas, traits: List<List<FloatArray>>, bec: Float, p: Paint, e: Float, dx: Float, dy: Float) {
            for (t in traits) {
                val lisse = Plume.lisser(t).map { floatArrayOf((it[0] + dx) * e, (it[1] + dy) * e) }
                for (q in Plume.polygones(lisse, bec * e)) {
                    c.drawPath(Path().apply {
                        moveTo(q[0], q[1]); lineTo(q[2], q[3]); lineTo(q[4], q[5]); lineTo(q[6], q[7]); close()
                    }, p)
                }
            }
        }
    }
}

object Signature {
    /** Les signatures gardées : signature complète, initiales, paraphe… une image par fichier. */
    fun dossier(ctx: Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }

    fun liste(ctx: Context): List<File> {
        // Avant la 0.3.0, une seule signature : signature.png. On la range avec les autres.
        val ancienne = File(ctx.filesDir, "signature.png")
        if (ancienne.isFile) ancienne.renameTo(File(dossier(ctx), "sig_0.png"))
        return dossier(ctx).listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name.removePrefix("sig_").removeSuffix(".png").toLongOrNull() ?: 0 }
    }

    fun nouveau(ctx: Context) = File(dossier(ctx), "sig_${System.currentTimeMillis()}.png")
    fun enregistree(ctx: Context): File? = liste(ctx).lastOrNull()

    /** Image réduite pour l'aperçu à l'écran (une image trop grande ne s'afficherait pas). */
    fun apercu(f: File): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var reduction = 1
        while (max(o.outWidth, o.outHeight) / reduction > 1200) reduction *= 2
        return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = reduction })
    }

    fun ratio(f: File): Float {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 0.4f
    }

    /**
     * « Quelle signature ? » : les signatures et tampons gardés en miniatures, plus
     * « Nouvelle signature ». Sans signature gardée, on passe directement à la création.
     */
    fun choisir(ctx: Context, titre: String, fin: (File) -> Unit) {
        val gardees = liste(ctx)
        if (gardees.isEmpty()) return creer(ctx, fin)
        var dlg: android.app.AlertDialog? = null
        val grille = android.widget.GridLayout(ctx).apply { columnCount = 2; setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        for (f in gardees.reversed()) {
            grille.addView(android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
                background = android.graphics.drawable.LayerDrawable(arrayOf(Theme.damier(ctx),
                    Theme.fond(android.graphics.Color.TRANSPARENT, ctx.dp(8f), Theme.BORDURE, ctx.dp(1))))
                clipToOutline = true
                setImageBitmap(apercu(f))
                contentDescription = "Signature"
                setOnClickListener { dlg?.dismiss(); fin(f) }
            }, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = ctx.dp(64); setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            })
        }
        dlg = android.app.AlertDialog.Builder(ctx).setTitle(titre)
            .setView(android.widget.ScrollView(ctx).apply { addView(grille) })
            .setPositiveButton("＋ Nouvelle signature") { _, _ -> creer(ctx, fin) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Fenêtre « Créer ma signature » ; fin(fichier) reçoit la signature enregistrée. */
    fun creer(ctx: Context, fin: (File) -> Unit) {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = PadSignature(ctx)
        val corps = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.fond(Theme.SURFACE, ctx.dp(14f))
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
            addView(Theme.titre(ctx, "Créer ma signature", 20f))
            addView(Theme.texte(ctx, "Signez au doigt ou au stylet : le trait imite un stylo plume. " +
                "Elle sera gardée sur cet appareil."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(pad, LinearLayout.LayoutParams(-1, ctx.dp(if (ctx.resources.configuration.screenWidthDp < 560) 200 else 240)).apply { topMargin = ctx.dp(16) })
            // Téléphone : l'encre sur sa propre ligne, les boutons dessous.
            val etroit = ctx.resources.configuration.screenWidthDp < 560
            val encre = LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            encre.addView(Theme.texte(ctx, "Encre  ", Theme.TEXTE))
            encre.addView(Theme.Segments(ctx, ENCRES.keys.toList(), "Bleue") { pad.encre = ENCRES.getValue(it) },
                LinearLayout.LayoutParams(ctx.dp(200), -2))
            val bas = if (etroit) LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                .also { addView(encre, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) }) } else encre
            bas.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            bas.addView(Theme.bouton(ctx, "Effacer", Theme.Style.DISCRET) { pad.effacer() })
            bas.addView(Theme.bouton(ctx, "Annuler") { d.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })
            bas.addView(Theme.bouton(ctx, "Utiliser", Theme.Style.PRINCIPAL) {
                val bmp = pad.image()
                if (bmp == null) {
                    android.widget.Toast.makeText(ctx, "Signez d'abord dans le cadre.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val f = nouveau(ctx)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    d.dismiss()
                    fin(f)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ctx.dp(8) })
            addView(bas, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
        }
        d.setContentView(corps)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val largeur = min(ctx.resources.displayMetrics.widthPixels - ctx.dp(32), ctx.dp(720))
        d.window?.setLayout(largeur, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }
}
