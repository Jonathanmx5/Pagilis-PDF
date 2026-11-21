// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import fr.pagilis.pdf.Theme.dp

/**
 * Fenêtre unique de Pagilis PDF : barre du haut (document, Annuler, Enregistrer,
 * Partager), barre d'outils, panneau de l'outil choisi, aperçu de la page.
 * Sur tablette, outils à gauche et panneau à côté de la page ; sur téléphone,
 * outils en bas, panneau repliable sous la page (au plus la moitié de l'écran),
 * Ouvrir et Partager dans le menu ⋮.
 */
class MainActivity : Activity() {
    companion object {
        const val OUVRIR = 1
        const val ENREGISTRER = 2
        const val CREER_FICHIER = 3   // fusion, extraction : écrire des octets préparés
        const val DOSSIER = 4         // découpe
        const val AJOUTER_PDF = 5     // fusion
        const val CHOISIR_IMAGE = 6   // signature depuis une image
        const val INSERER_PDF = 7     // pages
        const val PHOTO = 8           // scanner : appareil photo
        const val IMAGES_SCAN = 9     // scanner : images de la galerie
        const val IMPORTER_SIGNATURE = 10 // « Mes signatures » : tampon ou signature scannée
    }

    private val scanner by lazy { Scanner(this) }

    var copie: CopiePdf? = null
        private set
    var nom = "document.pdf"
        private set
    private var source: Uri? = null
    var occupe = false
        private set

    lateinit var vue: VuePage
        private set
    private lateinit var etiquetteFichier: TextView
    private lateinit var pastille: TextView
    private lateinit var boutonAnnuler: View
    private lateinit var boutonEnregistrer: View
    private var boutonPartager: View? = null
    private lateinit var roue: ProgressBar
    private lateinit var etat: TextView
    private lateinit var panneau: LinearLayout
    private lateinit var conteneurPanneau: View
    private lateinit var accueil: LinearLayout
    private lateinit var accueilDefile: ScrollView
    private lateinit var zonePage: FrameLayout
    private lateinit var navigation: TextView
    private val boutonsOutils = mutableMapOf<Outil, View>()
    lateinit var outils: Map<Outil, OutilPanneau>
        private set
    var outilActif = Outil.LIRE
        private set
    /** Disposition large (tablette, ou téléphone à l'horizontale) : outils à gauche, panneau à côté de la page. */
    val tablette get() = resources.configuration.screenWidthDp >= 600
    private var dispositionLarge = false

    /** Le téléphone tourne : on refait la fenêtre dans l'autre disposition, document et outil gardés. */
    override fun onConfigurationChanged(n: android.content.res.Configuration) {
        super.onConfigurationChanged(n)
        if (tablette == dispositionLarge) return
        val page = vue.index
        construire()
        afficherAccueil()
        copie?.let { vue.montrer(it, page) }
        rafraichirPanneau()
        majEtat()
        majRecherche()
        majChamps()
    }

    // ------------------------------------------------------------- formulaires

    private var champs: List<CopiePdf.Champ> = emptyList()

    /** Les cases du formulaire de la page affichée, encadrées en bleu en mode Lire. */
    fun majChamps() {
        val c = copie
        if (c == null) { champs = emptyList(); posees = emptyList(); majReperes(); return }
        val i = vue.index
        Moteur.lancer({ (if (c.aFormulaire) c.champs(i) else emptyList()) to c.imagesPosees(i) }) { (l, p) ->
            if (vue.index == i) { champs = l; posees = p; majReperes() }
        }
    }

    /** Signatures et tampons posés sur la page affichée : on peut les faire glisser dans Signer. */
    var posees: List<CopiePdf.Posee> = emptyList()
        private set

    private fun majReperes() {
        val l = mutableListOf<VuePage.Repere>()
        for (ch in champs) {
            val signature = ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_SIGNATURE
            // Les cases de signature vides se voient toujours, avec leur nom.
            if (signature && ch.valeur.isEmpty())
                l += VuePage.Repere(ch.x0, ch.y0, ch.x1, ch.y1, Theme.ACCENT, false, "Signer ici : ${ch.nom}".trimEnd(' ', ':'))
            else if (!ch.lectureSeule && ch.type != com.artifex.mupdf.fitz.PDFWidget.TYPE_BUTTON && outilActif == Outil.LIRE)
                l += VuePage.Repere(ch.x0, ch.y0, ch.x1, ch.y1, Theme.ACCENT, false)
        }
        trouves.forEachIndexed { k, t ->
            if (t.page == vue.index) for (r in t.rects)
                l += VuePage.Repere(r[0], r[1], r[2], r[3], if (k == trouve) Color.rgb(255, 140, 0) else Theme.AVERTISSEMENT, true)
        }
        vue.reperes = l
    }

    /** Toucher en mode Lire : remplir la case du formulaire sous le doigt. */
    private fun toucherChamp(x: Float, y: Float): Boolean {
        val ch = champs.lastOrNull { it.contient(x, y) } ?: return false
        val i = vue.index
        when {
            ch.lectureSeule -> statut("Cette case est en lecture seule.")
            ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_CHECKBOX || ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_RADIOBUTTON ->
                retoucher("Case…", { it.basculerChamp(i, ch.numero) }) { statut("Case modifiée.") }
            ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_COMBOBOX || ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_LISTBOX -> {
                if (ch.options.isEmpty()) return true
                AlertDialog.Builder(this).setTitle(ch.nom.ifBlank { "Choisir" })
                    .setItems(ch.options.toTypedArray()) { _, k ->
                        val v = ch.options[k]
                        retoucher("Choix…", { it.remplirChamp(i, ch.numero, v) }) { statut("« $v » choisi.") }
                    }.setNegativeButton("Annuler", null).show()
            }
            ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_TEXT -> {
                val saisie = EditText(this).apply {
                    setText(ch.valeur); setSelection(ch.valeur.length)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        (if (ch.multiligne) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                }
                val d = AlertDialog.Builder(this).setTitle(ch.nom.ifBlank { "Remplir la case" }).setView(saisie)
                    .setPositiveButton("OK") { _, _ ->
                        val v = saisie.text.toString()
                        retoucher("Remplissage…", { it.remplirChamp(i, ch.numero, v) }) { ok ->
                            statut(if (ok) "Case remplie." else "Valeur refusée par le formulaire.")
                        }
                    }.setNegativeButton("Annuler", null).create()
                d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                d.show()
            }
            ch.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_SIGNATURE -> signerCase(ch, null)
            else -> return false
        }
        return true
    }

    /** La case de signature sous le doigt, sur la page affichée. */
    fun caseSignatureEn(x: Float, y: Float): CopiePdf.Champ? =
        champs.lastOrNull { it.type == com.artifex.mupdf.fitz.PDFWidget.TYPE_SIGNATURE && !it.lectureSeule && it.contient(x, y) }

    /** Signer dans une case : avec l'image donnée, sinon on demande laquelle parmi les signatures gardées. */
    fun signerCase(ch: CopiePdf.Champ, image: java.io.File?) {
        val i = vue.index
        if (image == null) return Signature.choisir(this, ch.nom.ifBlank { "Signer ici" }) { f -> signerCase(ch, f) }
        retoucher("Signature…", { it.signerZone(i, ch.numero, image.path) }) {
            if (outilActif == Outil.SIGNER) rafraichirPanneau()
            statut("Signé dans la case « ${ch.nom} ». Annuler pour revenir en arrière, Enregistrer pour garder.")
        }
    }

    // ------------------------------------------------------------- recherche

    private var trouves: List<CopiePdf.Trouve> = emptyList()
    private var trouve = 0
    private var cherche = ""
    private lateinit var barreRecherche: LinearLayout
    private lateinit var texteRecherche: TextView

    fun rechercher() {
        val c = copie ?: return statut("Ouvrez d'abord un PDF.")
        val saisie = EditText(this).apply { hint = "Mot ou expression"; setText(cherche); setSingleLine() }
        val d = AlertDialog.Builder(this).setTitle("Rechercher").setView(saisie)
            .setPositiveButton("Rechercher") { _, _ ->
                val t = saisie.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                travailler("Recherche…", { c.rechercher(t) }) { l ->
                    cherche = t; trouves = l; trouve = 0
                    if (l.isEmpty()) statut("« $t » : introuvable (un scan n'a pas de texte).") else vue.aller(l[0].page)
                    majRecherche()
                }
            }.setNegativeButton("Annuler", null).create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun allerTrouve(sens: Int) {
        if (trouves.isEmpty()) return
        trouve = (trouve + sens + trouves.size) % trouves.size
        vue.aller(trouves[trouve].page)
        majRecherche()
    }

    private fun majRecherche() {
        barreRecherche.visibility = if (trouves.isEmpty()) View.GONE else View.VISIBLE
        texteRecherche.text = "« $cherche »  ${trouve + 1} / ${trouves.size}"
        majReperes()
    }

    private fun fermerRecherche() { trouves = emptyList(); majRecherche() }

    private fun barreDeRecherche(): View {
        barreRecherche = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.fond(Theme.SURFACE, dp(24f), Theme.BORDURE, dp(1))
            setPadding(dp(14), 0, dp(4), 0)
            elevation = dp(4f)
            visibility = View.GONE
        }
        texteRecherche = Theme.texte(this, "", Theme.TEXTE, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        barreRecherche.addView(texteRecherche, LinearLayout.LayoutParams(0, -2, 1f))
        for ((t, f) in listOf<Pair<String, () -> Unit>>("‹" to { allerTrouve(-1) }, "›" to { allerTrouve(1) }, "✕" to { fermerRecherche() })) {
            barreRecherche.addView(Theme.bouton(this, t, Theme.Style.DISCRET) { f() }.apply { textSize = 18f; setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        return barreRecherche
    }

    /** Octets à écrire quand l'utilisateur aura choisi le fichier de destination. */
    private var aEcrire: ByteArray? = null
    private var apresEcriture: (Uri) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Instantanés d'annulation laissés par une fermeture brutale : plus aucun document ne les utilise.
        cacheDir.listFiles { f -> f.name.startsWith("annul_") }?.forEach { it.delete() }
        window.statusBarColor = Theme.SURFACE
        window.navigationBarColor = Theme.SURFACE
        construire()
        outils = Outil.entries.associateWith { it.creer(this) }
        choisirOutil(Outil.LIRE)
        afficherAccueil()
        majEtat()
        traiterIntention(intent)
        chercherMiseAJour()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        traiterIntention(intent)
    }

    /** « Ouvrir avec Pagilis PDF » ou « Partager vers » depuis une autre application. */
    private fun traiterIntention(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        confirmerAbandon { ouvrirUri(uri) }
    }

    // ------------------------------------------------------------- construction

    private fun construire() {
        dispositionLarge = tablette
        boutonsOutils.clear()
        boutonPartager = null
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.FOND) }
        racine.addView(barreHaut(), LinearLayout.LayoutParams(-1, dp(if (tablette) 60 else 56)))

        zonePage = FrameLayout(this)
        vue = VuePage(this)
        vue.pageChangee = { majNavigation(); outils[outilActif]?.pageChangee(); champs = emptyList(); posees = emptyList(); majChamps() }
        vue.tape = { x, y -> outilActif == Outil.LIRE && toucherChamp(x, y) }
        zonePage.addView(vue)
        zonePage.addView(barreNavigation(), FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(12) })
        zonePage.addView(barreDeRecherche(), FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP)
            .apply { setMargins(dp(12), dp(10), dp(12), 0) })
        accueil = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        accueilDefile = ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(Theme.DOCUMENT)
            addView(accueil, FrameLayout.LayoutParams(-1, -2))
        }
        zonePage.addView(accueilDefile, FrameLayout.LayoutParams(-1, -1))

        panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (tablette) setPadding(dp(20), dp(16), dp(20), dp(20)) else setPadding(dp(16), dp(6), dp(16), dp(12))
        }
        val defile = PanneauBorne(this, if (tablette) 1f else 0.42f).apply { addView(panneau); setBackgroundColor(Theme.SURFACE) }
        conteneurPanneau = defile

        val corps = LinearLayout(this)
        if (tablette) {
            corps.orientation = LinearLayout.HORIZONTAL
            // Défilante : un téléphone à l'horizontale n'a pas la hauteur des sept outils.
            corps.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false; setBackgroundColor(Theme.SURFACE)
                addView(barreOutils(vertical = true))
            }, LinearLayout.LayoutParams(dp(96), -1))
            corps.addView(defile, LinearLayout.LayoutParams(dp(340), -1))
            corps.addView(zonePage, LinearLayout.LayoutParams(0, -1, 1f))
            racine.addView(corps, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            corps.orientation = LinearLayout.VERTICAL
            corps.addView(zonePage, LinearLayout.LayoutParams(-1, 0, 1f))
            corps.addView(defile, LinearLayout.LayoutParams(-1, -2))
            racine.addView(corps, LinearLayout.LayoutParams(-1, 0, 1f))
            racine.addView(barreOutils(vertical = false), LinearLayout.LayoutParams(-1, dp(64)))
        }
        etat = Theme.texte(this, "Prêt", Theme.TEXTE_2, 13f).apply {
            setPadding(dp(16), dp(6), dp(16), dp(6)); setBackgroundColor(Theme.SURFACE)
        }
        racine.addView(etat, LinearLayout.LayoutParams(-1, -2))
        // Android 15 dessine sous les barres système : on laisse leur place (et celle du clavier).
        racine.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(racine)
    }

    private fun barreHaut(): View {
        val b = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(if (tablette) 16 else 12), 0, dp(if (tablette) 8 else 2), 0)
        }
        val logo = if (tablette) 32 else 28
        b.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(logo), dp(logo)))
        if (tablette) b.addView(Theme.titre(this, "Pagilis PDF", 18f).apply { setPadding(dp(10), 0, dp(16), 0) })
        etiquetteFichier = Theme.texte(this, if (tablette) "Aucun fichier ouvert" else "Aucun PDF", Theme.TEXTE, 15f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            if (!tablette) setPadding(dp(10), 0, dp(4), 0)
        }
        b.addView(etiquetteFichier, LinearLayout.LayoutParams(0, -2, 1f))
        pastille = TextView(this).apply {
            textSize = 12f; setTextColor(Theme.AVERTISSEMENT); typeface = Typeface.DEFAULT_BOLD
            if (tablette) {
                text = "Non enregistré"
                background = Theme.fond(Color.parseColor("#3a3220"), dp(6f)); setPadding(dp(8), dp(3), dp(8), dp(3))
            } else {
                text = "●"; contentDescription = "Non enregistré"   // téléphone : un point orange suffit
            }
            visibility = View.GONE
        }
        b.addView(pastille, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(if (tablette) 8 else 4) })
        roue = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        b.addView(roue, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        if (tablette) {
            boutonAnnuler = Theme.bouton(this, "↶ Annuler", Theme.Style.DISCRET) { annuler() }
            b.addView(boutonAnnuler)
            b.addView(Theme.bouton(this, "Ouvrir", Theme.Style.SECONDAIRE) { confirmerAbandon { choisirFichier() } },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            boutonPartager = Theme.bouton(this, "Partager", Theme.Style.SECONDAIRE) { partager() }
            b.addView(boutonPartager, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            boutonEnregistrer = Theme.bouton(this, "Enregistrer", Theme.Style.PRINCIPAL) { enregistrer() }
            b.addView(boutonEnregistrer, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            val menu = Theme.bouton(this, "⋮", Theme.Style.DISCRET) {}.apply {
                textSize = 22f; setPadding(0, 0, 0, 0); contentDescription = "Plus"
            }
            menu.setOnClickListener { menuPlus(menu) }
            b.addView(menu, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginStart = dp(2) })
        } else {
            // Téléphone : Annuler en icône, Enregistrer visible, Ouvrir et Partager dans le menu ⋮.
            boutonAnnuler = Theme.bouton(this, "↶", Theme.Style.DISCRET) { annuler() }.apply {
                textSize = 22f; setPadding(0, 0, 0, 0); contentDescription = "Annuler"
            }
            b.addView(boutonAnnuler, LinearLayout.LayoutParams(dp(44), dp(44)))
            boutonEnregistrer = Theme.bouton(this, "Enregistrer", Theme.Style.PRINCIPAL) { enregistrer() }.apply {
                textSize = 14f; setPadding(dp(12), 0, dp(12), 0)
            }
            b.addView(boutonEnregistrer, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(4) })
            val menu = Theme.bouton(this, "⋮", Theme.Style.DISCRET) {}.apply {
                textSize = 22f; setPadding(0, 0, 0, 0); contentDescription = "Plus"
            }
            menu.setOnClickListener { menuPlus(menu) }
            b.addView(menu, LinearLayout.LayoutParams(dp(40), dp(44)))
        }
        return b
    }

    /** Menu ⋮ : sur téléphone, Ouvrir et Partager y sont aussi. */
    private fun menuPlus(ancre: View) {
        val m = android.widget.PopupMenu(this, ancre)
        if (!tablette) {
            m.menu.add(0, 1, 0, "Ouvrir un PDF")
            m.menu.add(0, 2, 1, "Partager").isEnabled = copie != null && !occupe
        }
        m.menu.add(0, 3, 2, "Scanner un document")
        m.menu.add(0, 4, 3, "Rechercher dans le PDF").isEnabled = copie != null && !occupe
        m.menu.add(0, 5, 4, "Copies de secours").isEnabled = !occupe
        m.menu.add(0, 6, 5, "Mes signatures")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> confirmerAbandon { choisirFichier() }
                2 -> partager()
                3 -> confirmerAbandon { scanner.commencer() }
                4 -> rechercher()
                5 -> copiesDeSecours()
                6 -> mesSignatures()
            }
            true
        }
        m.show()
    }

    private fun barreOutils(vertical: Boolean): View {
        val b = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.SURFACE)
            if (vertical) setPadding(dp(6), dp(10), dp(6), dp(6)) else setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        for (o in Outil.entries) {
            val bouton = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (vertical) setPadding(dp(4), dp(8), dp(4), dp(8)) else setPadding(dp(1), dp(2), dp(1), dp(2))
                addView(TextView(context).apply { text = o.symbole; textSize = if (vertical) 22f else 20f; gravity = Gravity.CENTER; setTextColor(Theme.TEXTE) })
                addView(TextView(context).apply {
                    text = if (vertical) o.libelle else o.court; textSize = if (vertical) 12f else 11f; gravity = Gravity.CENTER; setTextColor(Theme.TEXTE_2)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                setOnClickListener { choisirOutil(o) }
            }
            boutonsOutils[o] = bouton
            b.addView(bouton, if (vertical) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            else LinearLayout.LayoutParams(0, -1, 1f))
        }
        return b
    }

    private fun barreNavigation(): View {
        val b = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.fond(Theme.SURFACE, dp(24f), Theme.BORDURE, dp(1))
            setPadding(dp(6), 0, dp(6), 0)
            elevation = dp(4f)
        }
        b.addView(Theme.bouton(this, "‹", Theme.Style.DISCRET) { vue.aller(vue.index - 1) }.apply { textSize = 22f })
        navigation = Theme.texte(this, "", Theme.TEXTE, 15f).apply { gravity = Gravity.CENTER; minWidth = dp(70) }
        navigation.setOnClickListener { demanderPage() }
        b.addView(navigation)
        b.addView(Theme.bouton(this, "›", Theme.Style.DISCRET) { vue.aller(vue.index + 1) }.apply { textSize = 22f })
        return b
    }

    // ------------------------------------------------------------- mise à jour

    val versionInstallee: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    }
    private var nouveaute: MiseAJour.Publication? = null
    private var boutonMiseAJour: android.widget.Button? = null

    /** Au démarrage, sans gêner : si une version plus récente existe, l'accueil le dit. */
    private fun chercherMiseAJour() {
        Thread {
            val n = MiseAJour.chercher(versionInstallee) ?: return@Thread
            runOnUiThread { nouveaute = n; afficherAccueil() }
        }.start()
    }

    private fun bandeauMiseAJour(n: MiseAJour.Publication): View {
        val carte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.fond(Theme.ACCENT_DOUX, dp(14f), Theme.ACCENT, dp(1))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        carte.addView(Theme.titre(this, "Pagilis PDF ${n.version} est disponible", 17f))
        val notes = n.notes.lines().map { it.trim().removePrefix("- ").removePrefix("* ") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.take(4).joinToString("\n") { "• $it" }
        if (notes.isNotEmpty()) carte.addView(Theme.texte(this, notes, Theme.TEXTE_2, 13f).apply { setPadding(0, dp(6), 0, 0) })
        val ligne = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0)
            if (!tablette) orientation = LinearLayout.VERTICAL
        }
        ligne.addView(Theme.texte(this, "Vous avez la version $versionInstallee.", Theme.TEXTE_2, 13f),
            if (tablette) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        val bouton = Theme.bouton(this, "Mettre à jour", Theme.Style.PRINCIPAL) { mettreAJour(n) }
        boutonMiseAJour = bouton
        ligne.addView(bouton, if (tablette) LinearLayout.LayoutParams(-2, -2)
            else LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        carte.addView(ligne)
        return carte
    }

    private fun mettreAJour(n: MiseAJour.Publication) {
        if (!MiseAJour.peutInstaller(this)) {
            AlertDialog.Builder(this).setTitle("Autoriser la mise à jour")
                .setMessage("Android va vous demander d'autoriser Pagilis PDF à installer des applications. " +
                    "Activez l'autorisation, revenez ici, puis touchez à nouveau « Mettre à jour ».")
                .setPositiveButton("Continuer") { _, _ -> MiseAJour.demanderAutorisation(this) }
                .setNegativeButton("Annuler", null).show()
            return
        }
        val bouton = boutonMiseAJour
        bouton?.let { Theme.activer(it, false) }
        Thread {
            try {
                val apk = MiseAJour.telecharger(n, Partage.dossier(this)) { pc ->
                    runOnUiThread { bouton?.text = "Téléchargement… $pc %" }
                }
                runOnUiThread {
                    bouton?.text = "Mettre à jour"
                    bouton?.let { Theme.activer(it, true) }
                    MiseAJour.installer(this, apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    bouton?.text = "Mettre à jour"
                    bouton?.let { Theme.activer(it, true) }
                    erreur(if (e is ErreurPdf) e else ErreurPdf("Téléchargement impossible. Vérifiez la connexion, puis réessayez."))
                }
            }
        }.start()
    }

    private fun afficherAccueil() {
        accueil.removeAllViews()
        accueilDefile.visibility = if (copie == null) View.VISIBLE else View.GONE
        if (copie != null) return
        // Largeurs bornées à l'écran : sur téléphone, rien ne déborde.
        val dispo = resources.configuration.screenWidthDp - 32
        fun large(l: Int) = dp(minOf(l, dispo))
        nouveaute?.let { accueil.addView(bandeauMiseAJour(it), LinearLayout.LayoutParams(large(520), -2).apply { bottomMargin = dp(if (tablette) 36 else 24) }) }
        val icone = if (tablette) 96 else 72
        accueil.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(icone), dp(icone)))
        accueil.addView(Theme.titre(this, "Ouvrez un PDF pour commencer", if (tablette) 22f else 20f).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
        accueil.addView(Theme.texte(this, "Signez, remplissez, annotez, scannez, réorganisez les pages.").apply { gravity = Gravity.CENTER })
        accueil.addView(Theme.bouton(this, "Ouvrir un PDF", Theme.Style.PRINCIPAL) { choisirFichier() },
            LinearLayout.LayoutParams(large(240), dp(52)).apply { topMargin = dp(22) })
        accueil.addView(Theme.bouton(this, "📷  Scanner un document", Theme.Style.SECONDAIRE) { scanner.commencer() },
            LinearLayout.LayoutParams(large(240), dp(52)).apply { topMargin = dp(10) })
        accueil.addView(Theme.bouton(this, "✒️  Mes signatures", Theme.Style.SECONDAIRE) { mesSignatures() },
            LinearLayout.LayoutParams(large(240), dp(52)).apply { topMargin = dp(10) })
        val recents = Recents.liste(this)
        if (recents.isNotEmpty()) {
            accueil.addView(Theme.titre(this, "Récents", 15f).apply { setPadding(0, dp(30), 0, dp(6)) },
                LinearLayout.LayoutParams(large(420), -2))
            for ((uri, n) in recents.take(6)) {
                accueil.addView(Theme.bouton(this, "📄  $n", Theme.Style.DISCRET) { ouvrirUri(uri) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(large(420), dp(46)))
            }
        }
        accueil.addView(Theme.texte(this, "Version $versionInstallee", Theme.TEXTE_DISCRET, 12f).apply { setPadding(0, dp(28), 0, 0); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2))
    }

    // ------------------------------------------------------------- outils

    private var replie = false
    private var reconstruire = false

    /** Téléphone : cache ou montre le panneau de l'outil ; l'outil reste actif sur la page. */
    private fun replier(r: Boolean) {
        replie = r
        conteneurPanneau.visibility = if (r) View.GONE else View.VISIBLE
        if (r) statut("Touchez « ${outilActif.libelle} » en bas pour rouvrir le panneau.")
    }

    fun choisirOutil(o: Outil) {
        if (o.exigeDocument && copie == null) { statut("Ouvrez d'abord un PDF."); return }
        // Téléphone : toucher à nouveau l'outil actif replie ou rouvre son panneau.
        if (!tablette && !reconstruire && o == outilActif && o != Outil.LIRE && copie != null) {
            replier(!replie); return
        }
        replie = false
        if (!reconstruire || o != outilActif) outils[outilActif]?.quitter()
        outilActif = o
        boutonsOutils.forEach { (outil, v) ->
            v.background = if (outil == o) Theme.fond(Theme.ACCENT_DOUX, dp(10f)) else null
        }
        panneau.removeAllViews()
        val p = outils.getValue(o)
        if (tablette) panneau.addView(Theme.titre(this, o.titre, 20f))
        else {
            // Téléphone : le panneau se replie pour laisser toute la place à la page.
            val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            tete.addView(Theme.titre(this, o.titre, 18f), LinearLayout.LayoutParams(0, -2, 1f))
            if (o != Outil.LIRE && copie != null)
                tete.addView(Theme.bouton(this, "▾ Réduire", Theme.Style.DISCRET) { replier(true) }.apply { textSize = 13f })
            panneau.addView(tete)
        }
        // Téléphone : l'explication passe dans la ligne d'état, la page garde la place.
        if (tablette) panneau.addView(Theme.texte(this, o.description).apply { setPadding(0, dp(4), 0, dp(10)) })
        else if (copie != null || o == Outil.FUSION) statut(o.description)
        p.construire(panneau)
        // En lecture, la page prend toute la place une fois le document ouvert.
        conteneurPanneau.visibility = if (o == Outil.LIRE && (copie != null || !tablette)) View.GONE else View.VISIBLE
        vue.interaction = p.interaction()
        p.montre()
        majReperes()
    }

    /** Reconstruit le panneau de l'outil actif sans le faire remonter tout en haut. */
    fun rafraichirPanneau() {
        val defile = panneau.parent as? ScrollView
        val position = defile?.scrollY ?: 0
        reconstruire = true
        try { choisirOutil(outilActif) } finally { reconstruire = false }
        defile?.post { defile.scrollTo(0, position) }
    }

    // ------------------------------------------------------------- état

    fun statut(t: String) { etat.text = t }
    fun erreur(e: Throwable) {
        val message = when (e) {
            is ErreurPdf -> e.message
            is MotDePasseRequis -> "Ce PDF est protégé par un mot de passe."
            else -> "Opération impossible : ${e.message ?: e.javaClass.simpleName}"
        }
        AlertDialog.Builder(this).setTitle("Pagilis PDF").setMessage(message).setPositiveButton("OK", null).show()
        statut(message ?: "")
    }

    fun majEtat() {
        val c = copie
        etiquetteFichier.text = if (c != null) nom else if (tablette) "Aucun fichier ouvert" else "Aucun PDF"
        pastille.visibility = if (c?.modifie == true) View.VISIBLE else View.GONE
        Theme.activer(boutonAnnuler, c?.peutAnnuler == true && !occupe)
        Theme.activer(boutonEnregistrer, c != null && !occupe)
        boutonPartager?.let { Theme.activer(it, c != null && !occupe) }
        majNavigation()
    }

    private fun majNavigation() {
        val c = copie
        navigation.text = if (c == null) "" else "${vue.index + 1} / ${c.nombrePages}"
        (navigation.parent as View).visibility = if (c == null) View.GONE else View.VISIBLE
    }

    /** Exécute un travail sur le fil MuPDF, roue visible ; fin() sur l'interface. */
    fun <T> travailler(message: String, travail: () -> T, surErreur: (Throwable) -> Boolean = { false },
                       fin: (T) -> Unit) {
        if (occupe) return
        occupe = true
        roue.visibility = View.VISIBLE
        statut(message)
        majEtat()
        Moteur.lancer(travail, { e ->
            occupe = false; roue.visibility = View.GONE; majEtat(); vue.rendre()
            if (!surErreur(e)) erreur(e)
        }) { r ->
            occupe = false; roue.visibility = View.GONE
            fin(r)
            majEtat()
        }
    }

    /** Une retouche de la copie de travail : appliquée, puis l'aperçu et l'outil suivent. */
    fun <T> retoucher(message: String, action: (CopiePdf) -> T, fin: (T) -> Unit = {}) {
        val c = copie ?: return
        travailler(message, { action(c) }) { r ->
            vue.montrer(c, vue.index, garderZoom = true)
            outils[outilActif]?.documentChange()
            majChamps()
            fin(r)
        }
    }

    private fun annuler() {
        val c = copie ?: return
        travailler("Annulation…", { c.annuler() }) {
            vue.montrer(c, vue.index, garderZoom = true)
            outils[outilActif]?.documentChange()
            majChamps()
            statut("Dernière retouche annulée.")
        }
    }

    // ------------------------------------------------------------- fichiers

    fun choisirFichier(code: Int = OUVRIR, plusieurs: Boolean = false) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            if (plusieurs) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, code)
    }

    /**
     * « Mes signatures », sans document ouvert : créer une signature, importer un tampon
     * ou une signature scannée, supprimer (toucher une miniature).
     */
    fun mesSignatures() {
        val gardees = Signature.liste(this)
        var dlg: AlertDialog? = null
        val corps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        corps.addView(Theme.texte(this, if (gardees.isEmpty())
            "Aucune signature pour l'instant. Dessinez-la au doigt, ou importez une image (signature scannée, tampon PNG transparent)."
        else "Gardées sur cet appareil, prêtes à poser dans n'importe quel PDF. Touchez-en une pour la supprimer."))
        val grille = android.widget.GridLayout(this).apply { columnCount = 2 }
        for (f in gardees.reversed()) {
            grille.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = android.graphics.drawable.LayerDrawable(arrayOf(Theme.damier(this@MainActivity),
                    Theme.fond(Color.TRANSPARENT, dp(8f), Theme.BORDURE, dp(1))))
                clipToOutline = true
                setImageBitmap(Signature.apercu(f))
                contentDescription = "Signature"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Supprimer cette signature de l'appareil ?")
                        .setPositiveButton("Supprimer") { _, _ -> f.delete(); dlg?.dismiss(); signaturesChangees(); mesSignatures() }
                        .setNegativeButton("Annuler", null).show()
                }
            }, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(64); setMargins(dp(4), dp(10), dp(4), 0)
            })
        }
        corps.addView(grille)
        dlg = AlertDialog.Builder(this).setTitle("Mes signatures")
            .setView(ScrollView(this).apply { addView(corps) })
            .setPositiveButton("＋ Dessiner") { _, _ ->
                Signature.creer(this) { signaturesChangees(); statut("Signature gardée."); mesSignatures() }
            }
            .setNeutralButton("Importer…") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
                }, IMPORTER_SIGNATURE)
            }
            .setNegativeButton("Fermer", null).show()
    }

    /** Le panneau Signer, s'il est ouvert, montre la galerie à jour. */
    private fun signaturesChangees() { if (copie != null && outilActif == Outil.SIGNER) rafraichirPanneau() }

    private fun importerSignature(uri: Uri) {
        val f = Signature.nouveau(this)
        try {
            contentResolver.openInputStream(uri)?.use { e -> f.outputStream().use { e.copyTo(it) } }
        } catch (e: Exception) { f.delete(); return erreur(ErreurPdf("Image illisible.")) }
        if (android.graphics.BitmapFactory.decodeFile(f.path) == null) { f.delete(); return erreur(ErreurPdf("Image illisible.")) }
        signaturesChangees()
        statut("Image gardée dans « Mes signatures ».")
        mesSignatures()
    }

    fun choisirImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
        }, CHOISIR_IMAGE)
    }

    fun nomDe(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"

    fun lire(uri: Uri): ByteArray = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw ErreurPdf("Fichier illisible.")

    fun ouvrirUri(uri: Uri, motDePasse: String? = null) {
        val n = nomDe(uri)
        travailler("Ouverture de $n…", { CopiePdf(lire(uri), cacheDir, motDePasse) },
            surErreur = { e -> if (e is MotDePasseRequis) { demanderMotDePasse(uri, motDePasse != null); true } else false }) { c ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Recents.ajouter(this, uri, n)
            } catch (_: SecurityException) { /* fichier prêté par une autre appli : pas de récent durable */ }
            installer(c, n, uri)
        }
    }

    /** Les versions gardées avant chaque « Remplacer » : on en rouvre une comme un document neuf. */
    private fun copiesDeSecours() {
        val l = Secours.liste(Secours.dossier(filesDir))
        if (l.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Copies de secours")
                .setMessage("Aucune pour l'instant. Chaque fois que vous remplacez un PDF, sa version d'avant est gardée ici (les 10 dernières).")
                .setPositiveButton("OK", null).show()
            return
        }
        val f = java.text.SimpleDateFormat("dd/MM HH'h'mm", java.util.Locale.FRANCE)
        AlertDialog.Builder(this).setTitle("Copies de secours (avant remplacement)")
            .setItems(l.map { "${it.nom}\n${f.format(java.util.Date(it.quand))} · ${taille(it.fichier.length())}" }.toTypedArray()) { _, k ->
                val s = l[k]
                confirmerAbandon {
                    travailler("Ouverture…", { CopiePdf(s.fichier.readBytes(), cacheDir).also { it.marquerNonEnregistre() } }) { c ->
                        val quand = java.text.SimpleDateFormat("dd-MM HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date(s.quand))
                        installer(c, s.nom.removeSuffix(".pdf") + " (secours $quand).pdf", null)
                        statut("Copie de secours ouverte. Enregistrer pour la récupérer.")
                    }
                }
            }.setNegativeButton("Fermer", null).show()
    }

    /** Le PDF du scanner : un document neuf, à enregistrer. */
    fun ouvrirScan(octets: ByteArray) {
        travailler("Ouverture du scan…", { CopiePdf(octets, cacheDir).also { it.marquerNonEnregistre() } }) { c ->
            val n = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH'h'mm", java.util.Locale.FRANCE).format(java.util.Date()) + ".pdf"
            installer(c, n, null)
            statut("Scan prêt (${c.nombrePages} page(s)). Signez-le si besoin, puis Enregistrer.")
        }
    }

    private fun installer(c: CopiePdf, n: String, uri: Uri?) {
        if (copie !== c) copie?.fermer()
        copie = c
        nom = n
        source = uri
        trouves = emptyList(); champs = emptyList()
        afficherAccueil()
        vue.montrer(c, 0)
        outils.values.forEach { it.documentCharge() }
        // Même outil qu'avant : on reconstruit son panneau, sans le replier.
        reconstruire = true
        try { choisirOutil(if (outilActif.exigeDocument || outilActif == Outil.FUSION) outilActif else Outil.LIRE) }
        finally { reconstruire = false }
        majRecherche()
        majChamps()
        statut(if (c.aFormulaire) "$n : formulaire. En mode Lire, touchez une case encadrée pour la remplir."
               else "$n : ${c.nombrePages} page(s).")
    }

    /** Écrit des octets dans un fichier que l'utilisateur choisit (Enregistrer sous). */
    fun ecrireNouveau(octets: ByteArray, nomPropose: String, fin: (Uri) -> Unit = {}) {
        aEcrire = octets
        apresEcriture = fin
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, nomPropose)
        }, CREER_FICHIER)
    }

    private fun enregistrer() {
        val c = copie ?: return
        val remplacable = source?.let { peutEcrire(it) } == true
        val choix = listOfNotNull(
            if (remplacable) "Remplacer « $nom »" else null,
            "Enregistrer une copie…",
            "Copie allégée (pour un e-mail)…",
            "Copie protégée par mot de passe…",
            "Copie verrouillée (une fois signé)…")
        val base = nom.removeSuffix(".pdf")
        AlertDialog.Builder(this).setTitle("Enregistrer").setItems(choix.toTypedArray()) { _, i ->
            when (choix[i]) {
                choix.first().takeIf { remplacable } -> travailler("Enregistrement…", {
                    val uri = source!!
                    val octets = c.enregistrer()
                    // L'ancienne version d'abord, pour pouvoir revenir en arrière quoi qu'il arrive.
                    Secours.garder(Secours.dossier(filesDir), nom, lire(uri))
                    try { ecrire(uri, octets) } catch (e: Exception) { c.marquerNonEnregistre(); throw e }
                }) {
                    statut("Enregistré : $nom. L'ancienne version est gardée (⋮ › Copies de secours).")
                }
                "Copie verrouillée (une fois signé)…" -> travailler("Vérification…", { c.casesVides() }) { vides ->
                    AlertDialog.Builder(this).setTitle("Copie verrouillée")
                        .setMessage((if (vides > 0) "⚠ Il reste $vides case(s) de signature vide(s) : elles ne pourront plus être signées.\n\n" else "") +
                            "Signatures, annotations et cases sont fondues dans la page. Le PDF s'ouvre normalement, " +
                            "s'imprime et se copie, mais Adobe et les principaux lecteurs refusent de le modifier.\n\n" +
                            "Ce n'est pas une protection absolue : un outil spécialisé peut lever ce verrou.")
                        .setPositiveButton("Verrouiller") { _, _ ->
                            travailler("Verrouillage…", { c.verrouiller() }) { octets ->
                                ecrireNouveau(octets, "$base (signé).pdf") { statut("Copie verrouillée enregistrée. Votre document de travail reste modifiable.") }
                            }
                        }.setNegativeButton("Annuler", null).show()
                }
                "Enregistrer une copie…" -> travailler("Préparation…", { c.enregistrer() }) { octets ->
                    ecrireNouveau(octets, if (source == null) nom else "$base (modifié).pdf") { uri ->
                        source = uri; nom = nomDe(uri); Recents.ajouter(this, uri, nom); majEtat()
                        statut("Enregistré : $nom")
                    }
                }
                "Copie allégée (pour un e-mail)…" -> travailler("Allègement des images…", { c.octets().size to c.compresser() }) { (avant, octets) ->
                    ecrireNouveau(octets, "$base (allégé).pdf") {
                        statut("Copie allégée : ${taille(avant.toLong())} → ${taille(octets.size.toLong())}.")
                    }
                }
                else -> demanderNouveauMotDePasse { mdp ->
                    travailler("Chiffrement…", { c.enregistrer(mdp) }) { octets ->
                        ecrireNouveau(octets, "$base (protégé).pdf") { statut("Copie protégée enregistrée. Gardez bien le mot de passe : sans lui, impossible de l'ouvrir.") }
                    }
                }
            }
        }.show()
    }

    private fun taille(o: Long) = if (o >= 1_000_000) String.format(java.util.Locale.FRANCE, "%.1f Mo", o / 1e6)
        else "${(o + 999) / 1000} ko"

    private fun demanderNouveauMotDePasse(suite: (String) -> Unit) {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun champ(h: String) = EditText(this).apply {
            hint = h; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { l.addView(it) }
        val a = champ("Mot de passe")
        val b = champ("Le retaper")
        AlertDialog.Builder(this).setTitle("Protéger par mot de passe")
            .setMessage("Il faudra ce mot de passe pour ouvrir la copie. Il ne peut pas être retrouvé s'il est oublié.")
            .setView(l)
            .setPositiveButton("Protéger") { _, _ ->
                val m = a.text.toString()
                when {
                    m.length < 4 -> erreur(ErreurPdf("Mot de passe trop court (4 caractères au moins)."))
                    m != b.text.toString() -> erreur(ErreurPdf("Les deux mots de passe sont différents."))
                    ',' in m -> erreur(ErreurPdf("Le mot de passe ne peut pas contenir de virgule."))
                    else -> suite(m)
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun peutEcrire(uri: Uri) = try {
        contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true
    } catch (_: Exception) {
        checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun ecrire(uri: Uri, octets: ByteArray) {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(octets) } ?: throw ErreurPdf("Écriture impossible.")
    }

    private fun partager() {
        val c = copie ?: return
        travailler("Préparation du partage…", {
            val f = java.io.File(Partage.dossier(this), nom.ifBlank { "document.pdf" })
            f.writeBytes(c.enregistrer()); f
        }) { f ->
            val envoi = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, Partage.uri(this@MainActivity, f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(envoi, "Partager $nom"))
            statut("Prêt à partager.")
        }
    }

    /** Retouches non enregistrées : on demande avant de les perdre. */
    fun confirmerAbandon(suite: () -> Unit) {
        if (copie?.modifie != true) return suite()
        AlertDialog.Builder(this).setTitle("Modifications non enregistrées")
            .setMessage("Les retouches de « $nom » seront perdues.")
            .setPositiveButton("Continuer sans enregistrer") { _, _ -> suite() }
            .setNegativeButton("Annuler", null).show()
    }

    @Deprecated("Activity sans AndroidX")
    override fun onBackPressed() = confirmerAbandon { @Suppress("DEPRECATION") super.onBackPressed() }

    private fun demanderPage() {
        val c = copie ?: return
        val champ = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1 à ${c.nombrePages}" }
        AlertDialog.Builder(this).setTitle("Aller à la page").setView(champ)
            .setPositiveButton("OK") { _, _ -> champ.text.toString().toIntOrNull()?.let { vue.aller(it - 1) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun demanderMotDePasse(uri: Uri, faux: Boolean) {
        val champ = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("PDF protégé")
            .setMessage(if (faux) "Mot de passe incorrect. Réessayez :" else "« ${nomDe(uri)} » est protégé. Mot de passe :").setView(champ)
            .setPositiveButton("Ouvrir") { _, _ -> ouvrirUri(uri, champ.text.toString()) }
            .setNegativeButton("Annuler", null).show()
    }

    @Deprecated("Activity sans AndroidX")
    override fun onActivityResult(code: Int, resultat: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(code, resultat, data)
        // L'appareil photo écrit dans notre fichier et ne renvoie souvent rien d'autre.
        if (code == PHOTO) return scanner.photoPrise(resultat == RESULT_OK)
        if (resultat != RESULT_OK || data == null) return
        if (code == IMAGES_SCAN) {
            val images = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            return scanner.imagesChoisies(images)
        }
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return
        when (code) {
            OUVRIR -> ouvrirUri(uris[0])
            IMPORTER_SIGNATURE -> importerSignature(uris[0])
            CREER_FICHIER -> {
                val octets = aEcrire ?: return
                aEcrire = null
                travailler("Écriture…", { ecrire(uris[0], octets) }) { apresEcriture(uris[0]) }
            }
            else -> outils[outilActif]?.resultat(code, uris)
        }
    }

}

/** Panneau d'outil qui ne dépasse pas une part de la hauteur de l'écran (téléphone). */
class PanneauBorne(ctx: android.content.Context, private val part: Float) : ScrollView(ctx) {
    override fun onMeasure(largeur: Int, hauteur: Int) {
        if (part >= 1f) return super.onMeasure(largeur, hauteur)
        val max = (resources.displayMetrics.heightPixels * part).toInt()
        val dispo = View.MeasureSpec.getSize(hauteur)
        val borne = if (View.MeasureSpec.getMode(hauteur) == View.MeasureSpec.UNSPECIFIED) max else minOf(max, dispo)
        super.onMeasure(largeur, View.MeasureSpec.makeMeasureSpec(borne, View.MeasureSpec.AT_MOST))
    }
}

/** Les derniers fichiers ouverts (adresse et nom), gardés dans les préférences. */
object Recents {
    private const val CLE = "recents"
    fun liste(ctx: android.content.Context): List<Pair<Uri, String>> =
        ctx.getSharedPreferences("pagilis", 0).getString(CLE, "")!!.lines().filter { '\t' in it }
            .map { Uri.parse(it.substringBefore('\t')) to it.substringAfter('\t') }
            .filter { (uri, _) -> ctx.contentResolver.persistedUriPermissions.any { p -> p.uri == uri } }

    fun ajouter(ctx: android.content.Context, uri: Uri, nom: String) {
        val l = listOf(uri to nom) + liste(ctx).filter { it.first != uri }
        ctx.getSharedPreferences("pagilis", 0).edit()
            .putString(CLE, l.take(10).joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }
}
