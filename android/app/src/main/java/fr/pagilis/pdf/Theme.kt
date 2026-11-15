// SPDX-License-Identifier: AGPL-3.0-or-later
package fr.pagilis.pdf

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Couleurs et éléments d'interface, les mêmes que theme.py sur PC (thème sombre). */
object Theme {
    val FOND = Color.parseColor("#1c1d20")
    val SURFACE = Color.parseColor("#24252a")
    val SURFACE_2 = Color.parseColor("#2d2e34")
    val SURFACE_3 = Color.parseColor("#36373e")
    val BORDURE = Color.parseColor("#3a3b42")
    val DOCUMENT = Color.parseColor("#17181b")
    val TEXTE = Color.parseColor("#ececef")
    val TEXTE_2 = Color.parseColor("#b2b4bb")
    val TEXTE_DISCRET = Color.parseColor("#80838c")
    val ACCENT = Color.parseColor("#3d7bf5")
    val ACCENT_DOUX = Color.parseColor("#253552")
    val DANGER = Color.parseColor("#d64848")
    val AVERTISSEMENT = Color.parseColor("#e0a800")

    fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    fun Context.dp(v: Int): Int = dp(v.toFloat()).toInt()

    fun fond(couleur: Int, rayon: Float, bordure: Int? = null, epaisseur: Int = 0) = GradientDrawable().apply {
        setColor(couleur)
        cornerRadius = rayon
        if (bordure != null) setStroke(epaisseur, bordure)
    }

    enum class Style { PRINCIPAL, SECONDAIRE, DISCRET, DANGER }

    fun bouton(ctx: Context, texte: String, style: Style = Style.SECONDAIRE, action: () -> Unit): Button =
        Button(ctx).apply {
            text = texte
            isAllCaps = false
            textSize = 15f
            stateListAnimator = null
            minHeight = ctx.dp(44)
            minimumHeight = ctx.dp(44)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            styler(this, style)
            setOnClickListener { action() }
        }

    fun styler(b: Button, style: Style) {
        val ctx = b.context
        val (fondC, texteC, bord) = when (style) {
            Style.PRINCIPAL -> Triple(ACCENT, Color.WHITE, null)
            Style.SECONDAIRE -> Triple(SURFACE_2, TEXTE, BORDURE)
            Style.DISCRET -> Triple(Color.TRANSPARENT, TEXTE, null)
            Style.DANGER -> Triple(Color.TRANSPARENT, DANGER, DANGER)
        }
        b.setTextColor(texteC)
        b.typeface = if (style == Style.PRINCIPAL) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.background = RippleDrawable(ColorStateList.valueOf(SURFACE_3),
            fond(fondC, ctx.dp(8f), bord, if (bord != null) ctx.dp(1) else 0), null)
    }

    fun activer(v: View, actif: Boolean) {
        v.isEnabled = actif
        v.alpha = if (actif) 1f else 0.4f
    }

    fun titre(ctx: Context, texte: String, taille: Float = 18f) = TextView(ctx).apply {
        text = texte
        textSize = taille
        setTextColor(TEXTE)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun texte(ctx: Context, texte: String, couleur: Int = TEXTE_2, taille: Float = 14f) = TextView(ctx).apply {
        text = texte
        textSize = taille
        setTextColor(couleur)
    }

    /** Choix exclusif en segments (comme les CTkSegmentedButton du PC). */
    class Segments(ctx: Context, valeurs: List<String>, initiale: String, val changement: (String) -> Unit = {}) :
        LinearLayout(ctx) {
        var valeur: String = initiale
            private set
        private val boutons = valeurs.associateWith { v ->
            TextView(ctx).apply {
                text = v
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                setOnClickListener { choisir(v) }
            }
        }

        init {
            orientation = HORIZONTAL
            background = fond(SURFACE_2, ctx.dp(8f))
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            boutons.values.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
            marquer()
        }

        fun choisir(v: String) {
            valeur = v
            marquer()
            changement(v)
        }

        private fun marquer() = boutons.forEach { (v, t) ->
            val choisi = v == valeur
            t.background = if (choisi) fond(ACCENT, context.dp(6f)) else null
            t.setTextColor(if (choisi) Color.WHITE else TEXTE)
        }
    }
}
