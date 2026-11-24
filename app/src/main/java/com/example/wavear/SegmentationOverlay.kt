package com.example.wavear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.nio.ByteBuffer

/**
 * Función de utilidad: MediaPipe devuelve la máscara como un ByteBuffer.
 * El GPU Delegate casi siempre devuelve RGBA_8888 (4 bytes por píxel), 
 * mientras que el CPU Delegate devuelve ALPHA_8 (1 byte por píxel).
 */
fun createMaskBitmap(byteBuffer: ByteBuffer, width: Int, height: Int): Bitmap {
    val capacity = byteBuffer.capacity()
    val expectedAlpha = width * height
    val expectedRGBA = width * height * 4
    
    byteBuffer.rewind()
    
    if (capacity >= expectedRGBA) {
        // La memoria vino de la GPU (OpenGL) en formato de 4 canales.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(byteBuffer)
        return bitmap
    } else {
        // La memoria vino de la CPU en formato de 1 canal.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val stride = capacity / height
        if (stride > width) {
            // Eliminar los bytes de relleno (padding) fila por fila
            val cleanBuffer = ByteBuffer.allocateDirect(expectedAlpha)
            val row = ByteArray(width)
            for (y in 0 until height) {
                byteBuffer.position(y * stride)
                byteBuffer.get(row, 0, width)
                cleanBuffer.put(row)
            }
            cleanBuffer.rewind()
            bitmap.copyPixelsFromBuffer(cleanBuffer)
        } else {
            bitmap.copyPixelsFromBuffer(byteBuffer)
        }
        return bitmap
    }
}

// Shader escrito en AGSL (Android Graphics Shading Language)
// API 33+ (Android 13)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private const val SEGMENTATION_SHADER = """
    uniform shader maskShader;
    
    half4 getColor(float id) {
        // En ALPHA_8, MediaPipe a veces saca valores en la máscara de "confianza" en lugar de IDs directos
        // si el tensor no está procesado correctamente.
        // Vamos a relajar el mapeo y simplemente colorear TODO lo que NO sea fondo.
        
        // Multiplicamos por 255 para recuperar el valor del byte
        int classId = int(id * 255.0 + 0.5); 
        
        // Invertimos la lógica de color para el modelo Selfie Segmentation
        // Si hay ALGUNA clase detectada (>0), la dejamos TRANSPARENTE (es la persona)
        // El resto (el fondo), lo pintamos de rojo.
        if (classId > 0) return half4(0.0, 0.0, 0.0, 0.0); // Persona (Transparente)
        
        return half4(1.0, 0.0, 0.0, 0.5); // Fondo (Rojo semitransparente)
    }

    half4 main(float2 fragCoord) {
        // Obtenemos el valor de la máscara en la posición actual
        half4 centerMask = maskShader.eval(fragCoord);
        
        // CORRECCIÓN CRÍTICA: Dependiendo de cómo MediaPipe extraiga el Bitmap,
        // el ID de clase puede estar en el canal Alpha (si es ALPHA_8) 
        // o en el canal Rojo (si es ARGB_8888, donde Alpha siempre es 1.0).
        float centerId = (centerMask.a > 0.99) ? centerMask.r : centerMask.a;
        
        int centerClass = int(centerId * 255.0 + 0.5);
        
        if (centerClass > 0) {
            return half4(0.0, 0.0, 0.0, 0.0); // Sin dibujar nada sobre la persona
        }

        // Filtro de bordes
        half4 up = maskShader.eval(fragCoord + float2(0.0, -1.0));
        half4 down = maskShader.eval(fragCoord + float2(0.0, 1.0));
        half4 left = maskShader.eval(fragCoord + float2(-1.0, 0.0));
        half4 right = maskShader.eval(fragCoord + float2(1.0, 0.0));

        float idUp = (up.a > 0.99) ? up.r : up.a;
        float idDown = (down.a > 0.99) ? down.r : down.a;
        float idLeft = (left.a > 0.99) ? left.r : left.a;
        float idRight = (right.a > 0.99) ? right.r : right.a;

        // Tolerancia para que el ruido interno no se marque como borde
        float threshold = 0.05; 
        bool isEdge = abs(centerId - idUp) > threshold || 
                      abs(centerId - idDown) > threshold || 
                      abs(centerId - idLeft) > threshold || 
                      abs(centerId - idRight) > threshold;

        if (isEdge) {
            return half4(1.0, 1.0, 1.0, 1.0); // Borde Blanco Puro
        }

        // Si no es borde, pintamos el relleno
        return getColor(centerId);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun SegmentationOverlay(maskBitmap: Bitmap?, modifier: Modifier = Modifier) {
    // Inicializamos el Paint y el RuntimeShader (solo ocurre una vez en la composición)
    val shaderPaint = remember { Paint() }
    val runtimeShader = remember { RuntimeShader(SEGMENTATION_SHADER) }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (maskBitmap == null) return@Canvas

        // Como el tensor de IA viene girado 90 grados (en landscape) y nosotros 
        // lo vemos en portrait, la escala X debe usar el Height del bitmap y viceversa.
        val scaleX = size.width / maskBitmap.height.toFloat()
        val scaleY = size.height / maskBitmap.width.toFloat()

        // Como CameraX aplica FILL_CENTER (CenterCrop), elegimos la escala mayor
        val finalScale = maxOf(scaleX, scaleY)

        val bitmapShader = BitmapShader(maskBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        runtimeShader.setInputShader("maskShader", bitmapShader)
        
        // Desactivar el filtro bilineal
        shaderPaint.shader = runtimeShader
        shaderPaint.isFilterBitmap = false 

        drawIntoCanvas { canvas ->
            // 1. Mover el centro de dibujo al centro exacto de la pantalla
            canvas.translate(size.width / 2f, size.height / 2f) 
            
            // 2. Rotar 270 grados (o -90) hacia la izquierda para alinear correctamente
            // la orientación natural del sensor de Android con respecto a la pantalla
            canvas.rotate(-90f) 
            
            // 3. ¡ESPEJO! La cámara frontal en Android por hardware es un "espejo" físico.
            // Invertimos el eje Y para que la silueta siga a la mano correcta.
            canvas.scale(1f, -1f)

            // 4. Retroceder a la esquina superior izquierda del mapa rotado y escalar
            canvas.translate(- (maskBitmap.width * finalScale) / 2f, - (maskBitmap.height * finalScale) / 2f)
            canvas.scale(finalScale, finalScale)
            
            // Dibujamos el rectángulo de la máscara
            canvas.nativeCanvas.drawRect(
                0f, 0f, 
                maskBitmap.width.toFloat(), 
                maskBitmap.height.toFloat(), 
                shaderPaint
            )
        }
    }
}
