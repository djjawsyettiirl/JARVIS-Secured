package com.jarvis.secured

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import java.io.File
import kotlin.random.Random

object LocalImageEngine {
    fun available(context: Context) = LocalModelStore.installed(context, LocalModelStore.Kind.IMAGE)

    fun generate(context: Context, prompt: String, iterations: Int = 20): String {
        require(prompt.isNotBlank()) { "Image prompt cannot be empty" }
        val directory = LocalModelStore.image(context)
        require(directory.isDirectory) { "Install the local image model in Settings first" }
        val options = ImageGenerator.ImageGeneratorOptions.builder()
            .setImageGeneratorModelDirectory(directory.absolutePath)
            .build()
        val bitmap = ImageGenerator.createFromOptions(context.applicationContext, options).use { generator ->
            BitmapExtractor.extract(generator.generate(prompt, iterations, Random.nextInt()).generatedImage())
        }
        val outputDirectory = File(context.cacheDir, "generated-images").apply { mkdirs() }
        val file = File(outputDirectory, "jarvis-local-${System.currentTimeMillis()}.png")
        file.outputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save generated image" }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }
}
