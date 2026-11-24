package com.example.wavear

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.wavear.ui.theme.WavearTheme
import com.google.mediapipe.framework.image.ByteBufferExtractor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WavearTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. Estado para el permiso de cámara
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 2. Launcher para pedir el permiso en tiempo de ejecución
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show()
        }
    }

    // 3. Pedir el permiso al iniciar si no lo tenemos
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 4. UI Principal (Solo si hay permiso)
    if (hasCameraPermission) {
        // Estado de la máscara y de la cámara (trasera vs frontal)
        var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isFrontCamera by remember { mutableStateOf(true) }
        
        // Inicializamos los controladores que hemos creado (recordados para que no se recreen)
        val cameraManager = remember { CameraManager(context, lifecycleOwner) }
        val segmenter = remember {
            SceneSegmenter(context) { result, _ ->
                // Este callback se ejecuta en background cada vez que MediaPipe termina un frame
                val categoryMask = result.categoryMask().orElse(null)
                if (categoryMask != null) {
                    // El GPU Delegate a veces genera MPImages que BitmapExtractor no soporta.
                    // Extraemos el ByteBuffer y usamos la función de utilidad para construir el Bitmap.
                    val width = categoryMask.width
                    val height = categoryMask.height
                    
                    try {
                        val byteBuffer = ByteBufferExtractor.extract(categoryMask)
                        maskBitmap = createMaskBitmap(byteBuffer, width, height)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Limpieza de memoria (IMPORTANTE)
        DisposableEffect(Unit) {
            onDispose {
                cameraManager.shutdown()
                segmenter.close()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // A. Vista de la Cámara (CameraX PreviewView)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { previewView ->
                    // El update se llama cada vez que cambia un estado que esta vista consume (como isFrontCamera)
                    cameraManager.startCamera(previewView, isFrontCamera) { imageProxy ->
                        segmenter.segmentImage(imageProxy)
                    }
                }
            )

            // B. Vista Superpuesta de Segmentación (AGSL Shader)
            // Validamos que el dispositivo soporte AGSL (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SegmentationOverlay(
                    maskBitmap = maskBitmap,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "El filtro avanzado requiere Android 13+",
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            
            // C. Botón Flotante para cambiar de cámara
            FloatingActionButton(
                onClick = {
                    isFrontCamera = !isFrontCamera
                },
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .size(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Cambiar Cámara"
                )
            }
        }
    } else {
        // Pantalla de carga mientras se aceptan los permisos
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Solicitando permisos de cámara...")
        }
    }
}
