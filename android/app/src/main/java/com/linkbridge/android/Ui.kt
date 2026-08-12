package com.linkbridge.android

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

object Ui {
    const val BACKGROUND = 0xFFF7F6F2.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF171717.toInt()
    const val MUTED = 0xFF6B7280.toInt()
    const val LINE = 0xFFE5E1D8.toInt()
    const val ACCENT = 0xFF2563EB.toInt()
    const val SUCCESS = 0xFFEAF7EF.toInt()
    const val WARNING = 0xFFFFF4D7.toInt()
    const val DANGER = 0xFFFFECEC.toInt()

    fun rounded(color: Int, radius: Float = 22f, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(1, it) }
        }
    }

    fun TextView.title(textValue: String) {
        text = textValue
        textSize = 30f
        setTextColor(INK)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0f
    }

    fun TextView.section(textValue: String) {
        text = textValue
        textSize = 13f
        setTextColor(MUTED)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        isAllCaps = true
    }

    fun TextView.body(textValue: String) {
        text = textValue
        textSize = 15f
        setTextColor(INK)
        setLineSpacing(2f, 1.0f)
    }

    fun View.pad(horizontal: Int = 24, vertical: Int = 18) {
        setPadding(horizontal, vertical, horizontal, vertical)
    }
}
