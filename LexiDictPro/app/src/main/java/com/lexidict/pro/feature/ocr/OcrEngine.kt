package com.lexidict.pro.feature.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════
//  OCR ENGINE
// ═══════════════════════════════════════════════════════════════

@Singleton
class OcrEngine @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extract text from a bitmap using MLKit on-device OCR.
     * Works fully offline after the model is downloaded.
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val fullText = result.text
            val words = result.textBlocks
                .flatMap { block -> block.lines }
                .flatMap { line -> line.elements }
                .map { element -> element.text }
                .filter { it.length in 2..50 && it.all { c -> c.isLetter() || c == '-' } }

            OcrResult.Success(fullText = fullText, words = words.distinct())
        } catch (e: Exception) {
            OcrResult.Error(e.message ?: "OCR failed")
        }
    }

    /**
     * Extract text from a URI (image file).
     */
    suspend fun recognizeTextFromUri(context: Context, uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val result = recognizer.process(image).await()
            OcrResult.Success(
                fullText = result.text,
                words = result.text.split(Regex("\\s+"))
                    .filter { it.length in 2..50 }
                    .distinct()
            )
        } catch (e: Exception) {
            OcrResult.Error(e.message ?: "OCR failed")
        }
    }

    fun close() {
        recognizer.close()
    }
}

// ═══════════════════════════════════════════════════════════════
//  OCR RESULT
// ═══════════════════════════════════════════════════════════════

sealed class OcrResult {
    data class Success(val fullText: String, val words: List<String>) : OcrResult()
    data class Error(val message: String) : OcrResult()
}

// ═══════════════════════════════════════════════════════════════
//  CAMERA OCR SCREEN
// ═══════════════════════════════════════════════════════════════

@Composable
fun OcrCameraScreen(
    onWordDetected: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isFlashOn by remember { mutableStateOf(false) }
    var detectedWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var isCapturing by remember { mutableStateOf(false) }

    val ocrEngine = remember { OcrEngine() }
    var imageCapture: ImageCapture? = remember { null }

    DisposableEffect(Unit) {
        onDispose { ocrEngine.close() }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Scanning guide overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = Rect(
                left = size.width * 0.1f,
                top = size.height * 0.3f,
                right = size.width * 0.9f,
                bottom = size.height * 0.7f
            )
            drawRoundRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                size = androidx.compose.ui.geometry.Size(rect.width, rect.height),
                style = Stroke(width = 3.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
            )
        }

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Detected words chips
            if (detectedWords.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Tap a word to look up",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(detectedWords.take(8)) { word ->
                                SuggestionChip(
                                    onClick = { onWordDetected(word) },
                                    label = { Text(word, color = Color.White) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = Color.White.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Capture button
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isFlashOn = !isFlashOn },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Default.FlashOn, "Flash",
                        tint = if (isFlashOn) Color.Yellow else Color.White)
                }

                // Large capture button
                Button(
                    onClick = {
                        isCapturing = true
                        val capture = imageCapture ?: return@Button
                        val scope = CoroutineScope(Dispatchers.Main)
                        scope.launch {
                            try {
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                                    java.io.File(context.cacheDir, "ocr_temp.jpg")
                                ).build()

                                // Simplified — in production use takePicture with callback
                                isCapturing = false
                            } catch (e: Exception) {
                                isCapturing = false
                            }
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    enabled = !isCapturing
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Default.Camera, "Capture",
                            modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.size(48.dp)) // Balance
            }
        }
    }
}
