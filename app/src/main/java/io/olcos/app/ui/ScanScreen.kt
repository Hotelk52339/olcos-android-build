package io.olcos.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.olcos.app.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The camera, full screen, until a QR code is read: the text goes to
 * `onResult` once, and the caller takes it in like a pasted one. Asks for
 * the camera permission on first use; a refusal shows why it is needed.
 */
@Composable
fun ScanScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    val p = OlcosTheme.palette
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    // One answer per screen: frames keep coming after the first decode.
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // The camera is bound to the activity's lifecycle, which outlives this screen: unbound by hand on the way out.
    val bound = remember { AtomicReference<ProcessCameraProvider?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            runCatching { bound.getAndSet(null)?.unbindAll() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(view.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { image ->
                            val text = decodeQr(image)
                            image.close()
                            if (text != null && delivered.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            bound.set(provider)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (refused) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text(stringResource(R.string.scan_permission_refused), style = MaterialTheme.typography.bodyLarge, color = Color.White)
                Spacer(Modifier.height(20.dp))
                ActionButton(stringResource(R.string.scan_permission_again), p, primary = true) { ask.launch(Manifest.permission.CAMERA) }
            }
        }
        Text(
            stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodyMedium, color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        )
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
        }
    }
}

/** The QR code in a camera frame, from the luminance plane alone; null when the frame has none. */
private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    // The Y plane may be padded per row: copy it into a tight width×height array.
    val data = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(data, 0, minOf(data.size, buffer.remaining()))
    } else {
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val n = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, n)
            for (x in 0 until width) {
                val at = x * pixelStride
                if (at < n) data[y * width + x] = row[at]
            }
        }
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
}
