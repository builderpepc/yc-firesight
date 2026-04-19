package com.example.wearableai.shared

import java.io.File

private const val TAG = "CameraCapture"

actual class CameraCapture actual constructor() {

    actual suspend fun open(): Boolean {
        // Mobile-only mode: No Meta glasses camera.
        // We could implement standard Android CameraX here.
        return true
    }

    actual suspend fun capture(timeoutMs: Long): String? {
        // Return null to indicate no photo captured since we have no glasses.
        return null
    }

    actual fun close() {
        // No-op
    }
}
