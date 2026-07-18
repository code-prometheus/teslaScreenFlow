package com.tesla.screenflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕捕获 → JPEG，短边固定 540，长边按比例。
 * 监听屏幕旋转自动重建 VirtualDisplay。
 */
class ScreenJpegCapture(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "TeslaScreenFlow::Jpeg"
        private const val SHORT_SIDE = 1080
        private const val JPEG_QUALITY = 85
        private const val MAX_IMAGES = 2
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val running = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var screenW: Int = 1080
    private var screenH: Int = 1920
    private var densityDpi: Int = 320
    private var displayListener: DisplayManager.DisplayListener? = null
    private val jpegBuffer = ByteArrayOutputStream(65536)

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        calcSize(metrics)
        densityDpi = metrics.densityDpi
        createCapture()

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY) return
                val m = DisplayMetrics()
                dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
                if (m.widthPixels != screenW || m.heightPixels != screenH) {
                    calcSize(m); densityDpi = m.densityDpi; createCapture()
                }
            }
        }
        dm.registerDisplayListener(displayListener, null)
    }

    private fun calcSize(m: DisplayMetrics) {
        if (m.widthPixels <= m.heightPixels) {
            screenW = SHORT_SIDE
            screenH = (SHORT_SIDE * m.heightPixels.toFloat() / m.widthPixels).toInt()
        } else {
            screenH = SHORT_SIDE
            screenW = (SHORT_SIDE * m.widthPixels.toFloat() / m.heightPixels).toInt()
        }
    }

    @Synchronized
    private fun createCapture() {
        handlerThread?.quitSafely()
        handlerThread = HandlerThread("JpegCap").apply { start() }
        val handler = Handler(handlerThread!!.looper)

        imageReader?.close()
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = image.planes[0].buffer
                val rowStride = image.planes[0].rowStride
                val pixelStride = image.planes[0].pixelStride
                val pad = rowStride - pixelStride * screenW

                val bmp = Bitmap.createBitmap(screenW + pad / pixelStride, screenH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                val cropped = if (pad > 0) Bitmap.createBitmap(bmp, 0, 0, screenW, screenH) else bmp

                jpegBuffer.reset()
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegBuffer)

                if (cropped !== bmp) cropped.recycle()
                bmp.recycle()

                if (running.get()) onFrame(jpegBuffer.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "Frame err", e)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay?.release()
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenJpeg", screenW, screenH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        Log.i(TAG, "$screenW x $screenH")
    }

    fun stop() {
        running.set(false)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayListener?.let { dm.unregisterDisplayListener(it) }
        } catch (_: Exception) {}
        displayListener = null
    }
}
