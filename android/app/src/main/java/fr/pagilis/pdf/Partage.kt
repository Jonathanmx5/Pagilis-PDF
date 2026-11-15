// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Donne à une autre application (mail, messagerie…) un PDF à partager, en lecture
 * seule, sans permission de stockage. Seuls les fichiers du dossier de partage sont servis.
 */
class Partage : ContentProvider() {
    companion object {
        fun dossier(ctx: Context) = File(ctx.cacheDir, "partage").apply { mkdirs() }
        fun uri(ctx: Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.partage/${Uri.encode(f.name)}")
    }

    private fun fichier(uri: Uri): File {
        val nom = uri.lastPathSegment ?: throw SecurityException("chemin vide")
        val f = File(dossier(context!!), nom)
        if (f.parentFile?.canonicalPath != dossier(context!!).canonicalPath) throw SecurityException("hors du partage")
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/pdf"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fichier(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = fichier(uri)
        val colonnes = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(colonnes).apply {
            addRow(colonnes.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
