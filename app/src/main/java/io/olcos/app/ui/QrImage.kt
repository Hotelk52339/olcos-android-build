package io.olcos.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A QR code of `text`, black on white, sized by the modifier. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) { encode(text, 512) }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    }
}

private fun encode(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
