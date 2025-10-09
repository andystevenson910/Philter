package com.y9.philter.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageEmbedder(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputSize = 224
    private val pixelSize = 3 // RGB
    private val imageMean = 0f
    private val imageStd = 255f

    init {
        try {
            Log.d("ImageEmbedder", "🔄 Loading TFLite model...")
            val model = FileUtil.loadMappedFile(context, "mobilenet_v2_embeddings.tflite")
            Log.d("ImageEmbedder", "✅ Model file loaded, size: ${model.capacity()} bytes")

            val options = Interpreter.Options()
            options.setNumThreads(4)

            interpreter = Interpreter(model, options)

            // Allocate tensors
            interpreter?.allocateTensors()

            Log.d("ImageEmbedder", "✅ Interpreter created successfully")
            Log.d("ImageEmbedder", "📊 Input shape: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            Log.d("ImageEmbedder", "📊 Output shape: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
        } catch (e: Exception) {
            Log.e("ImageEmbedder", "❌ FAILED TO LOAD MODEL", e)
            e.printStackTrace()
        }
    }

    fun extractEmbedding(uri: Uri): FloatArray? {
        return try {
            Log.d("ImageEmbedder", "→ Starting extraction for URI: $uri")

            val bitmap = loadBitmap(uri)
            if (bitmap == null) {
                Log.e("ImageEmbedder", "❌ Failed to load bitmap")
                return null
            }
            Log.d("ImageEmbedder", "✅ Bitmap loaded: ${bitmap.width}x${bitmap.height}")

            // Resize bitmap to 224x224
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            if (bitmap != resizedBitmap) {
                bitmap.recycle()
            }
            Log.d("ImageEmbedder", "✅ Bitmap resized to ${inputSize}x${inputSize}")

            // Convert to ByteBuffer
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
            Log.d("ImageEmbedder", "✅ ByteBuffer created, capacity: ${inputBuffer.capacity()}")

            // Output buffer for embeddings (1280 features for MobileNetV2)
            val output = Array(1) { FloatArray(1280) }

            if (interpreter == null) {
                Log.e("ImageEmbedder", "❌ Interpreter is NULL!")
                return null
            }

            Log.d("ImageEmbedder", "🤖 Running inference...")
            interpreter?.run(inputBuffer, output)
            Log.d("ImageEmbedder", "✅ Inference complete! First 5 values: ${output[0].take(5)}")

            resizedBitmap.recycle()
            output[0]
        } catch (e: Exception) {
            Log.e("ImageEmbedder", "❌ EXTRACTION FAILED", e)
            e.printStackTrace()
            null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * pixelSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]

                // Extract RGB values and normalize
                byteBuffer.putFloat(((value shr 16 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value shr 8 and 0xFF) - imageMean) / imageStd)
                byteBuffer.putFloat(((value and 0xFF) - imageMean) / imageStd)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            Log.d("ImageEmbedder", "Loading bitmap from URI...")
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(1)
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }

            // Convert to ARGB_8888 if needed
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                Log.d("ImageEmbedder", "Converting from ${bitmap.config} to ARGB_8888")
                val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                convertedBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("ImageEmbedder", "❌ Bitmap loading failed", e)
            e.printStackTrace()
            null
        }
    }

    fun close() {
        Log.d("ImageEmbedder", "Closing interpreter")
        interpreter?.close()
    }
}