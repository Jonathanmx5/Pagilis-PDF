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
 * outils en bas et panneau sous la page.
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
    }

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
    private lateinit var boutonPartager: View
    private lateinit var roue: ProgressBar
    private lateinit var etat: TextView
    private lateinit var panneau: LinearLayout
    private lateinit var conteneurPanneau: View
    private lateinit var accueil: LinearLayout
    private lateinit var zonePage: FrameLayout
    private lateinit var navigation: TextView
    private val boutonsOutils = mutableMapOf<Outil, View>()
    lateinit var outils: Map<Outil, OutilPanneau>
        private set
    var outilActif = Outil.LIRE
        private set
    private val tablette get() = resources.configuration.smallestScreenWidthDp >= 600

    /** Octets à écrire quand l'utilisateur aura choisi le fichier de destination. */
    private var aEcrire: ByteArray? = null
    private var apresEcriture: (Uri) -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.SURFACE
        window.navigationBarColor = Theme.SURFACE
        construire()
        outils = Outil.entries.associateWith { it.creer(this) }
        choisirOutil(Outil.LIRE)
        afficherAccueil()
        majEtat()
        traiterIntention(intent)
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
        val racine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.FOND) }
        racine.addView(barreHaut(), LinearLayout.LayoutParams(-1, dp(60)))

        zonePage = FrameLayout(this)
        vue = VuePage(this)
        vue.pageChangee = { majNavigation(); outils[outilActif]?.pageChangee() }
        zonePage.addView(vue)
        zonePage.addView(barreNavigation(), FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(12) })
        accueil = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Theme.DOCUMENT) }
        zonePage.addView(accueil, FrameLayout.LayoutParams(-1, -1))

        panneau = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val defile = ScrollView(this).apply { addView(panneau); setBackgroundColor(Theme.SURFACE) }
        conteneurPanneau = defile

        val corps = LinearLayout(this)
        if (tablette) {
            corps.orientation = LinearLayout.HORIZONTAL
            corps.addView(barreOutils(vertical = true), LinearLayout.LayoutParams(dp(96), -1))
            corps.addView(defile, LinearLayout.LayoutParams(dp(340), -1))
            corps.addView(zonePage, LinearLayout.LayoutParams(0, -1, 1f))
            racine.addView(corps, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            corps.orientation = LinearLayout.VERTICAL
            corps.addView(zonePage, LinearLayout.LayoutParams(-1, 0, 1f))
            corps.addView(defile, LinearLayout.LayoutParams(-1, -2).apply { height = -2 })
            defile.layoutParams.height = -2
            racine.addView(corps, LinearLayout.LayoutParams(-1, 0, 1f))
            racine.addView(barreOutils(vertical = false), LinearLayout.LayoutParams(-1, dp(68)))
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
            setPadding(dp(16), 0, dp(8), 0)
        }
        b.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        b.addView(Theme.titre(this, if (tablette) "Pagilis PDF" else "", 18f).apply { setPadding(dp(10), 0, dp(16), 0) })
        etiquetteFichier = Theme.texte(this, "Aucun fichier ouvert", Theme.TEXTE, 15f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        b.addView(etiquetteFichier, LinearLayout.LayoutParams(0, -2, 1f))
        pastille = TextView(this).apply {
            text = "Non enregistré"; textSize = 12f; setTextColor(Theme.AVERTISSEMENT); typeface = Typeface.DEFAULT_BOLD
            background = Theme.fond(Color.parseColor("#3a3220"), dp(6f)); setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        b.addView(pastille, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        roue = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        b.addView(roue, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        boutonAnnuler = Theme.bouton(this, "↶ Annuler", Theme.Style.DISCRET) { annuler() }
        b.addView(boutonAnnuler)
        b.addView(Theme.bouton(this, "Ouvrir", Theme.Style.SECONDAIRE) { confirmerAbandon { choisirFichier() } },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        boutonPartager = Theme.bouton(this, "Partager", Theme.Style.SECONDAIRE) { partager() }
        b.addView(boutonPartager, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        boutonEnregistrer = Theme.bouton(this, "Enregistrer", Theme.Style.PRINCIPAL) { enregistrer() }
        b.addView(boutonEnregistrer, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        return b
    }

    private fun barreOutils(vertical: Boolean): View {
        val b = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.SURFACE)
            setPadding(dp(6), dp(10), dp(6), dp(6))
        }
        for (o in Outil.entries) {
            val bouton = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(TextView(context).apply { text = o.symbole; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Theme.TEXTE) })
                addView(TextView(context).apply { text = o.libelle; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Theme.TEXTE_2) })
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

    private fun afficherAccueil() {
        accueil.removeAllViews()
        accueil.visibility = if (copie == null) View.VISIBLE else View.GONE
        if (copie != null) return
        accueil.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        accueil.addView(Theme.titre(this, "Ouvrez un PDF pour commencer", 22f).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
        accueil.addView(Theme.texte(this, "Signez, ajoutez du texte, réorganisez les pages, fusionnez, découpez.").apply { gravity = Gravity.CENTER })
        accueil.addView(Theme.bouton(this, "Ouvrir un PDF", Theme.Style.PRINCIPAL) { choisirFichier() },
            LinearLayout.LayoutParams(dp(240), dp(52)).apply { topMargin = dp(22) })
        val recents = Recents.liste(this)
        if (recents.isNotEmpty()) {
            accueil.addView(Theme.titre(this, "Récents", 15f).apply { setPadding(0, dp(30), 0, dp(6)) },
                LinearLayout.LayoutParams(dp(420), -2))
            for ((uri, n) in recents.take(6)) {
                accueil.addView(Theme.bouton(this, "📄  $n", Theme.Style.DISCRET) { ouvrirUri(uri) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(dp(420), dp(46)))
            }
        }
    }

    // ------------------------------------------------------------- outils

    fun choisirOutil(o: Outil) {
        if (o.exigeDocument && copie == null) { statut("Ouvrez d'abord un PDF."); return }
        outils[outilActif]?.quitter()
        outilActif = o
        boutonsOutils.forEach { (outil, v) ->
            v.background = if (outil == o) Theme.fond(Theme.ACCENT_DOUX, dp(10f)) else null
        }
        panneau.removeAllViews()
        val p = outils.getValue(o)
        panneau.addView(Theme.titre(this, o.titre, 20f))
        panneau.addView(Theme.texte(this, o.description).apply { setPadding(0, dp(4), 0, dp(10)) })
        p.construire(panneau)
        // En lecture, la page prend toute la place une fois le document ouvert.
        conteneurPanneau.visibility = if (o == Outil.LIRE && (copie != null || !tablette)) View.GONE else View.VISIBLE
        vue.interaction = p.interaction()
        p.montre()
    }

    fun rafraichirPanneau() = choisirOutil(outilActif)

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
        etiquetteFichier.text = if (c == null) "Aucun fichier ouvert" else nom
        pastille.visibility = if (c?.modifie == true) View.VISIBLE else View.GONE
        Theme.activer(boutonAnnuler, c?.peutAnnuler == true && !occupe)
        Theme.activer(boutonEnregistrer, c != null && !occupe)
        Theme.activer(boutonPartager, c != null && !occupe)
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
            fin(r)
        }
    }

    private fun annuler() {
        val c = copie ?: return
        travailler("Annulation…", { c.annuler() }) {
            vue.montrer(c, vue.index, garderZoom = true)
            outils[outilActif]?.documentChange()
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
            copie = c
            nom = n
            source = uri
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Recents.ajouter(this, uri, n)
            } catch (_: SecurityException) { /* fichier prêté par une autre appli : pas de récent durable */ }
            afficherAccueil()
            vue.montrer(c, 0)
            outils.values.forEach { it.documentCharge() }
            choisirOutil(if (outilActif.exigeDocument || outilActif == Outil.FUSION) outilActif else Outil.LIRE)
            statut("$n : ${c.nombrePages} page(s).")
        }
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
        val choix = mutableListOf("Enregistrer une copie…")
        val remplacable = source?.let { peutEcrire(it) } == true
        if (remplacable) choix.add(0, "Remplacer « $nom »")
        AlertDialog.Builder(this).setTitle("Enregistrer").setItems(choix.toTypedArray()) { _, i ->
            if (remplacable && i == 0) {
                travailler("Enregistrement…", { c.enregistrer().also { ecrire(source!!, it) } }) {
                    statut("Enregistré : $nom")
                }
            } else {
                travailler("Préparation…", { c.enregistrer() }) { octets ->
                    ecrireNouveau(octets, nom.removeSuffix(".pdf") + " (modifié).pdf") { uri ->
                        source = uri; nom = nomDe(uri); Recents.ajouter(this, uri, nom); majEtat()
                        statut("Enregistré : $nom")
                    }
                }
            }
        }.show()
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
        if (resultat != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (uris.isEmpty()) return
        when (code) {
            OUVRIR -> ouvrirUri(uris[0])
            CREER_FICHIER -> {
                val octets = aEcrire ?: return
                aEcrire = null
                travailler("Écriture…", { ecrire(uris[0], octets) }) { apresEcriture(uris[0]) }
            }
            else -> outils[outilActif]?.resultat(code, uris)
        }
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
