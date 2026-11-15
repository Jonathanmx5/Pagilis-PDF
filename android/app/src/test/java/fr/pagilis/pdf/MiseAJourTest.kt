package fr.pagilis.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiseAJourTest {
    private fun r(tag: String, brouillon: Boolean = false, pre: Boolean = false, apk: Boolean = true) =
        MiseAJour.Publication(tag, if (apk) "https://x/$tag/PagilisPDF.apk" else null, 1000, "notes $tag", brouillon, pre)

    @Test
    fun lectureDesNumeros() {
        assertEquals(listOf(1, 10, 0), MiseAJour.version("android-v1.10.0"))
        assertEquals(listOf(0, 2, 0), MiseAJour.version("0.2.0"))
        assertNull(MiseAJour.version("v1.10.0"))   // une version PC n'est pas une version Android
        assertNull(MiseAJour.version("android-v0.2.0-beta.1"))
    }

    @Test
    fun choisitLaPlusRecenteAndroidPublieeAvecApk() {
        val liste = listOf(r("v1.10.0"), r("android-v0.1.0"), r("android-v0.10.0"), r("android-v0.9.0"),
            r("android-v0.11.0", brouillon = true), r("android-v0.12.0", pre = true), r("android-v0.13.0", apk = false))
        assertEquals("android-v0.10.0", MiseAJour.nouveaute(liste, "0.2.0")?.tag)
    }

    @Test
    fun rienSiDejaAJour() {
        assertNull(MiseAJour.nouveaute(listOf(r("android-v0.2.0"), r("android-v0.1.0")), "0.2.0"))
        assertNull(MiseAJour.nouveaute(emptyList(), "0.2.0"))
    }
}
