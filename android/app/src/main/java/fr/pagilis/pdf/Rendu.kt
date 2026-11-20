// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Image d'une page pour l'écran. Comme AndroidDrawDevice.drawPage, sauf les cases de
 * signature vides : MuPDF leur dessine une étiquette « SIGN » (en anglais) au lieu de
 * l'apparence du PDF. L'écran les montre à part (« Signer ici : nom »).
 */
object Rendu {
    fun page(page: Page, m: Matrix): Bitmap {
        val b = page.bounds.transform(m)
        val x0 = floor(b.x0).toInt(); val y0 = floor(b.y0).toInt()
        val bmp = Bitmap.createBitmap(maxOf(1, ceil(b.x1).toInt() - x0), maxOf(1, ceil(b.y1).toInt() - y0), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val dev = AndroidDrawDevice(bmp, x0, y0)
        try {
            page.runPageContents(dev, m, null)
            page.runPageAnnots(dev, m, null)
            if (page is PDFPage) {
                for (w in page.widgets ?: emptyArray())
                    if (!(w.fieldType == PDFWidget.TYPE_SIGNATURE && !w.isSigned)) w.run(dev, m, null)
            } else page.runPageWidgets(dev, m, null)
            dev.close()
        } finally {
            dev.destroy()
        }
        return bmp
    }
}
