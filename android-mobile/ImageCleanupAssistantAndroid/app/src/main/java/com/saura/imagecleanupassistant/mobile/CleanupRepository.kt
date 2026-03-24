package com.saura.imagecleanupassistant.mobile

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class RawMediaImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val modifiedAtMillis: Long
)

private data class SimilarPairCandidate(
    val pair: PairMatch,
    val sortKey: Long
)

data class CachedSessionState(
    val selectedSourceId: String,
    val selectedQueueId: CleanupQueueId,
    val activeReviewKey: String?,
    val screen: AppScreen,
    val dismissedEntryKeys: Set<String>,
    val lastScanMillis: Long?
)

class CleanupRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val aiVerdictCacheFile = File(context.filesDir, AI_VERDICT_CACHE_FILE_NAME)
    private val aiReviewer = AiForwardReviewer(context)

    fun restoreSnapshotFromCache(): CleanupSnapshot? {
        val images = readCachedImages() ?: return null
        if (images.isEmpty()) {
            return CleanupSnapshot.EMPTY
        }
        return buildSnapshotFromImages(images)
    }

    fun restoreUiSession(): CachedSessionState? {
        val preferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        if (!preferences.contains(KEY_SELECTED_QUEUE_ID)) {
            return null
        }

        val queueId = preferences.getString(KEY_SELECTED_QUEUE_ID, null)
            ?.runCatching { CleanupQueueId.valueOf(this) }
            ?.getOrNull()
            ?: CleanupQueueId.EXACT
        val selectedSourceId = preferences.getString(KEY_SELECTED_SOURCE_ID, ALL_SOURCE_ID)
            ?.ifBlank { ALL_SOURCE_ID }
            ?: ALL_SOURCE_ID
        val screen = preferences.getString(KEY_SCREEN, null)
            ?.runCatching { AppScreen.valueOf(this) }
            ?.getOrNull()
            ?: AppScreen.OVERVIEW
        val activeReviewKey = preferences.getString(KEY_ACTIVE_REVIEW_KEY, null)
        val dismissedKeys = preferences.getStringSet(KEY_DISMISSED_KEYS, emptySet()) ?: emptySet()
        val lastScanMillis = if (preferences.contains(KEY_LAST_SCAN_MILLIS)) {
            preferences.getLong(KEY_LAST_SCAN_MILLIS, 0L)
        } else {
            null
        }

        return CachedSessionState(
            selectedSourceId = selectedSourceId,
            selectedQueueId = queueId,
            activeReviewKey = activeReviewKey,
            screen = screen,
            dismissedEntryKeys = dismissedKeys.toSet(),
            lastScanMillis = lastScanMillis
        )
    }

    fun persistUiSession(state: UiState) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_SOURCE_ID, state.selectedSourceId)
            .putString(KEY_SELECTED_QUEUE_ID, state.selectedQueueId.name)
            .putString(KEY_ACTIVE_REVIEW_KEY, state.activeReviewKey)
            .putString(KEY_SCREEN, state.screen.name)
            .putStringSet(KEY_DISMISSED_KEYS, state.dismissedEntryKeys)
            .putLong(KEY_LAST_SCAN_MILLIS, state.lastScanMillis ?: 0L)
            .apply()
    }

    fun restoreAiModelConfig(): AiModelConfig? {
        val preferences = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
        val modelPath = preferences.getString(KEY_AI_MODEL_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            clearAiModel()
            return null
        }

        return AiModelConfig(
            modelPath = modelPath,
            modelName = preferences.getString(KEY_AI_MODEL_NAME, modelFile.name)
                ?.ifBlank { modelFile.name }
                ?: modelFile.name,
            sizeBytes = preferences.getLong(KEY_AI_MODEL_SIZE_BYTES, modelFile.length()),
            importedAtMillis = preferences.getLong(KEY_AI_MODEL_IMPORTED_AT, modelFile.lastModified()),
            lastModifiedAtMillis = preferences.getLong(KEY_AI_MODEL_LAST_MODIFIED_AT, modelFile.lastModified()),
            backendLabel = preferences.getString(KEY_AI_MODEL_BACKEND_LABEL, "GPU")
                ?.ifBlank { "GPU" }
                ?: "GPU"
        )
    }

    fun persistAiModelConfig(config: AiModelConfig?) {
        val preferences = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
        if (config == null) {
            preferences.clear().apply()
            return
        }

        preferences
            .putString(KEY_AI_MODEL_PATH, config.modelPath)
            .putString(KEY_AI_MODEL_NAME, config.modelName)
            .putLong(KEY_AI_MODEL_SIZE_BYTES, config.sizeBytes)
            .putLong(KEY_AI_MODEL_IMPORTED_AT, config.importedAtMillis)
            .putLong(KEY_AI_MODEL_LAST_MODIFIED_AT, config.lastModifiedAtMillis)
            .putString(KEY_AI_MODEL_BACKEND_LABEL, config.backendLabel)
            .apply()
    }

    fun restoreAiVerdictCache(modelConfig: AiModelConfig?): Map<Long, AiReviewVerdict> {
        modelConfig ?: return emptyMap()
        if (!aiVerdictCacheFile.exists()) {
            return emptyMap()
        }

        return runCatching {
            val root = JSONObject(aiVerdictCacheFile.readText())
            if (root.optString("modelKey") != modelConfig.modelKey) {
                return emptyMap()
            }

            val verdictsArray = root.optJSONArray("verdicts") ?: JSONArray()
            buildMap(verdictsArray.length()) {
                for (index in 0 until verdictsArray.length()) {
                    val verdict = verdictFromJson(verdictsArray.optJSONObject(index) ?: continue) ?: continue
                    put(verdict.imageId, verdict)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun persistAiVerdictCache(
        modelConfig: AiModelConfig?,
        verdicts: Map<Long, AiReviewVerdict>
    ) {
        modelConfig ?: run {
            runCatching { aiVerdictCacheFile.delete() }
            return
        }

        runCatching {
            val root = JSONObject()
            root.put("modelKey", modelConfig.modelKey)
            val verdictsArray = JSONArray()
            verdicts.values
                .sortedByDescending { it.reviewedAtMillis }
                .forEach { verdictsArray.put(verdictToJson(it)) }
            root.put("verdicts", verdictsArray)
            aiVerdictCacheFile.writeText(root.toString())
        }
    }

    fun pruneAiVerdicts(
        verdicts: Map<Long, AiReviewVerdict>,
        imagesById: Map<Long, MediaImage>
    ): Map<Long, AiReviewVerdict> =
        verdicts.filterValues { verdict ->
            imagesById[verdict.imageId]?.modifiedAtMillis == verdict.imageModifiedAtMillis
        }

    suspend fun importAiModel(sourceUri: Uri): AiModelConfig =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val fileName = queryDisplayName(sourceUri)
                ?.takeIf { it.endsWith(".litertlm", ignoreCase = true) }
                ?: throw IllegalArgumentException("Pick a .litertlm model file.")
            val modelDirectory = File(context.getExternalFilesDir("models") ?: context.filesDir, "ai-models")
            if (!modelDirectory.exists()) {
                modelDirectory.mkdirs()
            }
            modelDirectory.listFiles()
                ?.filter { it.extension.equals("litertlm", ignoreCase = true) }
                ?.forEach { existing ->
                    if (!existing.name.equals(fileName, ignoreCase = true)) {
                        existing.delete()
                    }
                }

            val destination = File(modelDirectory, fileName)
            resolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 4)
                }
            } ?: throw IllegalStateException("Unable to import the selected AI model.")

            val config = AiModelConfig(
                modelPath = destination.absolutePath,
                modelName = destination.nameWithoutExtension,
                sizeBytes = destination.length(),
                importedAtMillis = System.currentTimeMillis(),
                lastModifiedAtMillis = destination.lastModified(),
                backendLabel = "GPU"
            )
            persistAiModelConfig(config)
            persistAiVerdictCache(config, emptyMap())
            return@withContext config
        }

    fun clearAiModel() {
        restoreAiModelConfig()?.let { config ->
            runCatching { File(config.modelPath).delete() }
        }
        runCatching { aiVerdictCacheFile.delete() }
        persistAiModelConfig(null)
    }

    suspend fun reviewForwardImageWithAi(
        image: MediaImage,
        modelConfig: AiModelConfig
    ): AiReviewVerdict = aiReviewer.review(image, modelConfig)

    fun buildSourceOptions(snapshot: CleanupSnapshot): List<SourceOption> {
        val allCount = snapshot.imagesById.size
        val albumOptions = snapshot.imagesById.values
            .groupBy { it.folder.ifBlank { "Library" } }
            .map { (folder, images) ->
                SourceOption(
                    id = folder,
                    title = folder,
                    count = images.size
                )
            }
            .sortedWith(
                compareByDescending<SourceOption> { it.count }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )

        return listOf(
            SourceOption(
                id = ALL_SOURCE_ID,
                title = "All Photos",
                count = allCount
            )
        ) + albumOptions
    }

    fun filterSnapshotBySource(
        snapshot: CleanupSnapshot,
        sourceId: String
    ): CleanupSnapshot {
        if (sourceId == ALL_SOURCE_ID) {
            return snapshot
        }

        val filteredImages = snapshot.imagesById.values.filter { it.folder == sourceId }
        return buildSnapshotFromImages(filteredImages)
    }

    suspend fun scanLibrary(onProgress: (String) -> Unit = {}): CleanupSnapshot =
        withContext(Dispatchers.Default) {
            onProgress("Reading your photo library...")
            val rawImages = queryRawImages()
            if (rawImages.isEmpty()) {
                persistSnapshot(CleanupSnapshot.EMPTY)
                return@withContext CleanupSnapshot.EMPTY
            }

            val cachedImagesById = readCachedImages()
                ?.associateBy { it.id }
                .orEmpty()
            val hashCandidateSizes = rawImages
                .groupingBy { it.sizeBytes }
                .eachCount()
                .filter { it.key > 0L && it.value > 1 }
                .keys

            val analyzedImages = ArrayList<MediaImage>(rawImages.size)
            rawImages.forEachIndexed { index, raw ->
                if (index == 0 || (index + 1) % 15 == 0 || index == rawImages.lastIndex) {
                    onProgress("Analyzing ${index + 1} of ${rawImages.size} photos...")
                }

                val cached = cachedImagesById[raw.id]
                val reusable = cached != null &&
                    cached.sizeBytes == raw.sizeBytes &&
                    cached.modifiedAtMillis == raw.modifiedAtMillis &&
                    cached.name == raw.name &&
                    cached.folder == raw.folder

                analyzedImages += if (reusable) {
                    cached!!
                } else {
                    analyzeImage(raw, raw.sizeBytes in hashCandidateSizes)
                }
            }

            onProgress("Grouping cleanup queues...")
            val snapshot = buildSnapshotFromImages(analyzedImages)
            persistSnapshot(snapshot)
            return@withContext snapshot
        }

    fun buildQueueDefinitions(snapshot: CleanupSnapshot): List<QueueDefinition> =
        defaultQueueDefinitions().map { definition ->
            definition.copy(count = queueCount(snapshot, definition.id))
        }

    fun buildEntries(
        snapshot: CleanupSnapshot,
        queueId: CleanupQueueId,
        dismissedEntryKeys: Set<String> = emptySet()
    ): List<CleanupEntry> {
        val entries = when (queueId) {
            CleanupQueueId.EXACT -> snapshot.exactPairs.mapNotNull { pair ->
                val first = snapshot.imagesById[pair.firstId] ?: return@mapNotNull null
                val second = snapshot.imagesById[pair.secondId] ?: return@mapNotNull null
                CleanupEntry.PairEntry(
                    queueId = queueId,
                    pair = pair,
                    first = first,
                    second = second,
                    title = pair.label,
                    subtitle = pair.recommendationText,
                    metaText = "${first.dimensionsText} and ${second.dimensionsText}."
                )
            }

            CleanupQueueId.SIMILAR -> snapshot.similarPairs.mapNotNull { pair ->
                val first = snapshot.imagesById[pair.firstId] ?: return@mapNotNull null
                val second = snapshot.imagesById[pair.secondId] ?: return@mapNotNull null
                CleanupEntry.PairEntry(
                    queueId = queueId,
                    pair = pair,
                    first = first,
                    second = second,
                    title = pair.label,
                    subtitle = pair.recommendationText,
                    metaText = "${first.folder} and ${second.folder}."
                )
            }

            CleanupQueueId.BLURRY -> snapshot.blurryIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId,
                    image = image,
                    title = image.name,
                    subtitle = image.metrics.blurText,
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }

            CleanupQueueId.FORWARD -> snapshot.forwardIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId,
                    image = image,
                    title = image.name,
                    subtitle = "Likely WhatsApp forward, greeting, quote, or poster-style image.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }

            CleanupQueueId.SCREENSHOT -> snapshot.screenshotIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId,
                    image = image,
                    title = image.name,
                    subtitle = "Looks like a screenshot or screen capture.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }

            CleanupQueueId.TEXT_HEAVY -> snapshot.textHeavyIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId,
                    image = image,
                    title = image.name,
                    subtitle = "Text-heavy poster, quote, banner, or flyer style image.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }
        }

        return entries.filterNot { scopedEntryKey(queueId, it.key) in dismissedEntryKeys }
    }

    fun rebuildSnapshotAfterDelete(
        snapshot: CleanupSnapshot,
        deletedIds: Set<Long>
    ): CleanupSnapshot {
        if (deletedIds.isEmpty()) {
            return snapshot
        }

        val remainingImages = snapshot.imagesById.values.filterNot { it.id in deletedIds }
        val rebuilt = buildSnapshotFromImages(remainingImages)
        persistSnapshot(rebuilt)
        return rebuilt
    }

    fun queueCount(snapshot: CleanupSnapshot, queueId: CleanupQueueId): Int =
        when (queueId) {
            CleanupQueueId.EXACT -> snapshot.exactPairs.size
            CleanupQueueId.SIMILAR -> snapshot.similarPairs.size
            CleanupQueueId.BLURRY -> snapshot.blurryIds.size
            CleanupQueueId.FORWARD -> snapshot.forwardIds.size
            CleanupQueueId.SCREENSHOT -> snapshot.screenshotIds.size
            CleanupQueueId.TEXT_HEAVY -> snapshot.textHeavyIds.size
        }

    private fun persistSnapshot(snapshot: CleanupSnapshot) {
        runCatching {
            val root = JSONObject()
            val imagesArray = JSONArray()
            snapshot.imagesById.values
                .sortedByDescending { it.modifiedAtMillis }
                .forEach { image ->
                    imagesArray.put(imageToJson(image))
                }
            root.put("images", imagesArray)
            cacheFile.writeText(root.toString())
        }
    }

    private fun readCachedImages(): List<MediaImage>? {
        if (!cacheFile.exists()) {
            return null
        }

        return runCatching {
            val root = JSONObject(cacheFile.readText())
            val imagesArray = root.optJSONArray("images") ?: JSONArray()
            buildList(imagesArray.length()) {
                for (index in 0 until imagesArray.length()) {
                    val image = imageFromJson(imagesArray.optJSONObject(index) ?: continue)
                    if (image != null) {
                        add(image)
                    }
                }
            }
        }.getOrNull()
    }

    private fun imageToJson(image: MediaImage): JSONObject =
        JSONObject().apply {
            put("id", image.id)
            put("name", image.name)
            put("folder", image.folder)
            put("sizeBytes", image.sizeBytes)
            put("width", image.width)
            put("height", image.height)
            put("modifiedAtMillis", image.modifiedAtMillis)
            put("contentHash", image.contentHash)
            put("metrics", JSONObject().apply {
                put("averageHash", image.metrics.averageHash)
                put("gradientHash", image.metrics.gradientHash)
                put("toneSignature", JSONArray().apply {
                    image.metrics.toneSignature.forEach { put(it) }
                })
                put("sharpnessEstimate", image.metrics.sharpnessEstimate)
                put("brightness", image.metrics.brightness)
                put("edgeDensity", image.metrics.edgeDensity)
                put("averageSaturation", image.metrics.averageSaturation)
                put("whitePixelRatio", image.metrics.whitePixelRatio)
                put("uniqueColorCount", image.metrics.uniqueColorCount)
                put("qualityScore", image.metrics.qualityScore)
                put("likelyBlurry", image.metrics.likelyBlurry)
                put("blurText", image.metrics.blurText)
                put("likelyForward", image.metrics.likelyForward)
                put("likelyScreenshot", image.metrics.likelyScreenshot)
                put("likelyTextHeavy", image.metrics.likelyTextHeavy)
            })
        }

    private fun imageFromJson(objectJson: JSONObject?): MediaImage? {
        objectJson ?: return null
        val id = objectJson.optLong("id", -1L)
        if (id <= 0L) {
            return null
        }

        val metricsJson = objectJson.optJSONObject("metrics") ?: return null
        val toneArray = metricsJson.optJSONArray("toneSignature") ?: JSONArray()
        val toneSignature = IntArray(toneArray.length()) { index -> toneArray.optInt(index, 0) }
        return MediaImage(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            name = objectJson.optString("name", "Image $id"),
            folder = objectJson.optString("folder", "Library"),
            sizeBytes = objectJson.optLong("sizeBytes", 0L),
            width = objectJson.optInt("width", 1),
            height = objectJson.optInt("height", 1),
            modifiedAtMillis = objectJson.optLong("modifiedAtMillis", 0L),
            contentHash = objectJson.optString("contentHash").ifBlank { null },
            metrics = ImageMetrics(
                averageHash = metricsJson.optString("averageHash"),
                gradientHash = metricsJson.optString("gradientHash"),
                toneSignature = toneSignature,
                sharpnessEstimate = metricsJson.optDouble("sharpnessEstimate", 0.0),
                brightness = metricsJson.optDouble("brightness", 0.0),
                edgeDensity = metricsJson.optDouble("edgeDensity", 0.0),
                averageSaturation = metricsJson.optDouble("averageSaturation", 0.0),
                whitePixelRatio = metricsJson.optDouble("whitePixelRatio", 0.0),
                uniqueColorCount = metricsJson.optInt("uniqueColorCount", 0),
                qualityScore = metricsJson.optDouble("qualityScore", 0.0),
                likelyBlurry = metricsJson.optBoolean("likelyBlurry", false),
                blurText = metricsJson.optString("blurText"),
                likelyForward = metricsJson.optBoolean("likelyForward", false),
                likelyScreenshot = metricsJson.optBoolean("likelyScreenshot", false),
                likelyTextHeavy = metricsJson.optBoolean("likelyTextHeavy", false)
            )
        )
    }

    private fun verdictToJson(verdict: AiReviewVerdict): JSONObject =
        JSONObject().apply {
            put("imageId", verdict.imageId)
            put("imageModifiedAtMillis", verdict.imageModifiedAtMillis)
            put("label", verdict.label.name)
            put("confidence", verdict.confidence)
            put("reason", verdict.reason)
            put("modelName", verdict.modelName)
            put("reviewedAtMillis", verdict.reviewedAtMillis)
            put("backendLabel", verdict.backendLabel)
        }

    private fun verdictFromJson(objectJson: JSONObject?): AiReviewVerdict? {
        objectJson ?: return null
        val imageId = objectJson.optLong("imageId", -1L)
        if (imageId <= 0L) {
            return null
        }

        val label = objectJson.optString("label")
            .runCatching { AiReviewLabel.valueOf(this) }
            .getOrNull()
            ?: AiReviewLabel.UNSURE

        return AiReviewVerdict(
            imageId = imageId,
            imageModifiedAtMillis = objectJson.optLong("imageModifiedAtMillis", 0L),
            label = label,
            confidence = objectJson.optInt("confidence", 50),
            reason = objectJson.optString("reason", ""),
            modelName = objectJson.optString("modelName", "Gemma 3n E2B"),
            reviewedAtMillis = objectJson.optLong("reviewedAtMillis", 0L),
            backendLabel = objectJson.optString("backendLabel", "GPU")
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }

    private fun buildSnapshotFromImages(images: List<MediaImage>): CleanupSnapshot {
        val exactPairs = buildExactPairs(images)
        val excludedIds = exactPairs.flatMap { listOf(it.firstId, it.secondId) }.toSet()
        val similarPairs = buildSimilarPairs(images, excludedIds)

        val blurryIds = images
            .asSequence()
            .filter { it.metrics.likelyBlurry }
            .sortedWith(
                compareBy<MediaImage> { it.metrics.sharpnessEstimate }
                    .thenBy { it.metrics.edgeDensity }
                    .thenByDescending { it.modifiedAtMillis }
            )
            .map { it.id }
            .toList()

        val forwardIds = images
            .asSequence()
            .filter { it.metrics.likelyForward }
            .sortedByDescending { it.modifiedAtMillis }
            .map { it.id }
            .toList()

        val screenshotIds = images
            .asSequence()
            .filter { it.metrics.likelyScreenshot }
            .sortedByDescending { it.modifiedAtMillis }
            .map { it.id }
            .toList()

        val textHeavyIds = images
            .asSequence()
            .filter { it.metrics.likelyTextHeavy }
            .sortedByDescending { it.modifiedAtMillis }
            .map { it.id }
            .toList()

        return CleanupSnapshot(
            imagesById = images.associateBy { it.id },
            exactPairs = exactPairs,
            similarPairs = similarPairs,
            blurryIds = blurryIds,
            forwardIds = forwardIds,
            screenshotIds = screenshotIds,
            textHeavyIds = textHeavyIds
        )
    }

    private fun queryRawImages(): List<RawMediaImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val images = mutableListOf<RawMediaImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val folderIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                images += RawMediaImage(
                    id = id,
                    uri = uri,
                    name = cursor.getString(nameIndex).orEmpty().ifBlank { "Image $id" },
                    folder = cursor.getString(folderIndex).orEmpty().ifBlank { "Library" },
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    width = cursor.getInt(widthIndex).coerceAtLeast(0),
                    height = cursor.getInt(heightIndex).coerceAtLeast(0),
                    modifiedAtMillis = cursor.getLong(modifiedIndex).coerceAtLeast(0L) * 1000L
                )
            }
        }
        return images
    }

    private fun analyzeImage(raw: RawMediaImage, includeContentHash: Boolean): MediaImage {
        val analysis = analyzeBitmapMetrics(raw.uri, raw.width, raw.height)
        val qualityScore = computeQualityScore(
            width = analysis.width,
            height = analysis.height,
            sizeBytes = raw.sizeBytes,
            sharpnessEstimate = analysis.sharpnessEstimate
        )
        val nameAndFolder = "${raw.name} ${raw.folder}"
        val forwardScore = computeForwardScore(nameAndFolder, analysis)
        val screenshotScore = computeScreenshotScore(nameAndFolder, analysis)
        val textHeavyScore = computeTextHeavyScore(nameAndFolder, analysis)
        val blurScore = computeBlurScore(analysis)

        return MediaImage(
            id = raw.id,
            uri = raw.uri,
            name = raw.name,
            folder = raw.folder,
            sizeBytes = raw.sizeBytes,
            width = analysis.width,
            height = analysis.height,
            modifiedAtMillis = raw.modifiedAtMillis,
            contentHash = if (includeContentHash) computeContentHash(raw.uri) else null,
            metrics = ImageMetrics(
                averageHash = analysis.averageHash,
                gradientHash = analysis.gradientHash,
                toneSignature = analysis.toneSignature,
                sharpnessEstimate = analysis.sharpnessEstimate,
                brightness = analysis.brightness,
                edgeDensity = analysis.edgeDensity,
                averageSaturation = analysis.averageSaturation,
                whitePixelRatio = analysis.whitePixelRatio,
                uniqueColorCount = analysis.uniqueColorCount,
                qualityScore = qualityScore,
                likelyBlurry = blurScore >= 5 || (analysis.sharpnessEstimate <= 9.0 && analysis.edgeDensity <= 0.08),
                blurText = "Sharpness ${formatNumber(analysis.sharpnessEstimate, 1)} | edge detail ${formatNumber(analysis.edgeDensity, 3)}",
                likelyForward = forwardScore >= 5,
                likelyScreenshot = screenshotScore >= 5,
                likelyTextHeavy = textHeavyScore >= 5
            )
        )
    }

    private fun buildExactPairs(images: List<MediaImage>): List<PairMatch> {
        val pairs = mutableListOf<PairMatch>()
        images
            .filter { !it.contentHash.isNullOrBlank() }
            .groupBy { it.contentHash }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val sorted = group.sortedWith(
                    compareByDescending<MediaImage> { it.metrics.qualityScore }
                        .thenByDescending { it.sizeBytes }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
                val keeper = sorted.first()
                sorted.drop(1).forEach { duplicate ->
                    pairs += PairMatch(
                        firstId = keeper.id,
                        secondId = duplicate.id,
                        label = "${keeper.name} vs ${duplicate.name}",
                        suggestedDeleteId = duplicate.id,
                        confidence = 100,
                        matchScore = 1000.0,
                        recommendationText = "Exact duplicate. Suggested delete: ${duplicate.name}."
                    )
                }
            }
        return pairs.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun buildSimilarPairs(
        images: List<MediaImage>,
        excludedIds: Set<Long>
    ): List<PairMatch> {
        val candidates = mutableListOf<SimilarPairCandidate>()
        val eligible = images.filterNot { it.id in excludedIds }
        val ratioBuckets = eligible.groupBy { ((it.aspectRatio * 10.0).roundToInt()) / 10.0 }

        for (bucket in ratioBuckets.values) {
            val group = bucket.sortedWith(
                compareBy<MediaImage> { it.metrics.brightness }
                    .thenBy { max(it.width, it.height) }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            for (i in group.indices) {
                val first = group[i]
                for (j in i + 1 until group.size) {
                    val second = group[j]
                    if (abs(first.metrics.brightness - second.metrics.brightness) > 24.0) {
                        break
                    }

                    val pair = buildSimilarPair(first, second) ?: continue
                    candidates += SimilarPairCandidate(
                        pair = pair,
                        sortKey = max(first.modifiedAtMillis, second.modifiedAtMillis)
                    )
                }
            }
        }

        val usedIds = mutableSetOf<Long>()
        return candidates
            .sortedWith(
                compareByDescending<SimilarPairCandidate> { it.pair.matchScore }
                    .thenByDescending { it.sortKey }
                    .thenBy { it.pair.label.lowercase(Locale.ROOT) }
            )
            .mapNotNull { candidate ->
                if (candidate.pair.firstId in usedIds || candidate.pair.secondId in usedIds) {
                    null
                } else {
                    usedIds += candidate.pair.firstId
                    usedIds += candidate.pair.secondId
                    candidate.pair
                }
            }
    }

    private fun buildSimilarPair(first: MediaImage, second: MediaImage): PairMatch? {
        if (!first.contentHash.isNullOrBlank() && first.contentHash == second.contentHash) {
            return null
        }

        val aspectDifference = abs(first.aspectRatio - second.aspectRatio)
        if (aspectDifference > 0.02) {
            return null
        }

        val firstLongSide = max(first.width, first.height)
        val secondLongSide = max(second.width, second.height)
        val smallerLongSide = min(firstLongSide, secondLongSide).coerceAtLeast(1)
        val dimensionRatio = max(firstLongSide, secondLongSide).toDouble() / smallerLongSide.toDouble()
        if (dimensionRatio > 1.4) {
            return null
        }

        val brightnessDifference = abs(first.metrics.brightness - second.metrics.brightness)
        if (brightnessDifference > 18.0) {
            return null
        }

        val saturationDifference = abs(first.metrics.averageSaturation - second.metrics.averageSaturation)
        if (saturationDifference > 0.14) {
            return null
        }

        val whiteDifference = abs(first.metrics.whitePixelRatio - second.metrics.whitePixelRatio)
        if (whiteDifference > 0.14) {
            return null
        }

        val edgeDifference = abs(first.metrics.edgeDensity - second.metrics.edgeDensity)
        if (edgeDifference > 0.07) {
            return null
        }

        val averageHashDistance = hammingDistance(first.metrics.averageHash, second.metrics.averageHash)
        if (averageHashDistance > 3) {
            return null
        }

        val gradientHashDistance = hammingDistance(first.metrics.gradientHash, second.metrics.gradientHash)
        if (gradientHashDistance > 6) {
            return null
        }

        val toneDifference = meanAbsoluteDifference(first.metrics.toneSignature, second.metrics.toneSignature)
        if (toneDifference > 10.0) {
            return null
        }

        val nameAffinity = nameAffinityScore(first.name, second.name)
        val sameFolderBonus = if (first.folder.equals(second.folder, ignoreCase = true)) 4 else 0
        val modifiedDeltaDays = abs(first.modifiedAtMillis - second.modifiedAtMillis) / 86_400_000.0
        val timeAffinity = when {
            modifiedDeltaDays <= 1.0 -> 6
            modifiedDeltaDays <= 7.0 -> 3
            else -> 0
        }
        val strictVisualCluster =
            averageHashDistance <= 3 &&
                gradientHashDistance <= 6 &&
                toneDifference <= 10.0 &&
                dimensionRatio <= 1.2

        if (!(nameAffinity >= 10 || (sameFolderBonus > 0 && timeAffinity >= 3) || strictVisualCluster)) {
            return null
        }

        val score =
            (120 - (averageHashDistance * 13)) +
                (92 - (gradientHashDistance * 7)) +
                max(0.0, 42.0 - (toneDifference * 2.8)) +
                max(0.0, 22.0 - (brightnessDifference * 1.0)) +
                max(0.0, 14.0 - (saturationDifference * 90.0)) +
                max(0.0, 14.0 - (edgeDifference * 140.0)) +
                max(0.0, 12.0 - (aspectDifference * 500.0)) +
                nameAffinity +
                sameFolderBonus +
                timeAffinity

        if (score < 205.0) {
            return null
        }

        val suggestedDelete = selectDeleteCandidate(first, second)
        val keepImage = if (suggestedDelete.id == first.id) second else first
        val confidence = min(99, max(72, (score / 3.0).roundToInt()))

        return PairMatch(
            firstId = first.id,
            secondId = second.id,
            label = "${first.name} vs ${second.name}",
            suggestedDeleteId = suggestedDelete.id,
            confidence = confidence,
            matchScore = score,
            recommendationText = "Strong match (${confidence}%). Suggested delete: ${suggestedDelete.name}. Keep: ${keepImage.name}."
        )
    }

    private fun selectDeleteCandidate(first: MediaImage, second: MediaImage): MediaImage {
        if (first.metrics.qualityScore < second.metrics.qualityScore) {
            return first
        }
        if (second.metrics.qualityScore < first.metrics.qualityScore) {
            return second
        }

        val firstPenalty = deleteNamePenalty(first.name)
        val secondPenalty = deleteNamePenalty(second.name)
        if (firstPenalty > secondPenalty) {
            return first
        }
        if (secondPenalty > firstPenalty) {
            return second
        }

        if (first.sizeBytes < second.sizeBytes) {
            return first
        }
        if (second.sizeBytes < first.sizeBytes) {
            return second
        }

        return if (first.modifiedAtMillis <= second.modifiedAtMillis) first else second
    }

    private fun computeContentHash(uri: Uri): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
        val decoded = decodeBitmap(uri, targetSide = 96) ?: return BitmapAnalysis(
            width = fallbackWidth.coerceAtLeast(1),
            height = fallbackHeight.coerceAtLeast(1),
            averageHash = "0".repeat(64),
            gradientHash = "0".repeat(64),
            toneSignature = IntArray(36),
            sharpnessEstimate = 0.0,
            brightness = 0.0,
            edgeDensity = 0.0,
            averageSaturation = 0.0,
            whitePixelRatio = 0.0,
            uniqueColorCount = 0
        )

        val baseBitmap = decoded.bitmap
        val sample8 = Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
        val sample9x8 = Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
        val sample24 = Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)

        return try {
            val brightnessValues = DoubleArray(64)
            var brightnessTotal = 0.0
            var contrastTotal = 0.0
            var index = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixel = sample8.getPixel(x, y)
                    val gray = grayscale(pixel)
                    brightnessValues[index++] = gray
                    brightnessTotal += gray

                    if (x > 0) {
                        contrastTotal += abs(gray - grayscale(sample8.getPixel(x - 1, y)))
                    }
                    if (y > 0) {
                        contrastTotal += abs(gray - grayscale(sample8.getPixel(x, y - 1)))
                    }
                }
            }

            val averageGray = brightnessValues.average()
            val averageHash = buildString(64) {
                brightnessValues.forEach { value ->
                    append(if (value >= averageGray) '1' else '0')
                }
            }

            val gradientHash = buildString(64) {
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val leftGray = grayscale(sample9x8.getPixel(x, y))
                        val rightGray = grayscale(sample9x8.getPixel(x + 1, y))
                        append(if (leftGray >= rightGray) '1' else '0')
                    }
                }
            }

            val colorMap = HashSet<String>()
            var whitePixels = 0
            var saturationTotal = 0.0
            var strongEdges = 0
            val toneSignature = IntArray(36)
            var toneIndex = 0

            for (y in 0 until 24) {
                for (x in 0 until 24) {
                    val pixel = sample24.getPixel(x, y)
                    val gray = grayscale(pixel)
                    val red = android.graphics.Color.red(pixel)
                    val green = android.graphics.Color.green(pixel)
                    val blue = android.graphics.Color.blue(pixel)
                    val maxChannel = max(red, max(green, blue))
                    val minChannel = min(red, min(green, blue))
                    saturationTotal += if (maxChannel == 0) 0.0 else (maxChannel - minChannel).toDouble() / maxChannel.toDouble()
                    colorMap += "${red / 32}-${green / 32}-${blue / 32}"

                    if (gray >= 235.0) {
                        whitePixels += 1
                    }
                    if (x > 0 && abs(gray - grayscale(sample24.getPixel(x - 1, y))) >= 55.0) {
                        strongEdges += 1
                    }
                    if (y > 0 && abs(gray - grayscale(sample24.getPixel(x, y - 1))) >= 55.0) {
                        strongEdges += 1
                    }
                }
            }

            for (blockY in 0 until 6) {
                for (blockX in 0 until 6) {
                    var blockTotal = 0.0
                    for (innerY in 0 until 4) {
                        for (innerX in 0 until 4) {
                            blockTotal += grayscale(sample24.getPixel((blockX * 4) + innerX, (blockY * 4) + innerY))
                        }
                    }
                    toneSignature[toneIndex++] = (blockTotal / 16.0).roundToInt()
                }
            }

            BitmapAnalysis(
                width = decoded.width.coerceAtLeast(fallbackWidth).coerceAtLeast(1),
                height = decoded.height.coerceAtLeast(fallbackHeight).coerceAtLeast(1),
                averageHash = averageHash,
                gradientHash = gradientHash,
                toneSignature = toneSignature,
                sharpnessEstimate = contrastTotal / 112.0,
                brightness = brightnessTotal / 64.0,
                edgeDensity = strongEdges / 1104.0,
                averageSaturation = saturationTotal / 576.0,
                whitePixelRatio = whitePixels / 576.0,
                uniqueColorCount = colorMap.size
            )
        } finally {
            sample8.recycle()
            sample9x8.recycle()
            sample24.recycle()
            baseBitmap.recycle()
        }
    }

    private fun decodeBitmap(uri: Uri, targetSide: Int): DecodedBitmap? {
        return runCatching {
            var originalWidth = 0
            var originalHeight = 0
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                originalWidth = info.size.width
                originalHeight = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val maxSide = max(originalWidth, originalHeight).coerceAtLeast(1)
                decoder.setTargetSampleSize((maxSide / targetSide).coerceAtLeast(1))
                decoder.isMutableRequired = false
            }
            DecodedBitmap(
                bitmap = bitmap,
                width = originalWidth.coerceAtLeast(1),
                height = originalHeight.coerceAtLeast(1)
            )
        }.getOrNull()
    }

    private fun computeQualityScore(
        width: Int,
        height: Int,
        sizeBytes: Long,
        sharpnessEstimate: Double
    ): Double {
        val megapixels = (width * height) / 1_000_000.0
        val sizeMegabytes = sizeBytes / 1_000_000.0
        return (megapixels * 3.8) + (sharpnessEstimate * 1.8) + (sizeMegabytes * 0.7)
    }

    private fun computeForwardScore(nameAndFolder: String, metrics: BitmapAnalysis): Int {
        var score = 0
        val hasKeyword = FORWARD_REGEX.containsMatchIn(nameAndFolder)
        if (hasKeyword) {
            score += 4
        }
        if (metrics.averageSaturation >= 0.24) score += 1
        if (metrics.uniqueColorCount <= 110) score += 1
        if (metrics.edgeDensity >= 0.12) score += 1
        if (metrics.whitePixelRatio <= 0.45) score += 1
        if ((metrics.width.toDouble() / metrics.height.coerceAtLeast(1).toDouble()) <= 1.35) score += 1
        if (metrics.width <= 2200 && metrics.height <= 2200) score += 1
        return if (hasKeyword || (metrics.uniqueColorCount <= 85 && metrics.edgeDensity >= 0.16)) score else 0
    }

    private fun computeScreenshotScore(nameAndFolder: String, metrics: BitmapAnalysis): Int {
        var score = 0
        val hasKeyword = SCREENSHOT_REGEX.containsMatchIn(nameAndFolder)
        if (hasKeyword) {
            score += 3
        }
        val ratio = max(
            metrics.width.toDouble() / metrics.height.coerceAtLeast(1).toDouble(),
            metrics.height.toDouble() / metrics.width.coerceAtLeast(1).toDouble()
        )
        if (ratio in 1.65..2.35) score += 1
        if (metrics.edgeDensity >= 0.14) score += 1
        if (metrics.whitePixelRatio >= 0.24) score += 1
        if (metrics.averageSaturation <= 0.34) score += 1
        return if (hasKeyword || (ratio in 1.7..2.3 && metrics.whitePixelRatio >= 0.28 && metrics.edgeDensity >= 0.15)) score else 0
    }

    private fun computeTextHeavyScore(nameAndFolder: String, metrics: BitmapAnalysis): Int {
        var score = 0
        val hasKeyword = TEXT_HEAVY_REGEX.containsMatchIn(nameAndFolder)
        if (hasKeyword) {
            score += 3
        }
        if (metrics.edgeDensity >= 0.18) score += 1
        if (metrics.whitePixelRatio >= 0.30) score += 1
        if (metrics.uniqueColorCount <= 90) score += 1
        if (metrics.averageSaturation <= 0.32) score += 1
        if (metrics.width <= 1800 && metrics.height <= 1800) score += 1
        return if (hasKeyword || (metrics.whitePixelRatio >= 0.34 && metrics.edgeDensity >= 0.18 && metrics.uniqueColorCount <= 85)) score else 0
    }

    private fun computeBlurScore(metrics: BitmapAnalysis): Int {
        var score = 0
        when {
            metrics.sharpnessEstimate <= 8.0 -> score += 4
            metrics.sharpnessEstimate <= 12.0 -> score += 3
            metrics.sharpnessEstimate <= 16.0 -> score += 2
            metrics.sharpnessEstimate <= 18.0 -> score += 1
        }
        when {
            metrics.edgeDensity <= 0.05 -> score += 2
            metrics.edgeDensity <= 0.07 -> score += 1
        }
        return score
    }

    private fun hammingDistance(first: String, second: String): Int {
        val length = min(first.length, second.length)
        var distance = abs(first.length - second.length)
        for (index in 0 until length) {
            if (first[index] != second[index]) {
                distance += 1
            }
        }
        return distance
    }

    private fun meanAbsoluteDifference(first: IntArray, second: IntArray): Double {
        if (first.isEmpty() || second.isEmpty()) {
            return 100.0
        }
        val length = min(first.size, second.size)
        var total = 0.0
        for (index in 0 until length) {
            total += abs(first[index] - second[index]).toDouble()
        }
        total += abs(first.size - second.size) * 16.0
        return total / length.toDouble()
    }

    private fun deleteNamePenalty(name: String): Int =
        if (DELETE_NAME_REGEX.containsMatchIn(name)) 3 else 0

    private fun nameAffinityScore(firstName: String, secondName: String): Int {
        val firstCore = normalizeNameCore(firstName)
        val secondCore = normalizeNameCore(secondName)
        if (firstCore.isBlank() || secondCore.isBlank()) {
            return 0
        }
        if (firstCore == secondCore) {
            return 16
        }
        if (firstCore.contains(secondCore) || secondCore.contains(firstCore)) {
            return 10
        }

        val firstWords = firstCore.split(' ').filter { it.length >= 3 }.toSet()
        val secondWords = secondCore.split(' ').filter { it.length >= 3 }.toSet()
        if (firstWords.isEmpty() || secondWords.isEmpty()) {
            return 0
        }
        val commonWordCount = firstWords.intersect(secondWords).size
        if (commonWordCount == 0) {
            return 0
        }
        return min(12, commonWordCount * 4)
    }

    private fun normalizeNameCore(name: String): String =
        name
            .substringBeforeLast('.')
            .replace(Regex("(?i)(copy|edited|duplicate)"), " ")
            .replace(Regex("[_\\-()\\[\\].]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

    private fun grayscale(pixel: Int): Double {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return (0.299 * red) + (0.587 * green) + (0.114 * blue)
    }

    private fun formatNumber(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int
    )

    private data class BitmapAnalysis(
        val width: Int,
        val height: Int,
        val averageHash: String,
        val gradientHash: String,
        val toneSignature: IntArray,
        val sharpnessEstimate: Double,
        val brightness: Double,
        val edgeDensity: Double,
        val averageSaturation: Double,
        val whitePixelRatio: Double,
        val uniqueColorCount: Int
    )

    private companion object {
        const val CACHE_FILE_NAME = "image_cleanup_cache.json"
        const val AI_VERDICT_CACHE_FILE_NAME = "image_cleanup_ai_verdicts.json"
        const val SESSION_PREFS = "image_cleanup_session"
        const val AI_PREFS = "image_cleanup_ai"
        const val KEY_SELECTED_SOURCE_ID = "selected_source_id"
        const val KEY_SELECTED_QUEUE_ID = "selected_queue_id"
        const val KEY_ACTIVE_REVIEW_KEY = "active_review_key"
        const val KEY_SCREEN = "screen"
        const val KEY_DISMISSED_KEYS = "dismissed_keys"
        const val KEY_LAST_SCAN_MILLIS = "last_scan_millis"
        const val KEY_AI_MODEL_PATH = "ai_model_path"
        const val KEY_AI_MODEL_NAME = "ai_model_name"
        const val KEY_AI_MODEL_SIZE_BYTES = "ai_model_size_bytes"
        const val KEY_AI_MODEL_IMPORTED_AT = "ai_model_imported_at"
        const val KEY_AI_MODEL_LAST_MODIFIED_AT = "ai_model_last_modified_at"
        const val KEY_AI_MODEL_BACKEND_LABEL = "ai_model_backend_label"

        val FORWARD_REGEX = Regex(
            "(?i)(diwali|holi|eid|christmas|xmas|new.?year|good.?morning|good.?night|quote|motivational|motivation|blessing|blessings|shayari|status|suvichar|thought|wish|greeting|festival|birthday|anniversary|invitation)"
        )
        val SCREENSHOT_REGEX = Regex("(?i)(screenshot|screen.?shot|screen_capture|capture|screenshots)")
        val TEXT_HEAVY_REGEX = Regex(
            "(?i)(quote|motivational|motivation|shayari|suvichar|thought|wish|greeting|invitation|poster|banner|flyer|notice|good.?morning|good.?night|festival|diwali|holi|new.?year)"
        )
        val DELETE_NAME_REGEX = Regex("(?i)(copy|edited|duplicate|_copy|-copy|\\(\\d+\\))")
    }
}
