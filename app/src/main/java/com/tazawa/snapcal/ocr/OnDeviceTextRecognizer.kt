package com.tazawa.snapcal.ocr

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

interface OnDeviceTextRecognizer : Closeable {
    suspend fun recognize(uri: Uri): List<RecognizedLine>
}

class MlKitJapaneseTextRecognizer(
    context: Context,
) : OnDeviceTextRecognizer {
    private val appContext = context.applicationContext
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build(),
    )

    override suspend fun recognize(uri: Uri): List<RecognizedLine> = withContext(NonCancellable) {
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(appContext.contentResolver, uri),
        ) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_EDGE_PX) {
                val scale = MAX_IMAGE_EDGE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt(),
                    (info.size.height * scale).roundToInt(),
                )
            }
        }
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val recognizedLines = result.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { line ->
                                    val box = line.boundingBox ?: return@mapNotNull null
                                    RecognizedLine(
                                        text = line.text,
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom,
                                    )
                                }
                            }
                        continuation.resume(
                            SystemStatusBarFilter.filter(recognizedLines, bitmap.width, bitmap.height),
                        )
                    }
                    .addOnFailureListener(continuation::resumeWithException)
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }

    private companion object {
        const val MAX_IMAGE_EDGE_PX = 2_560
    }
}
