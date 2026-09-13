package com.linkbridge.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BACKGROUND = Color.parseColor("#F4F5F1")
    val SURFACE = Color.parseColor("#FFFFFF")
    val INK = Color.parseColor("#10151F")
    val MUTED = Color.parseColor("#68717D")
    val LINE = Color.parseColor("#D9DED8")
    val ACCENT = Color.parseColor("#3E62E8")
    val ACCENT_LIGHT = Color.parseColor("#E8EDFF")

    val SUCCESS = Color.parseColor("#35C98B")
    val WARNING = Color.parseColor("#F08A5D")
    val DANGER = Color.parseColor("#D95C5C")

    val SUCCESS_BG = Color.parseColor("#E7F8F0")
    val WARNING_BG = Color.parseColor("#FFF0E9")
    val DANGER_BG = Color.parseColor("#FCECEC")

    fun rounded(color: Int, radiusDp: Float = 16f, strokeColor: Int? = null, strokeWidthPx: Int = 2): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 2f
            strokeColor?.let { setStroke(strokeWidthPx, it) }
        }
    }

    fun primaryButtonDrawable(): RippleDrawable {
        val defaultBg = rounded(ACCENT, radiusDp = 12f)
        val mask = rounded(Color.BLACK, radiusDp = 12f)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#5E83E8")), defaultBg, mask)
    }

    fun secondaryButtonDrawable(): RippleDrawable {
        val defaultBg = rounded(SURFACE, radiusDp = 12f, strokeColor = LINE, strokeWidthPx = 3)
        val mask = rounded(Color.BLACK, radiusDp = 12f)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#CBD5E1")), defaultBg, mask)
    }

    fun statusDot(context: Context, color: Int): View {
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    fun TextView.title(textValue: String) {
        text = textValue
        textSize = 30f
        setTextColor(INK)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun TextView.section(textValue: String) {
        text = textValue
        textSize = 11f
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

    fun View.pad(horizontal: Int = 20, vertical: Int = 14) {
        setPadding(horizontal, vertical, horizontal, vertical)
    }
}
