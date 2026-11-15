package com.linkbridge.android.ui.offline

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * [text] as a square QR code, black on white whatever the theme (scanners need that contrast).
 * [sizePx] is the target; the bitmap is the nearest whole number of pixels per module. Null if
 * [text] doesn't fit in a QR code.
 */
fun qrBitmap(text: String, sizePx: Int): Bitmap? {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    } catch (_: com.google.zxing.WriterException) {
        return null
    }
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
