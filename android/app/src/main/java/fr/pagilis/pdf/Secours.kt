// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import java.io.File
import java.util.Base64

/**
 * Copies de secours : avant de remplacer un PDF, sa version d'avant est gardée sur
 * l'appareil. Les plus anciennes s'effacent d'elles-mêmes (10 au plus).
 */
object Secours {
    data class Copie(val fichier: File, val nom: String, val quand: Long)

    fun dossier(filesDir: File) = File(filesDir, "secours").apply { mkdirs() }

    fun garder(dossier: File, nom: String, octets: ByteArray, max: Int = 10, quand: Long = System.currentTimeMillis()): Copie {
        dossier.mkdirs()
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(nom.toByteArray(Charsets.UTF_8))
        val f = File(dossier, "${quand}_$code.pdf")
        val provisoire = File(dossier, f.name + ".part")
        provisoire.writeBytes(octets)
        if (!provisoire.renameTo(f)) { provisoire.delete(); throw ErreurPdf("Copie de secours impossible.") }
        liste(dossier).drop(max).forEach { it.fichier.delete() }
        return Copie(f, nom, quand)
    }

    /** La plus récente d'abord. */
    fun liste(dossier: File): List<Copie> = dossier.listFiles { f -> f.name.endsWith(".pdf") }.orEmpty().mapNotNull { f ->
        val (t, code) = f.name.removeSuffix(".pdf").split("_", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val quand = t.toLongOrNull() ?: return@mapNotNull null
        val nom = try { String(Base64.getUrlDecoder().decode(code), Charsets.UTF_8) } catch (_: IllegalArgumentException) { return@mapNotNull null }
        Copie(f, nom, quand)
    }.sortedByDescending { it.quand }
}
