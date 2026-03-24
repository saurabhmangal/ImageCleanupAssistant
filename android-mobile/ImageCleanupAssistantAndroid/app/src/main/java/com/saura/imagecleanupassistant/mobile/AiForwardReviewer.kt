package com.saura.imagecleanupassistant.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Array
import kotlin.math.max

private data class InferenceReply(
    val label: AiReviewLabel,
    val confidence: Int,
    val reason: String,
    val backendLabel: String
)

class AiForwardReviewer(private val context: Context) {

    private val guard = Mutex()

    private val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
    private val engineConfigClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
    private val conversationConfigClass = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
    private val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")
    private val contentsCompanionClass = Class.forName("com.google.ai.edge.litertlm.Contents\$Companion")
    private val contentBaseClass = Class.forName("com.google.ai.edge.litertlm.Content")
    private val imageBytesClass = Class.forName("com.google.ai.edge.litertlm.Content\$ImageBytes")
    private val textContentClass = Class.forName("com.google.ai.edge.litertlm.Content\$Text")
    private val backendBaseClass = Class.forName("com.google.ai.edge.litertlm.Backend")
    private val backendGpuClass = Class.forName("com.google.ai.edge.litertlm.Backend\$GPU")
    private val backendCpuClass = Class.forName("com.google.ai.edge.litertlm.Backend\$CPU")

    private val engineCtor = engineClass.getConstructor(engineConfigClass)
    private val engineInitialize = engineClass.getMethod("initialize")
    private val engineCreateConversation = engineClass.getMethod("createConversation", conversationConfigClass)
    private val engineClose = engineClass.getMethod("close")
    private val engineConfigCtor = engineConfigClass.getConstructor(
        String::class.java,
        backendBaseClass,
        backendBaseClass,
        backendBaseClass,
        Integer::class.java,
        String::class.java
    )

    private val conversationConfigCtor = conversationConfigClass.getConstructor()
    private val conversationSendMessage = Class
        .forName("com.google.ai.edge.litertlm.Conversation")
        .getMethod("sendMessage", contentsClass, Map::class.java)
    private val conversationClose = Class
        .forName("com.google.ai.edge.litertlm.Conversation")
        .getMethod("close")

    private val contentsCompanion = contentsClass.getField("Companion").get(null)
    private val contentsOfVarArg = contentsCompanionClass.getMethod("of", Array.newInstance(contentBaseClass, 0).javaClass)
    private val contentsItemsGetter = contentsClass.getMethod("getContents")
    private val messageContentsGetter = Class
        .forName("com.google.ai.edge.litertlm.Message")
        .getMethod("getContents")
    private val textCtor = textContentClass.getConstructor(String::class.java)
    private val imageBytesCtor = imageBytesClass.getConstructor(ByteArray::class.java)
    private val textGetter = textContentClass.getMethod("getText")

    private var loadedModelKey: String? = null
    private var loadedEngine: Any? = null
    private var loadedBackendLabel: String = "GPU"

    suspend fun review(image: MediaImage, modelConfig: AiModelConfig): AiReviewVerdict =
        withContext(Dispatchers.Default) {
            guard.withLock {
                val imageBytes = buildInferenceImageBytes(image.uri)
                val reply = runInference(
                    imageBytes = imageBytes,
                    prompt = buildPrompt(image),
                    modelConfig = modelConfig
                )
                AiReviewVerdict(
                    imageId = image.id,
                    imageModifiedAtMillis = image.modifiedAtMillis,
                    label = reply.label,
                    confidence = reply.confidence,
                    reason = reply.reason,
                    modelName = modelConfig.modelName,
                    reviewedAtMillis = System.currentTimeMillis(),
                    backendLabel = reply.backendLabel
                )
            }
        }

    suspend fun release() {
        withContext(Dispatchers.Default) {
            guard.withLock {
                closeEngine()
            }
        }
    }

    private fun runInference(
        imageBytes: ByteArray,
        prompt: String,
        modelConfig: AiModelConfig
    ): InferenceReply {
        val engine = ensureEngine(modelConfig)
        val conversation = engineCreateConversation.invoke(engine, conversationConfigCtor.newInstance())
        try {
            val contentArray = Array.newInstance(contentBaseClass, 2)
            Array.set(contentArray, 0, imageBytesCtor.newInstance(imageBytes))
            Array.set(contentArray, 1, textCtor.newInstance(prompt))
            val contents = contentsOfVarArg.invoke(contentsCompanion, contentArray)
            val message = conversationSendMessage.invoke(conversation, contents, null)
            val rawText = extractText(message).ifBlank {
                throw IllegalStateException("The AI model returned an empty response.")
            }
            return parseResponse(rawText, loadedBackendLabel)
        } finally {
            runCatching { conversationClose.invoke(conversation) }
        }
    }

    private fun ensureEngine(modelConfig: AiModelConfig): Any {
        if (loadedModelKey == modelConfig.modelKey && loadedEngine != null) {
            return loadedEngine!!
        }

        closeEngine()

        val cacheDir = File(context.cacheDir, "litertlm-cache").apply { mkdirs() }.absolutePath
        val backendCandidates = listOf(
            "GPU" to backendGpuClass.getConstructor().newInstance(),
            "CPU" to backendCpuClass.getConstructor(Integer::class.java).newInstance(Integer.valueOf(4))
        )
        var lastError: Throwable? = null

        backendCandidates.forEach { (label, backend) ->
            val engine = runCatching {
                val config = engineConfigCtor.newInstance(
                    modelConfig.modelPath,
                    backend,
                    backend,
                    backend,
                    Integer.valueOf(192),
                    cacheDir
                )
                val createdEngine = engineCtor.newInstance(config)
                engineInitialize.invoke(createdEngine)
                createdEngine
            }.getOrElse { error ->
                lastError = unwrap(error)
                null
            }

            if (engine != null) {
                loadedModelKey = modelConfig.modelKey
                loadedEngine = engine
                loadedBackendLabel = label
                return engine
            }
        }

        throw IllegalStateException(
            buildString {
                append("Unable to load the AI model.")
                lastError?.message?.takeIf { it.isNotBlank() }?.let {
                    append(' ')
                    append(it)
                }
            },
            lastError
        )
    }

    private fun closeEngine() {
        loadedModelKey = null
        loadedEngine?.let { engine ->
            runCatching { engineClose.invoke(engine) }
        }
        loadedEngine = null
    }

    private fun extractText(message: Any?): String {
        val contents = messageContentsGetter.invoke(message)
        val items = contentsItemsGetter.invoke(contents) as? List<*> ?: return ""
        return items
            .mapNotNull { content ->
                if (content != null && textContentClass.isInstance(content)) {
                    textGetter.invoke(content) as? String
                } else {
                    null
                }
            }
            .joinToString(" ")
            .trim()
    }

    private fun buildPrompt(image: MediaImage): String =
        """
        You are reviewing one phone-gallery image for cleanup.
        Decide whether this image is likely an unwanted shareable forward.
        FORWARD examples: greeting cards, happy new year images, Diwali or Holi wishes, good morning cards, quote posters, motivational cards, invitation cards, announcement flyers, festival wishes, or WhatsApp-style designed forwards.
        PHOTO examples: regular camera photos, family pictures, selfies, pets, travel shots, documents, receipts, or natural scene photos.
        Choose PHOTO whenever you are not clearly sure.
        Reply with exactly one line and nothing else:
        LABEL=FORWARD|PHOTO|UNSURE;CONFIDENCE=0-100;REASON=short phrase
        Context: file=${image.name}; folder=${image.folder}; size=${image.dimensionsText}
        """.trimIndent()

    private fun parseResponse(rawText: String, backendLabel: String): InferenceReply {
        val compact = rawText
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val labelText = Regex("(?i)(?:label\\s*=\\s*|\"label\"\\s*:\\s*\")(?<value>forward|photo|unsure)")
            .find(compact)
            ?.groups
            ?.get("value")
            ?.value
            ?.uppercase()
            ?: when {
                compact.contains("FORWARD", ignoreCase = true) -> "FORWARD"
                compact.contains("UNSURE", ignoreCase = true) -> "UNSURE"
                else -> "PHOTO"
            }

        val label = when (labelText) {
            "FORWARD" -> AiReviewLabel.FORWARD
            "UNSURE" -> AiReviewLabel.UNSURE
            else -> AiReviewLabel.PHOTO
        }

        val confidence = Regex("(?i)(?:confidence\\s*=\\s*|\"confidence\"\\s*:\\s*)(\\d{1,3})")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?: defaultConfidence(label)

        val reason = Regex("(?i)(?:reason\\s*=\\s*|\"reason\"\\s*:\\s*\")(.+?)(?:\"|$)")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trimEnd(';')
            ?.takeIf { it.isNotBlank() }
            ?: compact.take(120)

        return InferenceReply(
            label = label,
            confidence = confidence,
            reason = reason,
            backendLabel = backendLabel
        )
    }

    private fun defaultConfidence(label: AiReviewLabel): Int =
        when (label) {
            AiReviewLabel.FORWARD -> 78
            AiReviewLabel.PHOTO -> 68
            AiReviewLabel.UNSURE -> 50
        }

    private fun buildInferenceImageBytes(uri: Uri): ByteArray {
        val bitmap = decodeBitmap(uri, targetSide = 1024)
        if (bitmap == null) {
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read the selected image for AI review.")
        }

        return bitmap.useBitmap {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
        }
    }

    private fun decodeBitmap(uri: Uri, targetSide: Int): Bitmap? =
        runCatching {
            var width = 0
            var height = 0
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val maxSide = max(width, height).coerceAtLeast(1)
                decoder.setTargetSampleSize((maxSide / targetSide).coerceAtLeast(1))
                decoder.isMutableRequired = false
            }
        }.getOrNull()

    private fun unwrap(error: Throwable): Throwable =
        (error.cause ?: error)
}

private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
    try {
        block(this)
    } finally {
        recycle()
    }
