// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.AlertDialog
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import fr.pagilis.pdf.Theme.dp
import java.io.File
import kotlin.math.max

enum class Outil(val libelle: String, val court: String, val picto: Picto, val titre: String, val description: String,
                 val exigeDocument: Boolean) {
    LIRE("Lire", "Lire", Picto.LIRE, "Lire", "Glissez pour changer de page, pincez pour zoomer. Touchez une case de formulaire pour la remplir.", false),
    SIGNER("Signer", "Signer", Picto.SIGNER, "Signer", "Posez votre signature (ou un tampon) sur la page, ou créez des cases à signer.", true),
    TEXTE("Texte", "Texte", Picto.TEXTE, "Ajouter du texte", "Une date, une mention : touchez la page à l'endroit voulu.", true),
    ANNOTER("Annoter", "Annoter", Picto.ANNOTER, "Annoter", "Surligner, dessiner, encadrer, laisser une note. Lisibles dans tous les lecteurs PDF.", true),
    PAGES("Pages", "Pages", Picto.PAGES, "Pages", "Touchez les vignettes pour les choisir. Restez appuyé puis glissez pour changer l'ordre.", true),
    FUSION("Fusionner", "Fusion", Picto.FUSION, "Fusionner", "Assembler plusieurs PDF dans l'ordre choisi.", false),
    DECOUPE("Découper", "Découpe", Picto.DECOUPE, "Découper", "Séparer le PDF ouvert en plusieurs fichiers.", true);

    fun creer(a: MainActivity): OutilPanneau = when (this) {
        LIRE -> OutilLire(a)
        SIGNER -> OutilSigner(a)
        TEXTE -> OutilTexte(a)
        ANNOTER -> OutilAnnoter(a)
        PAGES -> OutilPages(a)
        FUSION -> OutilFusion(a)
        DECOUPE -> OutilDecoupe(a)
    }
}

/** Un outil : son panneau, son action sur la page, ses réactions aux changements. */
abstract class OutilPanneau(val a: MainActivity) {
    abstract fun construire(p: LinearLayout)
    open fun interaction(): Interaction? = null
    open fun montre() {}
    open fun quitter() {}
    open fun pageChangee() {}
    open fun documentChange() {}
    open fun documentCharge() {}
    open fun resultat(code: Int, uris: List<Uri>) {}

    protected fun section(p: LinearLayout, titre: String) =
        p.addView(Theme.titre(a, titre, 15f).apply { setPadding(0, a.dp(if (a.tablette) 18 else 8), 0, a.dp(if (a.tablette) 8 else 6)) })

    protected fun ligne(p: LinearLayout, libelle: String, vue: View) {
        val l = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        l.addView(Theme.texte(a, libelle, Theme.TEXTE_2), LinearLayout.LayoutParams(a.dp(92), -2))
        l.addView(vue, LinearLayout.LayoutParams(0, -2, 1f))
        p.addView(l, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(10) })
    }

    protected fun action(p: LinearLayout, texte: String, style: Theme.Style = Theme.Style.PRINCIPAL, f: () -> Unit) =
        Theme.bouton(a, texte, style, f).also { p.addView(it, LinearLayout.LayoutParams(-1, a.dp(50)).apply { topMargin = a.dp(10) }) }

    protected fun curseur(p: LinearLayout, libelle: String, min: Int, max: Int, valeur: Int, suffixe: String,
                          change: (Int) -> Unit): SeekBar {
        val affichage = Theme.texte(a, "$valeur$suffixe", Theme.TEXTE).apply { minWidth = a.dp(56); gravity = Gravity.END }
        val barre = SeekBar(a).apply {
            this.max = max - min
            progress = valeur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, u: Boolean) { affichage.text = "${v + min}$suffixe"; change(v + min) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val l = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        l.addView(Theme.texte(a, libelle, Theme.TEXTE_2), LinearLayout.LayoutParams(a.dp(92), -2))
        l.addView(barre, LinearLayout.LayoutParams(0, -2, 1f))
        l.addView(affichage)
        p.addView(l, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(6) })
        return barre
    }
}

class OutilLire(a: MainActivity) : OutilPanneau(a) {
    override fun construire(p: LinearLayout) {
        if (a.copie == null) action(p, "Ouvrir un PDF") { a.choisirFichier() }
    }
}

// ----------------------------------------------------------------- Signer

class OutilSigner(a: MainActivity) : OutilPanneau(a), Interaction {
    private var image: File? = Signature.enregistree(a)
    private var ratio = image?.let { Signature.ratio(it) } ?: 0.4f
    private var libelle = if (image != null) "Ma signature" else null
    private val miniatures = mutableMapOf<String, Bitmap?>()
    private var largeur = 160f            // points PDF
    private var opacite = 100
    private var centre: FloatArray? = null // centre de l'image sur la page (repère affiché)
    private var prise: FloatArray? = null  // décalage du doigt dans l'image pendant un glisser
    private var apercu: Bitmap? = null

    override fun construire(p: LinearLayout) {
        val gardees = Signature.liste(a)
        // Signature créée ailleurs (en touchant une case) : elle devient la signature choisie.
        if (image?.exists() != true && gardees.isNotEmpty()) {
            image = gardees.last(); ratio = Signature.ratio(image!!); libelle = "Ma signature"; apercu = null
        }
        section(p, if (gardees.isEmpty()) "Image" else "Mes signatures et tampons")
        if (gardees.isNotEmpty()) {
            // Signature complète, initiales… : on touche pour choisir, on reste appuyé pour supprimer.
            val rang = LinearLayout(a)
            for (f in gardees) {
                val img = ImageView(a).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4))
                    background = android.graphics.drawable.LayerDrawable(arrayOf(Theme.damier(a),
                        Theme.fond(android.graphics.Color.TRANSPARENT, a.dp(8f),
                            if (f == image) Theme.ACCENT else Theme.BORDURE, a.dp(if (f == image) 3 else 1))))
                    clipToOutline = true
                    setImageBitmap(miniatures.getOrPut(f.path) { Signature.apercu(f) })
                    contentDescription = "Signature"
                    setOnClickListener { utiliser(f, "Ma signature") }
                    setOnLongClickListener {
                        AlertDialog.Builder(a).setMessage("Supprimer cette signature de l'appareil ?")
                            .setPositiveButton("Supprimer") { _, _ ->
                                f.delete(); miniatures.remove(f.path)
                                if (image == f) { image = Signature.enregistree(a); libelle = if (image != null) "Ma signature" else null; apercu = null }
                                a.rafraichirPanneau()
                            }.setNegativeButton("Annuler", null).show()
                        true
                    }
                }
                rang.addView(img, LinearLayout.LayoutParams(a.dp(104), a.dp(52)).apply { marginEnd = a.dp(8) })
            }
            p.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(rang) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(8) })
        }
        // Une seule rangée : nouvelle signature, tampon, case à signer.
        val l = LinearLayout(a)
        fun b(t: CharSequence, style: Theme.Style, poids: Float, f: () -> Unit) = l.addView(
            Theme.bouton(a, t, style, f).apply { textSize = 14f; maxLines = 1; setPadding(a.dp(8), 0, a.dp(8), 0) },
            LinearLayout.LayoutParams(0, a.dp(48), poids).apply { if (l.childCount > 0) marginStart = a.dp(6) })
        b(if (gardees.isEmpty()) "Créer ma signature" else "＋ Nouvelle", Theme.Style.SECONDAIRE, 1.2f) {
            Signature.creer(a) { f -> utiliser(f, "Ma signature") }
        }
        b("Tampon…", Theme.Style.SECONDAIRE, 1f) { a.choisirImage() }
        b(if (tracer) "✕ Tracé" else Theme.avecIcone(a, Picto.CASE, "Case"), if (tracer) Theme.Style.DANGER else Theme.Style.SECONDAIRE, 1f) {
            tracer = !tracer; cadre = null
            a.rafraichirPanneau()
            a.statut(if (tracer) "Glissez sur la page pour tracer la case de signature (pour vous ou pour l'autre personne)."
                     else "Tracé annulé.")
        }
        p.addView(l)
        if (a.tablette) p.addView(Theme.texte(a,
            (libelle?.let { "$it : touchez la page pour la poser. " } ?: "Créez votre signature, ou choisissez une image (signature scannée, tampon…). ") +
            "Une signature posée se déplace en la faisant glisser. « Case » trace une case à signer, pour vous ou pour la personne qui recevra le PDF."
        ).apply { setPadding(0, a.dp(8), 0, 0) })
        else if (!tracer) a.statut(libelle?.let { "$it : touchez la page pour la poser. Touchez une case encadrée pour y signer." }
            ?: "Créez votre signature, ou choisissez une image.")
        if (a.tablette) section(p, "Placement") else p.addView(View(a), LinearLayout.LayoutParams(1, a.dp(10)))
        curseur(p, "Taille", 40, 400, largeur.toInt(), "") { largeur = it.toFloat(); a.vue.invalidate() }
        curseur(p, "Opacité", 10, 100, opacite, " %") { opacite = it; a.vue.invalidate() }
        action(p, "Poser la signature") { poser() }
    }

    // Signature déjà posée que l'on fait glisser.
    private var deplacee: CopiePdf.Posee? = null
    private var cadreDeplace: FloatArray? = null
    private var origine = floatArrayOf(0f, 0f)

    // Case de signature : en cours de tracé, ou visée par le doigt pour y signer.
    private var tracer = false
    private var cadre: FloatArray? = null
    private var caseVisee: CopiePdf.Champ? = null

    override fun quitter() { tracer = false; cadre = null; caseVisee = null; deplacee = null; cadreDeplace = null }

    private fun nommerCase(r: com.artifex.mupdf.fitz.Rect) {
        val i = a.vue.index
        val saisie = EditText(a).apply {
            setText("Client"); selectAll(); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val d = AlertDialog.Builder(a).setTitle("Qui signe ici ?")
            .setMessage("Ce nom s'affiche dans les lecteurs PDF (Client, Vendeur, Moi…).").setView(saisie)
            .setPositiveButton("Créer la case") { _, _ ->
                val nom = saisie.text.toString()
                a.retoucher("Case de signature…", { it.creerZoneSignature(i, r, nom) }) { ok ->
                    cadre = null; tracer = false
                    a.rafraichirPanneau()
                    a.statut(if (ok) "Case créée. Touchez-la pour y signer, ou envoyez le PDF pour la faire signer."
                             else "Case trop petite : tracez un rectangle plus grand.")
                }
            }
            .setNegativeButton("Annuler") { _, _ -> cadre = null; a.vue.invalidate() }.create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun utiliser(f: File, nom: String) {
        image = f
        ratio = Signature.ratio(f)
        libelle = nom
        apercu = null
        a.rafraichirPanneau()
    }

    override fun resultat(code: Int, uris: List<Uri>) {
        if (code != MainActivity.CHOISIR_IMAGE) return
        // Tampon, signature scannée : gardé avec les signatures, transparence du PNG comprise.
        val f = Signature.nouveau(a)
        a.contentResolver.openInputStream(uris[0])?.use { e -> f.outputStream().use { e.copyTo(it) } }
        if (BitmapFactory.decodeFile(f.path) == null) { f.delete(); return a.erreur(ErreurPdf("Image illisible.")) }
        utiliser(f, a.nomDe(uris[0]))
        a.statut("Image gardée dans « Mes signatures et tampons ». Touchez la page pour la poser.")
    }

    override fun interaction() = this
    override fun pageChangee() { centre = null }

    private fun rect(): RectF? {
        val c = centre ?: return null
        val h = largeur * ratio
        return RectF(c[0] - largeur / 2, c[1] - h / 2, c[0] + largeur / 2, c[1] + h / 2)
    }

    override fun appui(x: Float, y: Float) {
        caseVisee = null
        if (tracer) { cadre = floatArrayOf(x, y, x, y); return }
        val r = rect()
        if (r != null && r.contains(x, y)) { prise = floatArrayOf(x - r.centerX(), y - r.centerY()); return }
        caseVisee = a.caseSignatureEn(x, y)
        if (caseVisee != null) { centre = null; return }
        // Sans aperçu en cours, toucher une signature déjà posée la fait glisser.
        if (centre == null) a.posees.lastOrNull { it.contient(x, y) }?.let { s ->
            deplacee = s; cadreDeplace = floatArrayOf(s.x0, s.y0, s.x1, s.y1); origine = floatArrayOf(x, y)
            return
        }
        if (image == null) { a.statut("Créez d'abord votre signature, ou choisissez une image."); return }
        centre = floatArrayOf(x, y); prise = floatArrayOf(0f, 0f)
    }

    override fun glisse(x: Float, y: Float) {
        cadre?.let { it[2] = x; it[3] = y; return }
        deplacee?.let { s ->
            val dx = x - origine[0]; val dy = y - origine[1]
            cadreDeplace = floatArrayOf(s.x0 + dx, s.y0 + dy, s.x1 + dx, s.y1 + dy)
            return
        }
        val d = prise ?: return
        centre = floatArrayOf(x - d[0], y - d[1])
    }

    override fun relache(x: Float, y: Float) {
        prise = null
        deplacee?.let { s ->
            val r = cadreDeplace!!
            deplacee = null
            if (kotlin.math.abs(r[0] - s.x0) + kotlin.math.abs(r[1] - s.y0) < 3f) {
                cadreDeplace = null
                a.statut("Faites glisser la signature pour la déplacer.")
                return
            }
            val i = a.vue.index
            a.retoucher("Déplacement…", { it.deplacerImage(i, s.nom, com.artifex.mupdf.fitz.Rect(r[0], r[1], r[2], r[3])) }) {
                cadreDeplace = null
                a.statut("Signature déplacée. Annuler pour revenir en arrière.")
            }
            return
        }
        cadre?.let { c ->
            nommerCase(com.artifex.mupdf.fitz.Rect(minOf(c[0], c[2]), minOf(c[1], c[3]), maxOf(c[0], c[2]), maxOf(c[1], c[3])))
            return
        }
        // Toucher une case : on demande toujours laquelle (signature complète, initiales…).
        val ch = caseVisee ?: return
        caseVisee = null
        if (ch.contient(x, y)) { centre = null; a.signerCase(ch, null) }
    }

    override fun dessiner(canvas: Canvas, vue: VuePage) {
        cadreDeplace?.let { c ->
            val z = vue.rectEcran(c[0], c[1], c[2], c[3])
            canvas.drawRect(z, Paint().apply { color = Theme.ACCENT; alpha = 50 })
            canvas.drawRect(z, Paint().apply {
                style = Paint.Style.STROKE; color = Theme.ACCENT; strokeWidth = a.dp(2f)
                pathEffect = DashPathEffect(floatArrayOf(a.dp(6f), a.dp(4f)), 0f)
            })
        }
        cadre?.let { c ->
            canvas.drawRect(vue.rectEcran(minOf(c[0], c[2]), minOf(c[1], c[3]), maxOf(c[0], c[2]), maxOf(c[1], c[3])), Paint().apply {
                style = Paint.Style.STROKE; color = Theme.ACCENT; strokeWidth = a.dp(2f)
                pathEffect = DashPathEffect(floatArrayOf(a.dp(6f), a.dp(4f)), 0f)
            })
        }
        val r = rect() ?: return
        val f = image ?: return
        if (apercu == null) apercu = Signature.apercu(f)
        val zone = vue.rectEcran(r.left, r.top, r.right, r.bottom)
        canvas.drawBitmap(apercu!!, null, zone, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = opacite * 255 / 100 })
        canvas.drawRect(zone, Paint().apply {
            style = Paint.Style.STROKE; color = Theme.ACCENT; strokeWidth = a.dp(2f)
            pathEffect = DashPathEffect(floatArrayOf(a.dp(6f), a.dp(4f)), 0f)
        })
    }

    private fun poser() {
        val f = image ?: return a.statut("Créez d'abord votre signature, ou choisissez une image.")
        val r = rect() ?: return a.statut("Touchez d'abord la page à l'endroit de la signature.")
        val i = a.vue.index
        a.retoucher("Signature…", { it.poserImage(i, com.artifex.mupdf.fitz.Rect(r.left, r.top, r.right, r.bottom), f.path, opacite / 100f) }) {
            centre = null
            a.statut("Signature posée. Faites-la glisser pour la déplacer, Enregistrer pour garder.")
        }
    }
}

// ----------------------------------------------------------------- Texte

class OutilTexte(a: MainActivity) : OutilPanneau(a), Interaction {
    private val couleurs = linkedMapOf("Noir" to floatArrayOf(0f, 0f, 0f), "Bleu" to floatArrayOf(0.08f, 0.2f, 0.55f))
    private var taille = "12"
    private var couleur = "Noir"
    private var marque: FloatArray? = null

    override fun construire(p: LinearLayout) {
        section(p, "Style")
        ligne(p, "Taille", Theme.Segments(a, listOf("10", "12", "14", "18"), taille) { taille = it })
        ligne(p, "Couleur", Theme.Segments(a, couleurs.keys.toList(), couleur) { couleur = it })
        if (a.tablette) p.addView(Theme.texte(a, "Touchez la page à l'endroit du texte, tapez, puis Ajouter.").apply { setPadding(0, a.dp(8), 0, 0) })
        section(p, "Mentions en un geste")
        val aujourdhui = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date())
        val mentions = listOf("Lu et approuvé", "Bon pour accord", "Le $aujourdhui", "Lu et approuvé, le $aujourdhui")
        val grille = GridLayout(a).apply { columnCount = 2 }
        for (m in mentions) {
            grille.addView(Theme.bouton(a, m, if (m == rapide) Theme.Style.PRINCIPAL else Theme.Style.SECONDAIRE) {
                rapide = if (rapide == m) null else m
                a.rafraichirPanneau()
                if (rapide != null) a.statut("« $m » : touchez la page à l'endroit voulu.")
            }.apply { textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(a.dp(8), 0, a.dp(8), 0) },
                GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; height = a.dp(44); setMargins(0, 0, a.dp(6), a.dp(6))
                })
        }
        p.addView(grille, LinearLayout.LayoutParams(-1, -2))
    }

    /** Mention choisie : posée telle quelle au prochain toucher, sans clavier. */
    private var rapide: String? = null

    override fun quitter() { rapide = null }
    override fun interaction() = this
    override fun appui(x: Float, y: Float) { marque = floatArrayOf(x, y) }
    override fun dessiner(canvas: Canvas, vue: VuePage) {
        val m = marque ?: return
        val e = vue.versEcran(m[0], m[1])
        canvas.drawLine(e[0], e[1] - a.dp(14f), e[0], e[1] + a.dp(14f), Paint().apply { color = Theme.ACCENT; strokeWidth = a.dp(2f) })
    }

    override fun relache(x: Float, y: Float) {
        val i = a.vue.index
        rapide?.let { m ->
            val taillePt = taille.toFloat()
            val rvb = couleurs.getValue(couleur)
            a.retoucher("Ajout de la mention…", { it.ajouterTexte(i, x, y, m, taillePt, rvb) }) {
                marque = null; rapide = null
                a.rafraichirPanneau()
                a.statut("« $m » ajouté. Annuler pour revenir en arrière.")
            }
            return
        }
        val champ = EditText(a).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; hint = "Texte à ajouter" }
        val d = AlertDialog.Builder(a).setTitle("Ajouter du texte").setView(champ)
            .setPositiveButton("Ajouter") { _, _ ->
                val t = champ.text.toString()
                val taillePt = taille.toFloat()
                val rvb = couleurs.getValue(couleur)
                a.retoucher("Ajout du texte…", { it.ajouterTexte(i, x, y, t, taillePt, rvb) }) { ajoute ->
                    marque = null
                    a.statut(if (ajoute) "Texte ajouté. Touchez ailleurs pour en ajouter." else "Texte vide : rien n'a été ajouté.")
                }
            }
            .setNegativeButton("Annuler") { _, _ -> marque = null; a.vue.invalidate() }
            .create()
        d.setOnShowListener { champ.requestFocus() }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }
}

// ----------------------------------------------------------------- Annoter

class OutilAnnoter(a: MainActivity) : OutilPanneau(a), Interaction {
    private val couleurs = linkedMapOf(
        "Jaune" to floatArrayOf(1f, 0.85f, 0f), "Vert" to floatArrayOf(0.3f, 0.85f, 0.3f),
        "Rouge" to floatArrayOf(0.85f, 0.1f, 0.1f), "Bleu" to floatArrayOf(0.1f, 0.3f, 0.85f))
    private val modes = listOf("Surligner", "Stylo", "Cadre", "Note", "Gomme")
    private var mode = "Surligner"
    private var couleur = "Jaune"
    private var trait = mutableListOf<FloatArray>()

    override fun construire(p: LinearLayout) {
        p.addView(Theme.Segments(a, modes, mode) { mode = it; trait.clear(); a.statut(aide()) }.apply {
            for (k in 0 until childCount) (getChildAt(k) as TextView).apply { textSize = 13f; setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(10)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.dp(8) })
        ligne(p, "Couleur", Theme.Segments(a, couleurs.keys.toList(), couleur) { couleur = it }.apply {
            for (k in 0 until childCount) (getChildAt(k) as TextView).apply { textSize = 13f; setPadding(a.dp(2), a.dp(10), a.dp(2), a.dp(10)) }
        })
        if (a.tablette) p.addView(Theme.texte(a, aide()))
    }

    private fun aide() = when (mode) {
        "Surligner" -> "Glissez le doigt sur le texte à surligner."
        "Stylo" -> "Dessinez sur la page."
        "Cadre" -> "Glissez pour tracer un rectangle."
        "Note" -> "Touchez la page à l'endroit de la note."
        else -> "Touchez une annotation pour l'effacer."
    }

    override fun montre() { a.statut(aide()) }
    override fun interaction() = this
    override fun pageChangee() { trait.clear() }

    override fun appui(x: Float, y: Float) { trait = mutableListOf(floatArrayOf(x, y)) }
    override fun glisse(x: Float, y: Float) { if (mode != "Note" && mode != "Gomme") trait += floatArrayOf(x, y) }

    override fun relache(x: Float, y: Float) {
        val i = a.vue.index
        val rvb = couleurs.getValue(couleur)
        val t = trait.toList()
        trait.clear()
        if (t.isEmpty()) return
        val d = t.first(); val f = t.last()
        when (mode) {
            "Surligner" -> a.retoucher("Surlignage…", { it.surligner(i, d[0], d[1], f[0], f[1], rvb) }) { ok ->
                a.statut(if (ok) "Texte surligné." else "Pas de texte ici : glissez sur des mots (un scan n'a pas de texte).")
            }
            "Stylo" -> a.retoucher("Dessin…", { it.dessiner(i, listOf(t), rvb, 2f) }) {}
            "Cadre" -> a.retoucher("Cadre…", {
                it.encadrer(i, com.artifex.mupdf.fitz.Rect(minOf(d[0], f[0]), minOf(d[1], f[1]), maxOf(d[0], f[0]), maxOf(d[1], f[1])), rvb)
            }) {}
            "Note" -> {
                val champ = EditText(a).apply { hint = "Votre note"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
                val dlg = AlertDialog.Builder(a).setTitle("Note").setView(champ)
                    .setPositiveButton("Ajouter") { _, _ ->
                        val texte = champ.text.toString()
                        a.retoucher("Note…", { it.noter(i, f[0], f[1], texte) }) { ok -> if (ok) a.statut("Note ajoutée.") }
                    }.setNegativeButton("Annuler", null).create()
                dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                dlg.show()
            }
            "Gomme" -> a.retoucher("Effacement…", { it.effacerAnnotation(i, f[0], f[1]) }) { ok ->
                a.statut(if (ok) "Annotation effacée." else "Aucune annotation à cet endroit.")
            }
        }
    }

    override fun dessiner(canvas: Canvas, vue: VuePage) {
        if (trait.size < 2) return
        val c = couleurs.getValue(couleur)
        val peinture = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())
            style = Paint.Style.STROKE; strokeWidth = a.dp(2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val d = vue.versEcran(trait.first()[0], trait.first()[1]); val f = vue.versEcran(trait.last()[0], trait.last()[1])
        when (mode) {
            "Stylo" -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(d[0], d[1]); for (pt in trait.drop(1)) { val e = vue.versEcran(pt[0], pt[1]); lineTo(e[0], e[1]) }
            }, peinture)
            "Cadre" -> canvas.drawRect(minOf(d[0], f[0]), minOf(d[1], f[1]), maxOf(d[0], f[0]), maxOf(d[1], f[1]), peinture)
            "Surligner" -> canvas.drawRect(minOf(d[0], f[0]), minOf(d[1], f[1]) - a.dp(6f), maxOf(d[0], f[0]), maxOf(d[1], f[1]) + a.dp(6f),
                Paint().apply { color = peinture.color; alpha = 80 })
        }
    }
}

// ----------------------------------------------------------------- Pages

class OutilPages(a: MainActivity) : OutilPanneau(a) {
    private val selection = sortedSetOf<Int>()
    private var grille: GridLayout? = null
    private val vignettes = mutableMapOf<Int, Bitmap>()

    override fun construire(p: LinearLayout) {
        val actions = LinearLayout(a)
        fun b(t: CharSequence, style: Theme.Style = Theme.Style.SECONDAIRE, f: () -> Unit) =
            actions.addView(Theme.bouton(a, t, style, f).apply { textSize = 18f }, LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginEnd = a.dp(6) })
        b("⟲") { pivoter(-90) }
        b("⟳") { pivoter(90) }
        b(Theme.avecIcone(a, Picto.SUPPRIMER, "", Theme.DANGER), Theme.Style.DANGER) { supprimer() }
        p.addView(actions)
        val l2 = LinearLayout(a)
        l2.addView(Theme.bouton(a, "Insérer un PDF…") { a.choisirFichier(MainActivity.INSERER_PDF) }, LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginEnd = a.dp(6) })
        l2.addView(Theme.bouton(a, "Extraire…") { extraire() }, LinearLayout.LayoutParams(0, a.dp(48), 1f))
        p.addView(l2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(6) })
        section(p, "Toutes les pages")
        val g = GridLayout(a).apply { columnCount = if (a.tablette) 2 else 3 }
        grille = g
        p.addView(g)
        remplir()
    }

    override fun documentCharge() { selection.clear(); vignettes.clear() }
    override fun documentChange() { vignettes.clear(); if (a.outilActif == Outil.PAGES) remplir() }

    private fun remplir() {
        val g = grille ?: return
        val c = a.copie ?: return
        g.removeAllViews()
        selection.retainAll((0 until c.nombrePages).toSet())
        // Téléphone : trois vignettes par ligne, à la largeur de l'écran (marges du panneau déduites).
        val largeur = if (a.tablette) a.dp(140)
            else a.dp(minOf(140, (a.resources.configuration.screenWidthDp - 40) / 3 - 12))
        for (i in 0 until c.nombrePages) {
            val cellule = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(4))
                tag = i
            }
            val img = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Theme.DOCUMENT) }
            cellule.addView(img, LinearLayout.LayoutParams(largeur, (largeur * 1.3f).toInt()))
            cellule.addView(Theme.texte(a, "${i + 1}", Theme.TEXTE_2, 12f).apply { gravity = Gravity.CENTER })
            marquer(cellule, i)
            cellule.setOnClickListener {
                if (!selection.remove(i)) selection.add(i)
                marquer(cellule, i)
                a.vue.aller(i)
            }
            cellule.setOnLongClickListener { v ->
                if (i !in selection) { selection.add(i); marquer(cellule, i) }
                v.startDragAndDrop(ClipData.newPlainText("pages", selection.joinToString(",")), View.DragShadowBuilder(v), null, 0)
                true
            }
            cellule.setOnDragListener { v, e -> deposer(v, e, i) }
            g.addView(cellule)
            val dejaLa = vignettes[i]
            if (dejaLa != null) img.setImageBitmap(dejaLa)
            else Moteur.lancer({
                val page = c.doc.loadPage(i)
                val b = page.bounds
                AndroidDrawDevice.drawPage(page, Matrix(largeur / max(b.x1 - b.x0, b.y1 - b.y0)))
            }) { bmp -> vignettes[i] = bmp; img.setImageBitmap(bmp) }
        }
    }

    private fun marquerTout() {
        val g = grille ?: return
        for (k in 0 until g.childCount) marquer(g.getChildAt(k), k)
    }

    private fun marquer(cellule: View, i: Int) {
        cellule.background = if (i in selection) Theme.fond(Theme.ACCENT_DOUX, a.dp(8f), Theme.ACCENT, a.dp(2)) else null
    }

    /** Dépôt d'une vignette glissée : avant la page si on lâche sur sa moitié gauche. */
    private fun deposer(v: View, e: DragEvent, i: Int): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_ENTERED -> {
                v.foreground = BarreInsertion(e.x < v.width / 2, a.dp(4f))
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> v.foreground = null
            DragEvent.ACTION_DROP -> {
                v.foreground = null
                val place = if (e.x < v.width / 2) i else i + 1
                val pages = selection.toList()
                a.retoucher("Déplacement des pages…", { it.placer(pages, place) }) { nouvelles ->
                    if (nouvelles.isNotEmpty()) {
                        selection.clear(); selection.addAll(nouvelles)
                        marquerTout()
                        a.vue.aller(nouvelles[0])
                        a.statut("${pages.size} page(s) déplacée(s).")
                    }
                }
            }
        }
        return true
    }

    private fun choisies(): List<Int>? {
        if (selection.isEmpty()) { a.statut("Touchez d'abord une ou plusieurs vignettes."); return null }
        return selection.toList()
    }

    private fun pivoter(angle: Int) {
        val p = choisies() ?: return
        a.retoucher("Rotation…", { it.pivoter(p, angle) }) { a.statut("${p.size} page(s) pivotée(s).") }
    }

    private fun supprimer() {
        val p = choisies() ?: return
        AlertDialog.Builder(a).setTitle("Supprimer ${p.size} page(s) ?").setMessage("Annuler permet de revenir en arrière.")
            .setPositiveButton("Supprimer") { _, _ ->
                a.retoucher("Suppression…", { it.supprimer(p) }) {
                    selection.clear()
                    marquerTout()
                    a.statut("${p.size} page(s) supprimée(s).")
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun extraire() {
        val p = choisies() ?: return
        val c = a.copie ?: return
        a.travailler("Extraction…", { c.extraire(p) }) { octets ->
            a.ecrireNouveau(octets, a.nom.removeSuffix(".pdf") + "_pages_" + plages(p) + ".pdf") {
                a.statut("${p.size} page(s) extraite(s).")
            }
        }
    }

    override fun resultat(code: Int, uris: List<Uri>) {
        if (code != MainActivity.INSERER_PDF) return
        val apres = selection.lastOrNull() ?: ((a.copie?.nombrePages ?: 1) - 1)
        a.retoucher("Insertion…", { it.inserer(a.lire(uris[0]), apres) }) { nouvelles ->
            selection.clear(); selection.addAll(nouvelles)
            marquerTout()
            a.statut("${nouvelles.size} page(s) insérée(s).")
        }
    }

    companion object {
        /** [0,1,2,4] -> « 1-3,5 » */
        fun plages(p: List<Int>): String {
            val morceaux = mutableListOf<String>()
            var debut = p[0]; var prec = p[0]
            for (i in p.drop(1) + listOf(-2)) {
                if (i == prec + 1) { prec = i; continue }
                morceaux += if (debut == prec) "${debut + 1}" else "${debut + 1}-${prec + 1}"
                debut = i; prec = i
            }
            return morceaux.joinToString(",")
        }
    }
}

/** Barre bleue au bord d'une vignette : là où les pages iront. */
class BarreInsertion(private val gauche: Boolean, private val epaisseur: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint().apply { color = Theme.ACCENT }
    override fun draw(c: Canvas) {
        val b = bounds
        val x = if (gauche) b.left.toFloat() else b.right - epaisseur
        c.drawRect(x, b.top.toFloat(), x + epaisseur, b.bottom.toFloat(), p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

// ----------------------------------------------------------------- Fusionner

class OutilFusion(a: MainActivity) : OutilPanneau(a) {
    private val fichiers = mutableListOf<Pair<Uri, String>>()
    private var choisi: Int? = null
    private var inclureOuvert = true

    override fun construire(p: LinearLayout) {
        if (a.copie != null) {
            p.addView(CheckBox(a).apply {
                text = "Commencer par le document ouvert (${a.nom})"; setTextColor(Theme.TEXTE); isChecked = inclureOuvert
                setOnCheckedChangeListener { _, v -> inclureOuvert = v }
            })
        }
        section(p, "Fichiers à assembler")
        val liste = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Theme.fond(Theme.SURFACE_2, a.dp(8f)); setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4)) }
        if (fichiers.isEmpty()) liste.addView(Theme.texte(a, "Aucun fichier pour l'instant.", Theme.TEXTE_DISCRET).apply { setPadding(a.dp(12), a.dp(16), a.dp(12), a.dp(16)) })
        fichiers.forEachIndexed { i, (_, n) ->
            liste.addView(TextView(a).apply {
                text = "${i + 1}.  $n"; textSize = 15f; setTextColor(Theme.TEXTE); maxLines = 1
                setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
                background = if (i == choisi) Theme.fond(Theme.ACCENT_DOUX, a.dp(6f)) else null
                setOnClickListener { choisi = i; a.rafraichirPanneau() }
            })
        }
        p.addView(liste)
        val l = LinearLayout(a).apply { setPadding(0, a.dp(8), 0, 0) }
        fun b(t: String, f: () -> Unit) = l.addView(Theme.bouton(a, t, Theme.Style.SECONDAIRE, f), LinearLayout.LayoutParams(0, a.dp(48), 1f).apply { marginEnd = a.dp(6) })
        b("Ajouter…") { a.choisirFichier(MainActivity.AJOUTER_PDF, plusieurs = true) }
        b("▲") { deplacer(-1) }
        b("▼") { deplacer(1) }
        b("✕") { choisi?.let { fichiers.removeAt(it); choisi = null; a.rafraichirPanneau() } }
        p.addView(l)
        action(p, "Fusionner") { fusionner() }
    }

    private fun deplacer(sens: Int) {
        val i = choisi ?: return
        val j = i + sens
        if (j !in fichiers.indices) return
        fichiers[i] = fichiers[j].also { fichiers[j] = fichiers[i] }
        choisi = j
        a.rafraichirPanneau()
    }

    override fun resultat(code: Int, uris: List<Uri>) {
        if (code != MainActivity.AJOUTER_PDF) return
        fichiers += uris.map { it to a.nomDe(it) }
        a.rafraichirPanneau()
    }

    private fun fusionner() {
        val c = if (inclureOuvert) a.copie else null
        val liste = fichiers.toList()
        if ((if (c != null) 1 else 0) + liste.size < 2) return a.statut("Ajoutez au moins deux PDF à assembler.")
        a.travailler("Fusion…", {
            val docs = listOfNotNull(c?.let { CopiePdf.ouvrir(it.octets(), null) }) + liste.map { CopiePdf.ouvrir(a.lire(it.first), null) }
            Assemblage.fusionner(docs)
        }) { octets ->
            a.ecrireNouveau(octets, "fusion.pdf") { uri ->
                a.statut("Fusion enregistrée.")
                AlertDialog.Builder(a).setMessage("Ouvrir le PDF fusionné ?")
                    .setPositiveButton("Ouvrir") { _, _ -> a.confirmerAbandon { a.ouvrirUri(uri) } }
                    .setNegativeButton("Plus tard", null).show()
            }
        }
    }
}

// ----------------------------------------------------------------- Découper

class OutilDecoupe(a: MainActivity) : OutilPanneau(a) {
    private var mode = "Une page par fichier"
    private var plages = ""

    override fun construire(p: LinearLayout) {
        val modes = listOf("Une page par fichier", "Selon des plages")
        for (m in modes) {
            p.addView(android.widget.RadioButton(a).apply {
                text = m; setTextColor(Theme.TEXTE); isChecked = m == mode; textSize = 15f
                setOnClickListener { mode = m; a.rafraichirPanneau() }
            })
        }
        if (mode == "Selon des plages") {
            p.addView(EditText(a).apply {
                hint = "1-3, 5, 8-"; setText(plages); setTextColor(Theme.TEXTE); setHintTextColor(Theme.TEXTE_DISCRET)
                inputType = InputType.TYPE_CLASS_TEXT
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { plages = s.toString() }
                    override fun beforeTextChanged(s: CharSequence?, a1: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a1: Int, b: Int, c: Int) {}
                })
            })
            p.addView(Theme.texte(a, "Chaque plage devient un fichier.", Theme.TEXTE_2, 13f))
        }
        p.addView(Theme.texte(a, "Vous choisirez ensuite le dossier où ranger les fichiers.").apply { setPadding(0, a.dp(12), 0, 0) })
        action(p, "Découper") { decouper() }
    }

    private var morceaux: List<List<Int>> = emptyList()

    private fun decouper() {
        val c = a.copie ?: return
        morceaux = try {
            if (mode == "Une page par fichier") (0 until c.nombrePages).map { listOf(it) } else Assemblage.plages(plages, c.nombrePages)
        } catch (e: ErreurPdf) { return a.erreur(e) }
        a.startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.DOSSIER)
    }

    override fun resultat(code: Int, uris: List<Uri>) {
        if (code != MainActivity.DOSSIER) return
        val c = a.copie ?: return
        val arbre = uris[0]
        val base = a.nom.removeSuffix(".pdf")
        val liste = morceaux
        a.travailler("Découpe…", {
            val dossier = DocumentsContract.buildDocumentUriUsingTree(arbre, DocumentsContract.getTreeDocumentId(arbre))
            for (pages in liste) {
                val octets = c.extraire(pages)
                val n = "${base}_${OutilPages.plages(pages)}.pdf"
                val f = DocumentsContract.createDocument(a.contentResolver, dossier, "application/pdf", n)
                    ?: throw ErreurPdf("Impossible de créer « $n » dans ce dossier.")
                a.ecrire(f, octets)
            }
            liste.size
        }) { n -> a.statut("$n fichier(s) créé(s).") }
    }
}
