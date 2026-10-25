package com.pixelden.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-код: чёрные клетки на белом с полем в 2 клетки — камеры телефонов читают его с экрана ТВ. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    // Размер 0×0 — матрица «одна точка на клетку», рисуем сами, без размытия.
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier) {
        val quiet = 2
        val cell = size.minDimension / (m.width + quiet * 2)
        drawRect(Color.White, size = Size(size.minDimension, size.minDimension))
        for (y in 0 until m.height) for (x in 0 until m.width) {
            // +0.5 — без светлых щелей между клетками.
            if (m[x, y]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
