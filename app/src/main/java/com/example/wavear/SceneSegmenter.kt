package com.example.wavear

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult

class SceneSegmenter(
    private val context: Context,
    private val modelName: String = "selfie_segmentation.tflite", // El modelo debe estar en src/main/assets/
    private val onResult: (ImageSegmenterResult, MPImage) -> Unit
) {

    private var imageSegmenter: ImageSegmenter? = null

    init {
        setupImageSegmenter()
    }

    private fun setupImageSegmenter() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(modelName)
            .setDelegate(Delegate.GPU) // Usar GPU para mejor rendimiento (requiere OpenGL ES 3.1+)

        val optionsBuilder = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setRunningMode(RunningMode.LIVE_STREAM) // Modo de inferencia asíncrona continua
            .setOutputCategoryMask(true) // Requerimos la matriz de categorías por píxel
            .setOutputConfidenceMasks(false)
            .setResultListener { result: ImageSegmenterResult, image: MPImage ->
                // Callback devuelto en segundo plano
                onResult(result, image)
            }
            .setErrorListener { error ->
                Log.e(TAG, "Error en ImageSegmenter: \${error.message}")
            }

        try {
            imageSegmenter = ImageSegmenter.createFromOptions(context, optionsBuilder.build())
            Log.d(TAG, "ImageSegmenter inicializado correctamente con GPU Delegate.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando ImageSegmenter. ¿Está el modelo en assets?", e)
        }
    }

    /**
     * Extrae el fotograma, lo convierte para MediaPipe y ejecuta la inferencia.
     */
    fun segmentImage(imageProxy: ImageProxy) {
        if (imageSegmenter == null) {
            imageProxy.close()
            return
        }

        try {
            // CameraX 1.3.0+ permite llamar a toBitmap() directamente si el formato es RGBA_8888
            val bitmap = imageProxy.toBitmap()

            // MediaPipe necesita marcas de tiempo secuenciales. Lo extraemos del ImageProxy y convertimos a ms
            val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000

            // Convertir Bitmap nativo a MPImage de MediaPipe
            val mpImage = BitmapImageBuilder(bitmap).build()

            // Enviar para segmentar (LIVE_STREAM es asíncrono)
            imageSegmenter?.segmentAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando el fotograma", e)
        } finally {
            // MUY IMPORTANTE: siempre cerrar el ImageProxy para no trabar la cámara
            imageProxy.close()
        }
    }

    /**
     * Libera los recursos de memoria y la GPU al cerrar
     */
    fun close() {
        imageSegmenter?.close()
        imageSegmenter = null
    }

    companion object {
        private const val TAG = "SceneSegmenter"
    }
}
