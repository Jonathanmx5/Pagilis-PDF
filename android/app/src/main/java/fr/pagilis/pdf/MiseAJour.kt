// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mises à jour de l'application Android, depuis les versions `android-vX.Y.Z`
 * publiées sur GitHub. Android n'installe la nouvelle version que si elle est
 * signée avec la même clé que l'actuelle : un APK falsifié serait refusé.
 */
object MiseAJour {
    const val DEPOT = "Jonathanmx5/Pagilis-PDF"
    const val NOM_APK = "PagilisPDF.apk"
    private const val PREFIXE = "android-v"

    data class Publication(val tag: String, val apk: String?, val taille: Long, val notes: String,
                           val brouillon: Boolean = false, val preversion: Boolean = false) {
        val version: String get() = tag.removePrefix(PREFIXE)
    }

    /** « android-v1.2.3 » ou « 1.2.3 » -> [1, 2, 3] ; null pour une version PC ou une bêta. */
    fun version(texte: String): List<Int>? {
        val brut = if (texte.startsWith(PREFIXE)) texte.removePrefix(PREFIXE)
        else if (texte.first().isDigit()) texte else return null
        val morceaux = brut.split('.')
        if (morceaux.size != 3) return null
        return morceaux.map { it.toIntOrNull() ?: return null }
    }

    private fun plusGrande(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** La publication Android la plus récente, si elle dépasse la version installée. */
    fun nouveaute(publications: List<Publication>, actuelle: String): Publication? {
        val ici = version(actuelle) ?: return null
        return publications
            .filter { !it.brouillon && !it.preversion && it.apk != null && it.tag.startsWith(PREFIXE) }
            .mapNotNull { p -> version(p.tag)?.let { p to it } }
            .filter { (_, v) -> plusGrande(v, ici) }
            .maxWithOrNull { x, y -> if (plusGrande(x.second, y.second)) 1 else if (x.second == y.second) 0 else -1 }
            ?.first
    }

    private fun connexion(adresse: String): HttpURLConnection =
        (URL(adresse).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PagilisPDF-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** Interroge GitHub (hors du fil de l'interface). Null si à jour ou hors ligne. */
    fun chercher(actuelle: String): Publication? = try {
        val texte = connexion("https://api.github.com/repos/$DEPOT/releases?per_page=30").inputStream.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val tableau = JSONArray(texte)
        val publications = (0 until tableau.length()).map { i ->
            val r = tableau.getJSONObject(i)
            val assets = r.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == NOM_APK }
            Publication(r.optString("tag_name"), apk?.optString("browser_download_url"), apk?.optLong("size") ?: 0,
                r.optString("body"), r.optBoolean("draft"), r.optBoolean("prerelease"))
        }
        nouveaute(publications, actuelle)
    } catch (e: Exception) {
        null  // pas de réseau, GitHub indisponible : on réessaiera au prochain lancement
    }

    /** Télécharge l'APK dans le dossier de partage ; progression(0..100). */
    fun telecharger(p: Publication, dossier: File, progression: (Int) -> Unit): File {
        val cible = File(dossier, NOM_APK)
        val c = connexion(p.apk ?: throw ErreurPdf("Cette version n'a pas d'APK."))
        c.instanceFollowRedirects = true
        val total = if (p.taille > 0) p.taille else c.contentLengthLong
        var lu = 0L
        c.inputStream.use { entree ->
            cible.outputStream().use { sortie ->
                val tampon = ByteArray(64 * 1024)
                while (true) {
                    val n = entree.read(tampon)
                    if (n < 0) break
                    sortie.write(tampon, 0, n)
                    lu += n
                    if (total > 0) progression((lu * 100 / total).toInt())
                }
            }
        }
        if (p.taille > 0 && lu != p.taille) {
            cible.delete()
            throw ErreurPdf("Téléchargement incomplet, réessayez.")
        }
        return cible
    }

    /** Android doit autoriser Pagilis PDF à installer des applications (une seule fois). */
    fun peutInstaller(a: Activity) = Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()

    fun demanderAutorisation(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
    }

    /** Ouvre l'installeur d'Android sur l'APK téléchargé ; l'utilisateur confirme. */
    fun installer(a: Activity, apk: File) {
        a.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Partage.uri(a, apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
