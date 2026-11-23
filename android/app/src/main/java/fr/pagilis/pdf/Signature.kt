// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.pagilis.pdf.Theme.dp
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Encres proposées : RVB. */
val ENCRES = linkedMapOf("Bleue" to Color.rgb(20, 50, 140), "Noire" to Color.rgb(25, 25, 35))

/** Épaisseurs du bec, en dp. */
val EPAISSEURS = linkedMapOf("Fin" to 5f, "Moyen" to 7f, "Épais" to 10f)

/** Zone où l'on signe au doigt ou au stylet ; le trait imite un stylo plume. */
class PadSignature(ctx: Context) : View(ctx) {
    /** Points [x, y, pression] ; la pression vaut 1 au doigt. */
    val traits = mutableListOf<MutableList<FloatArray>>()
    var encre = ENCRES.getValue("Bleue")
        set(v) { field = v; invalidate() }
    var epaisseur = EPAISSEURS.getValue("Moyen")
        set(v) { field = v; invalidate() }
    val largeurBec get() = context.dp(epaisseur)
    var change: () -> Unit = {}
    private val peinture = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.5f }
    private val guide = Paint().apply { color = Color.rgb(208, 212, 218); strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) }
    private val consigne = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 175, 185); textAlign = Paint.Align.CENTER }

    fun effacer() { traits.clear(); invalidate(); change() }
    fun annulerTrait() { traits.removeLastOrNull(); invalidate(); change() }

    /** Stylet : la pression affine ou épaissit le trait. Doigt : trait régulier. */
    private fun pression(e: MotionEvent, p: Float) =
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) (0.45f + 0.85f * p).coerceIn(0.45f, 1.3f) else 1f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // On garde aussi les points intermédiaires, pour un trait fidèle.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                traits += mutableListOf(floatArrayOf(e.x, e.y, pression(e, e.pressure)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize)
                    traits.last() += floatArrayOf(e.getHistoricalX(h), e.getHistoricalY(h), pression(e, e.getHistoricalPressure(h)))
                traits.last() += floatArrayOf(e.x, e.y, pression(e, e.pressure))
            }
            MotionEvent.ACTION_UP -> change()
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        val ligne = height * 0.72f
        c.drawLine(width * 0.05f, ligne, width * 0.95f, ligne, guide)
        if (traits.isEmpty()) {
            consigne.textSize = context.dp(15f)
            c.drawText("Signez ici, au doigt ou au stylet", width / 2f, ligne - context.dp(14f), consigne)
        }
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
                val lisse = Plume.lisser(t).map { q -> floatArrayOf((q[0] + dx) * e, (q[1] + dy) * e, q.getOrElse(2) { 1f }) }
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

    // ------------------------------------------------------------ noms

    /** Le nom est gardé à côté de l'image : sig_123.png -> sig_123.txt. */
    private fun fichierNom(f: File) = File(f.parentFile, f.name.removeSuffix(".png") + ".txt")

    fun nom(f: File): String = runCatching { fichierNom(f).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Signature"

    fun nommer(f: File, nom: String) {
        val n = nom.trim()
        if (n.isEmpty()) fichierNom(f).delete() else fichierNom(f).writeText(n)
    }

    fun supprimer(f: File) { f.delete(); fichierNom(f).delete() }

    /** Signature, puis Initiales, puis Paraphe ; ensuite « Signature 2 »… */
    fun nomPropose(gardees: List<File>): String {
        val pris = gardees.map { nom(it) }.toSet()
        listOf("Signature", "Initiales", "Paraphe").firstOrNull { it !in pris }?.let { return it }
        var n = 2
        while ("Signature $n" in pris) n++
        return "Signature $n"
    }

    // ------------------------------------------------------------ affichage

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

    /** Miniature sur damier avec son nom dessous ; `choisie` l'encadre en bleu. */
    fun vignette(ctx: Context, f: File, image: Bitmap?, choisie: Boolean = false, hauteur: Int = 56): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
                background = android.graphics.drawable.LayerDrawable(arrayOf(Theme.damier(ctx),
                    Theme.fond(Color.TRANSPARENT, ctx.dp(8f), if (choisie) Theme.ACCENT else Theme.BORDURE, ctx.dp(if (choisie) 3 else 1))))
                clipToOutline = true
                setImageBitmap(image)
                contentDescription = nom(f)
            }, LinearLayout.LayoutParams(-1, ctx.dp(hauteur)))
            addView(TextView(ctx).apply {
                text = nom(f); textSize = 12f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (choisie) Theme.TEXTE else Theme.TEXTE_2)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(3) })
        }

    /** Renommer ou supprimer une signature gardée ; `apres` rafraîchit l'écran appelant. */
    fun gerer(ctx: Context, f: File, apres: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(nom(f))
            .setItems(arrayOf("Renommer", "Supprimer")) { _, i ->
                if (i == 0) demanderNom(ctx, "Renommer", nom(f)) { n -> nommer(f, n); apres() }
                else AlertDialog.Builder(ctx).setMessage("Supprimer « ${nom(f)} » de l'appareil ?")
                    .setPositiveButton("Supprimer") { _, _ -> supprimer(f); apres() }
                    .setNegativeButton("Annuler", null).show()
            }.setNegativeButton("Fermer", null).show()
    }

    fun demanderNom(ctx: Context, titre: String, initial: String, fin: (String) -> Unit) {
        val saisie = EditText(ctx).apply {
            setText(initial); selectAll(); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val d = AlertDialog.Builder(ctx).setTitle(titre).setView(LinearLayout(ctx).apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0); addView(saisie, LinearLayout.LayoutParams(-1, -2))
        }).setPositiveButton("OK") { _, _ -> fin(saisie.text.toString()) }.setNegativeButton("Annuler", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    /**
     * « Quelle signature ? » : les signatures et tampons gardés en miniatures, avec leur
     * nom, plus « Nouvelle signature ». Sans signature gardée, on passe à la création.
     */
    fun choisir(ctx: Context, titre: String, fin: (File) -> Unit) {
        val gardees = liste(ctx)
        if (gardees.isEmpty()) return creer(ctx, fin)
        var dlg: AlertDialog? = null
        val grille = android.widget.GridLayout(ctx).apply { columnCount = 2; setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        for (f in gardees.reversed()) {
            grille.addView(vignette(ctx, f, apercu(f), hauteur = 64).apply { setOnClickListener { dlg?.dismiss(); fin(f) } },
                android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(8))
                })
        }
        dlg = AlertDialog.Builder(ctx).setTitle(titre)
            .setView(android.widget.ScrollView(ctx).apply { addView(grille) })
            .setPositiveButton("＋ Nouvelle signature") { _, _ -> creer(ctx, fin) }
            .setNegativeButton("Annuler", null).show()
    }

    /**
     * « Nouvelle signature », en plein écran. Sur téléphone, l'écran passe à l'horizontale
     * le temps de signer : la zone est deux à trois fois plus grande qu'en hauteur.
     * fin(fichier) reçoit la signature enregistrée (avec son nom).
     */
    fun creer(ctx: Context, fin: (File) -> Unit) {
        val activite = ctx as? Activity
        val telephone = ctx.resources.configuration.smallestScreenWidthDp < 600
        val orientationAvant = activite?.requestedOrientation
        if (telephone) activite?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val d = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setOnDismissListener { if (telephone && orientationAvant != null) activite.requestedOrientation = orientationAvant }
        val pad = PadSignature(ctx)
        val nomSaisi = EditText(ctx).apply {
            setText(nomPropose(liste(ctx))); setSingleLine(); textSize = 15f
            setTextColor(Theme.TEXTE); setHintTextColor(Theme.TEXTE_DISCRET); hint = "Signature, Initiales…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            background = Theme.fond(Theme.SURFACE_2, ctx.dp(8f), Theme.BORDURE, ctx.dp(1))
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        }
        fun bouton(t: String, style: Theme.Style = Theme.Style.SECONDAIRE, f: () -> Unit) =
            Theme.bouton(ctx, t, style, f).apply { textSize = 14f; setPadding(ctx.dp(14), 0, ctx.dp(14), 0) }

        val annulerTrait = bouton("↶ Trait") { pad.annulerTrait() }
        val effacer = bouton("Effacer", Theme.Style.DISCRET) { pad.effacer() }
        fun majBoutons() { val a = pad.traits.isNotEmpty(); Theme.activer(annulerTrait, a); Theme.activer(effacer, a) }
        pad.change = { majBoutons() }
        majBoutons()

        val corps = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.FOND)
            setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            // En haut : titre et nom de la signature.
            val haut = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            haut.addView(Theme.titre(ctx, "Nouvelle signature", 18f), LinearLayout.LayoutParams(0, -2, 1f))
            haut.addView(Theme.texte(ctx, "Nom  ", Theme.TEXTE_2))
            haut.addView(nomSaisi, LinearLayout.LayoutParams(ctx.dp(190), ctx.dp(42)))
            addView(haut)
            // La zone de signature prend toute la place qui reste.
            addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ctx.dp(10); bottomMargin = ctx.dp(10) })
            // En bas : encre, épaisseur, corrections ; puis Annuler / Utiliser.
            val reglages = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            reglages.addView(Theme.Segments(ctx, ENCRES.keys.toList(), "Bleue") { pad.encre = ENCRES.getValue(it) },
                LinearLayout.LayoutParams(ctx.dp(150), -2))
            reglages.addView(Theme.Segments(ctx, EPAISSEURS.keys.toList(), "Moyen") { pad.epaisseur = EPAISSEURS.getValue(it) },
                LinearLayout.LayoutParams(ctx.dp(210), -2).apply { marginStart = ctx.dp(10) })
            reglages.addView(annulerTrait, LinearLayout.LayoutParams(-2, ctx.dp(44)).apply { marginStart = ctx.dp(10) })
            reglages.addView(effacer, LinearLayout.LayoutParams(-2, ctx.dp(44)).apply { marginStart = ctx.dp(4) })
            val actions = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            actions.addView(bouton("Annuler") { d.dismiss() }, LinearLayout.LayoutParams(-2, ctx.dp(44)))
            actions.addView(bouton("Utiliser", Theme.Style.PRINCIPAL) {
                val bmp = pad.image()
                if (bmp == null) {
                    android.widget.Toast.makeText(ctx, "Signez d'abord dans le cadre.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val f = nouveau(ctx)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    nommer(f, nomSaisi.text.toString())
                    d.dismiss()
                    fin(f)
                }
            }, LinearLayout.LayoutParams(-2, ctx.dp(44)).apply { marginStart = ctx.dp(8) })
            // Large (téléphone à l'horizontale, tablette) : une seule rangée ; sinon deux.
            if (ctx.resources.configuration.screenWidthDp >= 760 || telephone) {
                val bas = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                bas.addView(reglages, LinearLayout.LayoutParams(0, -2, 1f))
                bas.addView(actions)
                addView(bas)
            } else {
                addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(reglages) })
                addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
            }
        }
        // Plein écran : on laisse la place aux barres du système (navigation, encoche).
        corps.setOnApplyWindowInsetsListener { v, ins ->
            val (g, h, dr, b) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            v.setPadding(ctx.dp(16) + g, ctx.dp(10) + h, ctx.dp(16) + dr, ctx.dp(10) + b)
            ins
        }
        d.setContentView(corps)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        d.show()
    }
}
