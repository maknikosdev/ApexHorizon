package com.apexhorizon.app.ui.hud

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX preview plus a light analyzer. The analyzer only measures average brightness of a sparse
 * pixel grid a few times per second; frames are never stored or sent anywhere.
 */
@Composable
fun CameraLayer(
    modifier: Modifier = Modifier,
    onLuma: (Double) -> Unit,
    onActive: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val latestLuma by rememberUpdatedState(onLuma)
    val latestActive by rememberUpdatedState(onActive)

    DisposableEffect(lifecycleOwner) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, LumaAnalyzer { latestLuma(it) })
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                latestActive(true)
            } catch (_: Throwable) {
                latestActive(false)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
            latestActive(false)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private class LumaAnalyzer(private val onLuma: (Double) -> Unit) : ImageAnalysis.Analyzer {
    private var lastMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs < 400) return
            lastMs = now
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var sum = 0L
            var count = 0
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val idx = y * rowStride + x * pixelStride
                    if (idx < buf.limit()) {
                        sum += buf.get(idx).toInt() and 0xFF
                        count++
                    }
                    x += STEP
                }
                y += STEP
            }
            if (count > 0) onLuma(sum.toDouble() / count / 255.0)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val STEP = 16
    }
}
