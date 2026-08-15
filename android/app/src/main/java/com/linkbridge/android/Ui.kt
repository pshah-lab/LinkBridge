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
    val BACKGROUND = Color.parseColor("#F8FAFC")
    val SURFACE = Color.parseColor("#FFFFFF")
    val INK = Color.parseColor("#0F172A")
    val MUTED = Color.parseColor("#64748B")
    val LINE = Color.parseColor("#E2E8F0")
    val ACCENT = Color.parseColor("#4F46E5")
    val ACCENT_LIGHT = Color.parseColor("#EEF2FF")

    val SUCCESS = Color.parseColor("#10B981")
    val WARNING = Color.parseColor("#F59E0B")
    val DANGER = Color.parseColor("#EF4444")

    val SUCCESS_BG = Color.parseColor("#ECFDF5")
    val WARNING_BG = Color.parseColor("#FFFBEB")
    val DANGER_BG = Color.parseColor("#FEF2F2")

    fun rounded(color: Int, radiusDp: Float = 16f, strokeColor: Int? = null, strokeWidthPx: Int = 2): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 2.5f
            strokeColor?.let { setStroke(strokeWidthPx, it) }
        }
    }

    fun primaryButtonDrawable(): RippleDrawable {
        val defaultBg = rounded(ACCENT, radiusDp = 12f)
        val mask = rounded(Color.BLACK, radiusDp = 12f)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#818CF8")), defaultBg, mask)
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
        textSize = 24f
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
        textSize = 14f
        setTextColor(INK)
        setLineSpacing(2f, 1.0f)
    }

    fun View.pad(horizontal: Int = 20, vertical: Int = 14) {
        setPadding(horizontal, vertical, horizontal, vertical)
    }
}
