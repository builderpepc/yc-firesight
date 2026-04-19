package com.example.wearableai.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraCapture"

actual class CameraCapture actual constructor() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override fun getLifecycle(): Lifecycle = registry
    }

    actual suspend fun open(): Boolean = withContext(Dispatchers.Main) {
        try {
            val future = ProcessCameraProvider.getInstance(appContext)
            val deferred = CompletableDeferred<ProcessCameraProvider>()
            future.addListener({ deferred.complete(future.get()) }, ContextCompat.getMainExecutor(appContext))
            cameraProvider = deferred.await()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
            true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to open camera: ${e.message}")
            false
        }
    }

    actual suspend fun capture(timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val capture = imageCapture ?: return@withContext null
        val deferred = CompletableDeferred<String?>()

        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val file = File(appContext.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    val rotated = if (rotation != 0) bitmap.rotate(rotation) else bitmap
                    
                    FileOutputStream(file).use { out ->
                        rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    image.close()
                    deferred.complete(file.absolutePath)
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "Capture processing failed: ${e.message}")
                    image.close()
                    deferred.complete(null)
                }
            }

            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                android.util.Log.e(TAG, "Capture failed: ${exception.message}")
                deferred.complete(null)
            }
        })

        // Simple timeout
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    actual fun close() {
        cameraProvider?.unbindAll()
        executor.shutdown()
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
