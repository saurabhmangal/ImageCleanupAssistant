package com.saura.imagecleanupassistant.mobile

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
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

    fun restoreSnapshotFromCache(): CleanupSnapshot? {
        val images = readCachedImages() ?: return null
        if (images.isEmpty()) return CleanupSnapshot.EMPTY
        return buildSnapshotFromImages(images)
    }

    fun restoreUiSession(): CachedSessionState? {
        val preferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        if (!preferences.contains(KEY_SELECTED_QUEUE_ID)) return null

        val queueId = preferences.getString(KEY_SELECTED_QUEUE_ID, null)
            ?.runCatching { CleanupQueueId.valueOf(this) }
            ?.getOrNull()
            ?: CleanupQueueId.EXACT
        val selectedSourceId = preferences.getString(KEY_SELECTED_SOURCE_ID, ALL_SOURCE_ID)
            ?.ifBlank { ALL_SOURCE_ID } ?: ALL_SOURCE_ID
        val screen = preferences.getString(KEY_SCREEN, null)
            ?.runCatching { AppScreen.valueOf(this) }
            ?.getOrNull()
            ?: AppScreen.OVERVIEW
        val activeReviewKey = preferences.getString(KEY_ACTIVE_REVIEW_KEY, null)
        val dismissedKeys = preferences.getStringSet(KEY_DISMISSED_KEYS, emptySet()) ?: emptySet()
        val lastScanMillis = if (preferences.contains(KEY_LAST_SCAN_MILLIS)) {
            preferences.getLong(KEY_LAST_SCAN_MILLIS, 0L)
        } else null

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

    suspend fun queryAvailableFolders(): List<FolderOption> =
        withContext(Dispatchers.IO) {
            val folders = mutableMapOf<String, Int>()
            val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val folderIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folder = cursor.getString(folderIndex).orEmpty().ifBlank { "Library" }
                    folders[folder] = (folders[folder] ?: 0) + 1
                }
            }
            folders.entries
                .map { (folder, count) -> FolderOption(folder = folder, count = count) }
                .sortedWith(
                    compareByDescending<FolderOption> { it.count }
                        .thenBy { it.folder.lowercase(Locale.ROOT) }
                )
        }

    fun buildSourceOptions(snapshot: CleanupSnapshot): List<SourceOption> {
        val allCount = snapshot.imagesById.size
        val albumOptions = snapshot.imagesById.values
            .groupBy { it.folder.ifBlank { "Library" } }
            .map { (folder, images) ->
                SourceOption(id = folder, title = folder, count = images.size)
            }
            .sortedWith(
                compareByDescending<SourceOption> { it.count }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )

        return listOf(SourceOption(id = ALL_SOURCE_ID, title = "All Photos", count = allCount)) + albumOptions
    }

    fun filterSnapshotBySource(snapshot: CleanupSnapshot, sourceId: String): CleanupSnapshot {
        if (sourceId == ALL_SOURCE_ID) return snapshot
        val filteredImages = snapshot.imagesById.values.filter { it.folder == sourceId }
        return buildSnapshotFromImages(filteredImages)
    }

    suspend fun scanLibrary(
        folderFilter: String? = null,
        onProgress: (String) -> Unit = {}
    ): CleanupSnapshot =
        withContext(Dispatchers.Default) {
            onProgress(
                if (folderFilter != null) "Reading photos from $folderFilter..."
                else "Reading your photo library..."
            )
            val rawImages = queryRawImages(folderFilter)
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

                analyzedImages += if (reusable) cached!! else analyzeImage(raw, raw.sizeBytes in hashCandidateSizes)
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
                    queueId = queueId, pair = pair, first = first, second = second,
                    title = pair.label, subtitle = pair.recommendationText,
                    metaText = "${first.dimensionsText} and ${second.dimensionsText}."
                )
            }
            CleanupQueueId.SIMILAR -> snapshot.similarPairs.mapNotNull { pair ->
                val first = snapshot.imagesById[pair.firstId] ?: return@mapNotNull null
                val second = snapshot.imagesById[pair.secondId] ?: return@mapNotNull null
                CleanupEntry.PairEntry(
                    queueId = queueId, pair = pair, first = first, second = second,
                    title = pair.label, subtitle = pair.recommendationText,
                    metaText = "${first.folder} and ${second.folder}."
                )
            }
            CleanupQueueId.BLURRY -> snapshot.blurryIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId, image = image, title = image.name,
                    subtitle = image.metrics.blurText,
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }
            CleanupQueueId.FORWARD -> snapshot.forwardIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId, image = image, title = image.name,
                    subtitle = "Likely meme, sticker, quote card, greeting, or WhatsApp-style share image.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }
            CleanupQueueId.SCREENSHOT -> snapshot.screenshotIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId, image = image, title = image.name,
                    subtitle = "Looks like a phone, app, or browser screen capture.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }
            CleanupQueueId.TEXT_HEAVY -> snapshot.textHeavyIds.mapNotNull { id ->
                val image = snapshot.imagesById[id] ?: return@mapNotNull null
                CleanupEntry.SingleEntry(
                    queueId = queueId, image = image, title = image.name,
                    subtitle = "Looks like a receipt, scan, ID, form, ticket, or document image.",
                    metaText = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}"
                )
            }
        }
        return entries.filterNot { scopedEntryKey(queueId, it.key) in dismissedEntryKeys }
    }

    fun rebuildSnapshotAfterDelete(snapshot: CleanupSnapshot, deletedIds: Set<Long>): CleanupSnapshot {
        if (deletedIds.isEmpty()) return snapshot
        val remainingImages = snapshot.imagesById.values.filterNot { it.id in deletedIds }
        val rebuilt = buildSnapshotFromImages(remainingImages)
        persistSnapshot(rebuilt)
        return rebuilt
    }

    fun findImagesByIds(snapshot: CleanupSnapshot, imageIds: Collection<Long>): List<MediaImage> =
        imageIds.mapNotNull { snapshot.imagesById[it] }

    fun openRemoteImage(image: MediaImage): RemoteImagePayload? {
        val stream = context.contentResolver.openInputStream(image.uri) ?: return null
        return RemoteImagePayload(
            fileName = image.name,
            mimeType = mimeTypeForImage(image.name),
            stream = stream
        )
    }

    suspend fun deleteImagesDirect(images: Collection<MediaImage>): DirectDeleteResult =
        withContext(Dispatchers.IO) {
            val deletedIds = linkedSetOf<Long>()
            val errors = mutableListOf<String>()

            images.forEach { image ->
                runCatching {
                    val deletedRows = context.contentResolver.delete(image.uri, null, null)
                    if (deletedRows > 0) {
                        deletedIds += image.id
                    } else {
                        errors += "${image.name}: Android did not confirm the delete."
                    }
                }.onFailure { error ->
                    errors += "${image.name}: ${error.message ?: "Delete failed."}"
                }
            }

            DirectDeleteResult(
                deletedIds = deletedIds,
                errors = errors
            )
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

    // ── Persistence ─────────────────────────────────────────────────────────────

    private fun persistSnapshot(snapshot: CleanupSnapshot) {
        runCatching {
            val root = JSONObject()
            val imagesArray = JSONArray()
            snapshot.imagesById.values
                .sortedByDescending { it.modifiedAtMillis }
                .forEach { image -> imagesArray.put(imageToJson(image)) }
            root.put("images", imagesArray)
            cacheFile.writeText(root.toString())
        }
    }

    private fun readCachedImages(): List<MediaImage>? {
        if (!cacheFile.exists()) return null
        return runCatching {
            val root = JSONObject(cacheFile.readText())
            val imagesArray = root.optJSONArray("images") ?: JSONArray()
            buildList(imagesArray.length()) {
                for (index in 0 until imagesArray.length()) {
                    val image = imageFromJson(imagesArray.optJSONObject(index) ?: continue)
                    if (image != null) add(image)
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
                put("darkPixelRatio", image.metrics.darkPixelRatio)
                put("dominantColorShare", image.metrics.dominantColorShare)
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
        if (id <= 0L) return null
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
                darkPixelRatio = metricsJson.optDouble("darkPixelRatio", 0.0),
                dominantColorShare = metricsJson.optDouble("dominantColorShare", 0.0),
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

    // ── Image Analysis ──────────────────────────────────────────────────────────

    private fun buildSnapshotFromImages(images: List<MediaImage>): CleanupSnapshot {
        val exactPairs = buildExactPairs(images)
        val excludedIds = exactPairs.flatMap { listOf(it.firstId, it.secondId) }.toSet()
        val similarPairs = buildSimilarPairs(images, excludedIds)

        return CleanupSnapshot(
            imagesById = images.associateBy { it.id },
            exactPairs = exactPairs,
            similarPairs = similarPairs,
            blurryIds = images.asSequence()
                .filter { it.metrics.likelyBlurry }
                .sortedWith(
                    compareBy<MediaImage> { it.metrics.qualityScore }
                        .thenByDescending { it.metrics.darkPixelRatio }
                        .thenBy { it.metrics.sharpnessEstimate }
                        .thenByDescending { it.modifiedAtMillis }
                )
                .map { it.id }.toList(),
            forwardIds = images.asSequence().filter { it.metrics.likelyForward }.sortedByDescending { it.modifiedAtMillis }.map { it.id }.toList(),
            screenshotIds = images.asSequence().filter { it.metrics.likelyScreenshot }.sortedByDescending { it.modifiedAtMillis }.map { it.id }.toList(),
            textHeavyIds = images.asSequence()
                .filter { it.metrics.likelyTextHeavy }
                .sortedWith(compareByDescending<MediaImage> { it.metrics.whitePixelRatio }.thenByDescending { it.modifiedAtMillis })
                .map { it.id }.toList()
        )
    }

    private fun queryRawImages(folderFilter: String? = null): List<RawMediaImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val selection = if (folderFilter != null) "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" else null
        val selectionArgs = if (folderFilter != null) arrayOf(folderFilter) else null

        val images = mutableListOf<RawMediaImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
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
                images += RawMediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
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
        val qualityScore = computeQualityScore(analysis.width, analysis.height, raw.sizeBytes, analysis.sharpnessEstimate)
        val nameAndFolder = "${raw.name} ${raw.folder}"
        val screenshotScore = computeScreenshotScore(nameAndFolder, analysis, analysis.width, analysis.height)
        val documentScore = computeDocumentScore(nameAndFolder, analysis, analysis.width, analysis.height)
        val clutterScore = computeMessagingClutterScore(nameAndFolder, analysis, raw.sizeBytes, analysis.width, analysis.height)
        val lowQualityScore = computeLowQualityScore(analysis, raw.sizeBytes, analysis.width, analysis.height, qualityScore)
        val likelyScreenshot = screenshotScore >= 5
        val likelyDocument = documentScore >= 5 && !(likelyScreenshot && documentScore < 7)
        val likelyClutter = clutterScore >= 5 && !likelyDocument && !(likelyScreenshot && clutterScore < 7)
        val likelyLowQuality = lowQualityScore >= 5 &&
            !(likelyDocument && documentScore >= 7 && lowQualityScore < 7) &&
            !(likelyScreenshot && screenshotScore >= 7 && lowQualityScore < 7)

        return MediaImage(
            id = raw.id, uri = raw.uri, name = raw.name, folder = raw.folder,
            sizeBytes = raw.sizeBytes, width = analysis.width, height = analysis.height,
            modifiedAtMillis = raw.modifiedAtMillis,
            contentHash = if (includeContentHash) computeContentHash(raw.uri) else null,
            metrics = ImageMetrics(
                averageHash = analysis.averageHash, gradientHash = analysis.gradientHash,
                toneSignature = analysis.toneSignature,
                sharpnessEstimate = analysis.sharpnessEstimate, brightness = analysis.brightness,
                edgeDensity = analysis.edgeDensity, averageSaturation = analysis.averageSaturation,
                whitePixelRatio = analysis.whitePixelRatio, darkPixelRatio = analysis.darkPixelRatio,
                dominantColorShare = analysis.dominantColorShare, uniqueColorCount = analysis.uniqueColorCount,
                qualityScore = qualityScore,
                likelyBlurry = likelyLowQuality,
                blurText = buildLowQualitySummary(analysis, raw.sizeBytes, analysis.width, analysis.height),
                likelyForward = likelyClutter,
                likelyScreenshot = likelyScreenshot,
                likelyTextHeavy = likelyDocument
            )
        )
    }

    private fun buildExactPairs(images: List<MediaImage>): List<PairMatch> {
        val pairs = mutableListOf<PairMatch>()
        images.filter { !it.contentHash.isNullOrBlank() }
            .groupBy { it.contentHash }
            .values.filter { it.size > 1 }
            .forEach { group ->
                val sorted = group.sortedWith(
                    compareByDescending<MediaImage> { it.metrics.qualityScore }
                        .thenByDescending { it.sizeBytes }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
                val keeper = sorted.first()
                sorted.drop(1).forEach { duplicate ->
                    pairs += PairMatch(
                        firstId = keeper.id, secondId = duplicate.id,
                        label = "${keeper.name} vs ${duplicate.name}",
                        suggestedDeleteId = duplicate.id, confidence = 100, matchScore = 1000.0,
                        recommendationText = "Exact duplicate. Suggested delete: ${duplicate.name}."
                    )
                }
            }
        return pairs.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun buildSimilarPairs(images: List<MediaImage>, excludedIds: Set<Long>): List<PairMatch> {
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
                    if (abs(first.metrics.brightness - second.metrics.brightness) > 24.0) break
                    val pair = buildSimilarPair(first, second) ?: continue
                    candidates += SimilarPairCandidate(pair = pair, sortKey = max(first.modifiedAtMillis, second.modifiedAtMillis))
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
                if (candidate.pair.firstId in usedIds || candidate.pair.secondId in usedIds) null
                else {
                    usedIds += candidate.pair.firstId
                    usedIds += candidate.pair.secondId
                    candidate.pair
                }
            }
    }

    private fun buildSimilarPair(first: MediaImage, second: MediaImage): PairMatch? {
        if (!first.contentHash.isNullOrBlank() && first.contentHash == second.contentHash) return null
        val aspectDifference = abs(first.aspectRatio - second.aspectRatio)
        if (aspectDifference > 0.02) return null
        val firstLongSide = max(first.width, first.height)
        val secondLongSide = max(second.width, second.height)
        val smallerLongSide = min(firstLongSide, secondLongSide).coerceAtLeast(1)
        val dimensionRatio = max(firstLongSide, secondLongSide).toDouble() / smallerLongSide.toDouble()
        if (dimensionRatio > 1.4) return null
        val brightnessDifference = abs(first.metrics.brightness - second.metrics.brightness)
        if (brightnessDifference > 18.0) return null
        val saturationDifference = abs(first.metrics.averageSaturation - second.metrics.averageSaturation)
        if (saturationDifference > 0.14) return null
        val whiteDifference = abs(first.metrics.whitePixelRatio - second.metrics.whitePixelRatio)
        if (whiteDifference > 0.14) return null
        val edgeDifference = abs(first.metrics.edgeDensity - second.metrics.edgeDensity)
        if (edgeDifference > 0.07) return null
        val averageHashDistance = hammingDistance(first.metrics.averageHash, second.metrics.averageHash)
        if (averageHashDistance > 3) return null
        val gradientHashDistance = hammingDistance(first.metrics.gradientHash, second.metrics.gradientHash)
        if (gradientHashDistance > 6) return null
        val toneDifference = meanAbsoluteDifference(first.metrics.toneSignature, second.metrics.toneSignature)
        if (toneDifference > 10.0) return null
        val nameAffinity = nameAffinityScore(first.name, second.name)
        val sameFolderBonus = if (first.folder.equals(second.folder, ignoreCase = true)) 4 else 0
        val modifiedDeltaDays = abs(first.modifiedAtMillis - second.modifiedAtMillis) / 86_400_000.0
        val timeAffinity = when {
            modifiedDeltaDays <= 1.0 -> 6
            modifiedDeltaDays <= 7.0 -> 3
            else -> 0
        }
        val strictVisualCluster = averageHashDistance <= 3 && gradientHashDistance <= 6 && toneDifference <= 10.0 && dimensionRatio <= 1.2
        if (!(nameAffinity >= 10 || (sameFolderBonus > 0 && timeAffinity >= 3) || strictVisualCluster)) return null

        val score =
            (120 - (averageHashDistance * 13)) +
                (92 - (gradientHashDistance * 7)) +
                max(0.0, 42.0 - (toneDifference * 2.8)) +
                max(0.0, 22.0 - (brightnessDifference * 1.0)) +
                max(0.0, 14.0 - (saturationDifference * 90.0)) +
                max(0.0, 14.0 - (edgeDifference * 140.0)) +
                max(0.0, 12.0 - (aspectDifference * 500.0)) +
                nameAffinity + sameFolderBonus + timeAffinity

        if (score < 205.0) return null

        val suggestedDelete = selectDeleteCandidate(first, second)
        val keepImage = if (suggestedDelete.id == first.id) second else first
        val confidence = min(99, max(72, (score / 3.0).roundToInt()))

        return PairMatch(
            firstId = first.id, secondId = second.id,
            label = "${first.name} vs ${second.name}",
            suggestedDeleteId = suggestedDelete.id, confidence = confidence, matchScore = score,
            recommendationText = "Strong match (${confidence}%). Suggested delete: ${suggestedDelete.name}. Keep: ${keepImage.name}."
        )
    }

    private fun selectDeleteCandidate(first: MediaImage, second: MediaImage): MediaImage {
        if (first.metrics.qualityScore < second.metrics.qualityScore) return first
        if (second.metrics.qualityScore < first.metrics.qualityScore) return second
        val firstPenalty = deleteNamePenalty(first.name) + cleanupPenalty(first)
        val secondPenalty = deleteNamePenalty(second.name) + cleanupPenalty(second)
        if (firstPenalty > secondPenalty) return first
        if (secondPenalty > firstPenalty) return second
        if (first.sizeBytes < second.sizeBytes) return first
        if (second.sizeBytes < first.sizeBytes) return second
        return if (first.modifiedAtMillis <= second.modifiedAtMillis) first else second
    }

    private fun computeContentHash(uri: Uri): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
        val decoded = decodeBitmap(uri, targetSide = 96) ?: return BitmapAnalysis(
            width = fallbackWidth.coerceAtLeast(1), height = fallbackHeight.coerceAtLeast(1),
            averageHash = "0".repeat(64), gradientHash = "0".repeat(64),
            toneSignature = IntArray(36), sharpnessEstimate = 0.0, brightness = 0.0,
            edgeDensity = 0.0, averageSaturation = 0.0, whitePixelRatio = 0.0,
            darkPixelRatio = 0.0, dominantColorShare = 0.0, uniqueColorCount = 0
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
                    if (x > 0) contrastTotal += abs(gray - grayscale(sample8.getPixel(x - 1, y)))
                    if (y > 0) contrastTotal += abs(gray - grayscale(sample8.getPixel(x, y - 1)))
                }
            }

            val averageGray = brightnessValues.average()
            val averageHash = buildString(64) { brightnessValues.forEach { append(if (it >= averageGray) '1' else '0') } }
            val gradientHash = buildString(64) {
                for (y in 0 until 8) for (x in 0 until 8) {
                    append(if (grayscale(sample9x8.getPixel(x, y)) >= grayscale(sample9x8.getPixel(x + 1, y))) '1' else '0')
                }
            }

            val colorBuckets = HashMap<String, Int>()
            var whitePixels = 0
            var darkPixels = 0
            var saturationTotal = 0.0
            var strongEdges = 0
            val toneSignature = IntArray(36); var toneIndex = 0

            for (y in 0 until 24) for (x in 0 until 24) {
                val pixel = sample24.getPixel(x, y)
                val gray = grayscale(pixel)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                saturationTotal += if (maxChannel == 0) 0.0 else (maxChannel - minChannel).toDouble() / maxChannel.toDouble()
                val bucketKey = "${red / 32}-${green / 32}-${blue / 32}"
                colorBuckets[bucketKey] = (colorBuckets[bucketKey] ?: 0) + 1
                if (gray >= 235.0) whitePixels += 1
                if (gray <= 45.0) darkPixels += 1
                if (x > 0 && abs(gray - grayscale(sample24.getPixel(x - 1, y))) >= 55.0) strongEdges += 1
                if (y > 0 && abs(gray - grayscale(sample24.getPixel(x, y - 1))) >= 55.0) strongEdges += 1
            }

            for (blockY in 0 until 6) for (blockX in 0 until 6) {
                var blockTotal = 0.0
                for (innerY in 0 until 4) for (innerX in 0 until 4) {
                    blockTotal += grayscale(sample24.getPixel((blockX * 4) + innerX, (blockY * 4) + innerY))
                }
                toneSignature[toneIndex++] = (blockTotal / 16.0).roundToInt()
            }

            val dominantColorShare = (colorBuckets.values.maxOrNull() ?: 0) / 576.0

            BitmapAnalysis(
                width = decoded.width.coerceAtLeast(fallbackWidth).coerceAtLeast(1),
                height = decoded.height.coerceAtLeast(fallbackHeight).coerceAtLeast(1),
                averageHash = averageHash, gradientHash = gradientHash, toneSignature = toneSignature,
                sharpnessEstimate = contrastTotal / 112.0, brightness = brightnessTotal / 64.0,
                edgeDensity = strongEdges / 1104.0, averageSaturation = saturationTotal / 576.0,
                whitePixelRatio = whitePixels / 576.0, darkPixelRatio = darkPixels / 576.0,
                dominantColorShare = dominantColorShare, uniqueColorCount = colorBuckets.size
            )
        } finally {
            sample8.recycle(); sample9x8.recycle(); sample24.recycle(); baseBitmap.recycle()
        }
    }

    private fun decodeBitmap(uri: Uri, targetSide: Int): DecodedBitmap? {
        return runCatching {
            var originalWidth = 0; var originalHeight = 0
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                originalWidth = info.size.width; originalHeight = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                val maxSide = max(originalWidth, originalHeight).coerceAtLeast(1)
                decoder.setTargetSampleSize((maxSide / targetSide).coerceAtLeast(1))
                decoder.isMutableRequired = false
            }
            DecodedBitmap(bitmap = bitmap, width = originalWidth.coerceAtLeast(1), height = originalHeight.coerceAtLeast(1))
        }.getOrNull()
    }

    private fun computeQualityScore(width: Int, height: Int, sizeBytes: Long, sharpnessEstimate: Double): Double {
        val megapixels = (width * height) / 1_000_000.0
        val sizeMegabytes = sizeBytes / 1_000_000.0
        return (megapixels * 3.8) + (sharpnessEstimate * 1.8) + (sizeMegabytes * 0.7)
    }

    private fun computeMessagingClutterScore(nameAndFolder: String, metrics: BitmapAnalysis, sizeBytes: Long, width: Int, height: Int): Int {
        var score = 0
        val lower = nameAndFolder.lowercase(Locale.ROOT)
        val hasKeyword = CLUTTER_REGEX.containsMatchIn(lower)
        val messagingSource = MESSAGE_SOURCE_REGEX.containsMatchIn(lower) || WHATSAPP_NAME_REGEX.containsMatchIn(nameAndFolder)
        val aspectRatio = metrics.width.toDouble() / metrics.height.coerceAtLeast(1).toDouble()
        val balancedGraphicRatio = aspectRatio in 0.55..1.8
        if (hasKeyword) score += 4
        if (messagingSource) score += 3
        if (sizeBytes in 1 until 450_000 && max(width, height) in 500..2500) score += 1
        if (metrics.averageSaturation >= 0.18) score += 1
        if (metrics.uniqueColorCount <= 170) score += 1
        if (metrics.dominantColorShare >= 0.12) score += 1
        if (metrics.edgeDensity >= 0.10) score += 1
        if (metrics.whitePixelRatio <= 0.60) score += 1
        if (balancedGraphicRatio) score += 1
        if (metrics.width <= 2200 && metrics.height <= 2200) score += 1

        val strongGraphicPattern =
            metrics.uniqueColorCount <= 120 &&
                metrics.dominantColorShare >= 0.15 &&
                metrics.edgeDensity >= 0.12 &&
                metrics.averageSaturation >= 0.18

        return if (hasKeyword || messagingSource || strongGraphicPattern) score else 0
    }

    private fun computeScreenshotScore(nameAndFolder: String, metrics: BitmapAnalysis, width: Int, height: Int): Int {
        var score = 0
        val hasKeyword = SCREENSHOT_REGEX.containsMatchIn(nameAndFolder)
        if (hasKeyword) score += 3
        val ratio = max(
            metrics.width.toDouble() / metrics.height.coerceAtLeast(1).toDouble(),
            metrics.height.toDouble() / metrics.width.coerceAtLeast(1).toDouble()
        )
        if (ratio in 1.65..2.35) score += 1
        if (metrics.edgeDensity >= 0.14) score += 1
        if (metrics.whitePixelRatio >= 0.24) score += 1
        if (metrics.averageSaturation <= 0.34) score += 1
        if (metrics.darkPixelRatio <= 0.22) score += 1
        val w = min(width, height); val h = max(width, height)
        val isDeviceResolution = (w == 1080 && h in setOf(1920, 2400, 2340, 2160)) ||
            (w == 1440 && h in setOf(3200, 2560, 3120)) ||
            (w == 720 && h in setOf(1280, 1520, 1600)) ||
            (w == 828 && h == 1792) || (w == 1170 && h == 2532) ||
            (w == 1284 && h == 2778) || (w == 1125 && h == 2436) ||
            (w == 1242 && h in setOf(2688, 2208)) ||
            (w == 1080 && h == 1080) || (w == 1179 && h == 2556) ||
            (w == 1920 && h == 1080) || (w == 1366 && h == 768)
        if (isDeviceResolution) score += 2
        return if (
            hasKeyword ||
            (ratio in 1.7..2.3 && metrics.whitePixelRatio >= 0.28 && metrics.edgeDensity >= 0.15) ||
            (isDeviceResolution && ratio in 1.7..2.3)
        ) score else 0
    }

    private fun computeDocumentScore(nameAndFolder: String, metrics: BitmapAnalysis, width: Int, height: Int): Int {
        var score = 0
        val hasKeyword = DOCUMENT_REGEX.containsMatchIn(nameAndFolder)
        val scanSource = DOCUMENT_SOURCE_REGEX.containsMatchIn(nameAndFolder)
        val tallScreenRatio = max(
            width.toDouble() / height.coerceAtLeast(1).toDouble(),
            height.toDouble() / width.coerceAtLeast(1).toDouble()
        ) in 1.7..2.3
        if (hasKeyword) score += 3
        if (scanSource) score += 3
        if (metrics.whitePixelRatio >= 0.42) score += 2
        if (metrics.edgeDensity >= 0.12) score += 1
        if (metrics.uniqueColorCount <= 130) score += 1
        if (metrics.averageSaturation <= 0.22) score += 1
        if (metrics.darkPixelRatio <= 0.12) score += 1
        if ((width.toDouble() / height.coerceAtLeast(1).toDouble()) in 0.65..1.55) score += 1

        val documentLikeShape =
            metrics.whitePixelRatio >= 0.48 &&
                metrics.averageSaturation <= 0.18 &&
                metrics.edgeDensity >= 0.11 &&
                metrics.uniqueColorCount <= 120 &&
                !tallScreenRatio

        return if (hasKeyword || scanSource || documentLikeShape) score else 0
    }

    private fun computeLowQualityScore(
        metrics: BitmapAnalysis,
        sizeBytes: Long,
        width: Int,
        height: Int,
        qualityScore: Double
    ): Int {
        var score = 0
        when {
            metrics.sharpnessEstimate <= 7.0 -> score += 4
            metrics.sharpnessEstimate <= 10.0 -> score += 3
            metrics.sharpnessEstimate <= 13.0 -> score += 2
            metrics.sharpnessEstimate <= 16.0 -> score += 1
        }
        when {
            metrics.edgeDensity <= 0.045 -> score += 2
            metrics.edgeDensity <= 0.065 -> score += 1
        }
        when {
            metrics.brightness <= 55.0 || metrics.darkPixelRatio >= 0.55 -> score += 2
            metrics.brightness <= 72.0 || metrics.darkPixelRatio >= 0.38 -> score += 1
        }
        if (min(width, height) < 720 || max(width, height) < 1100) score += 1
        if (sizeBytes in 1 until 120_000 && max(width, height) < 1800) score += 1
        if (qualityScore < 6.5) score += 1
        return score
    }

    private fun buildLowQualitySummary(metrics: BitmapAnalysis, sizeBytes: Long, width: Int, height: Int): String {
        val reasons = mutableListOf<String>()
        if (metrics.sharpnessEstimate <= 10.0) reasons += "soft focus"
        if (metrics.darkPixelRatio >= 0.38 || metrics.brightness <= 72.0) reasons += "underexposed"
        if (min(width, height) < 720 || max(width, height) < 1100) reasons += "small resolution"
        if (sizeBytes in 1 until 120_000 && max(width, height) < 1800) reasons += "compressed"
        if (reasons.isEmpty()) reasons += "weak detail"
        return reasons.joinToString(" · ") +
            " | sharpness ${formatNumber(metrics.sharpnessEstimate, 1)} | dark ${formatNumber(metrics.darkPixelRatio * 100.0, 0)}%"
    }

    private fun cleanupPenalty(image: MediaImage): Int {
        var penalty = 0
        if (image.metrics.likelyBlurry) penalty += 2
        if (image.metrics.likelyForward) penalty += 2
        if (image.metrics.likelyScreenshot) penalty += 1
        if (image.metrics.likelyTextHeavy) penalty += 1
        return penalty
    }

    private fun hammingDistance(first: String, second: String): Int {
        val length = min(first.length, second.length)
        var distance = abs(first.length - second.length)
        for (index in 0 until length) if (first[index] != second[index]) distance += 1
        return distance
    }

    private fun meanAbsoluteDifference(first: IntArray, second: IntArray): Double {
        if (first.isEmpty() || second.isEmpty()) return 100.0
        val length = min(first.size, second.size)
        var total = 0.0
        for (index in 0 until length) total += abs(first[index] - second[index]).toDouble()
        total += abs(first.size - second.size) * 16.0
        return total / length.toDouble()
    }

    private fun deleteNamePenalty(name: String): Int {
        var penalty = 0
        if (DELETE_NAME_REGEX.containsMatchIn(name)) penalty += 3
        if (WHATSAPP_NAME_REGEX.containsMatchIn(name)) penalty += 2
        if (DOWNLOADED_NAME_REGEX.containsMatchIn(name)) penalty += 1
        return penalty
    }

    private fun nameAffinityScore(firstName: String, secondName: String): Int {
        val firstCore = normalizeNameCore(firstName)
        val secondCore = normalizeNameCore(secondName)
        if (firstCore.isBlank() || secondCore.isBlank()) return 0
        if (firstCore == secondCore) return 16
        if (firstCore.contains(secondCore) || secondCore.contains(firstCore)) return 10
        val firstWords = firstCore.split(' ').filter { it.length >= 3 }.toSet()
        val secondWords = secondCore.split(' ').filter { it.length >= 3 }.toSet()
        if (firstWords.isEmpty() || secondWords.isEmpty()) return 0
        val commonWordCount = firstWords.intersect(secondWords).size
        if (commonWordCount == 0) return 0
        return min(12, commonWordCount * 4)
    }

    private fun normalizeNameCore(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("(?i)(copy|edited|duplicate|downloaded)"), " ")
            .replace(Regex("[_\\-()\\[\\].]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().lowercase(Locale.ROOT)

    private fun grayscale(pixel: Int): Double {
        return (0.299 * android.graphics.Color.red(pixel)) +
            (0.587 * android.graphics.Color.green(pixel)) +
            (0.114 * android.graphics.Color.blue(pixel))
    }

    private fun formatNumber(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun mimeTypeForImage(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "tif", "tiff" -> "image/tiff"
            else -> "application/octet-stream"
        }

    private data class DecodedBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

    private data class BitmapAnalysis(
        val width: Int, val height: Int,
        val averageHash: String, val gradientHash: String, val toneSignature: IntArray,
        val sharpnessEstimate: Double, val brightness: Double, val edgeDensity: Double,
        val averageSaturation: Double, val whitePixelRatio: Double, val darkPixelRatio: Double,
        val dominantColorShare: Double, val uniqueColorCount: Int
    )

    private companion object {
        const val CACHE_FILE_NAME = "image_cleanup_cache.json"
        const val SESSION_PREFS = "image_cleanup_session"
        const val KEY_SELECTED_SOURCE_ID = "selected_source_id"
        const val KEY_SELECTED_QUEUE_ID = "selected_queue_id"
        const val KEY_ACTIVE_REVIEW_KEY = "active_review_key"
        const val KEY_SCREEN = "screen"
        const val KEY_DISMISSED_KEYS = "dismissed_keys"
        const val KEY_LAST_SCAN_MILLIS = "last_scan_millis"

        val CLUTTER_REGEX = Regex(
            "(?i)(diwali|holi|eid|christmas|xmas|new.?year|good.?morning|good.?night|quote|motivational|motivation|blessing|blessings|shayari|status|suvichar|thought|wish|greeting|festival|birthday|anniversary|invitation|meme|funny|reaction|sticker|whatsapp|telegram|sharechat|instagram|facebook|snapchat|suprabhat|shubh|navratri|durga|krishna|radha|ganesh|mahadev|shiva|ram.?navami|janmashtami|raksha.?bandhan|karwa.?chauth|chhath|makar.?sankranti|lohri|baisakhi|pongal|onam|ugadi|gudi.?padwa|happy.?sunday|happy.?monday|happy.?tuesday|happy.?wednesday|happy.?thursday|happy.?friday|happy.?saturday)"
        )
        val MESSAGE_SOURCE_REGEX = Regex("(?i)(whatsapp|telegram|sharechat|instagram|facebook|snapchat|status|stories|download)")
        val SCREENSHOT_REGEX = Regex("(?i)(screenshot|screen.?shot|screen_capture|capture|screenshots|screen.?grab|screenrecord)")
        val DOCUMENT_REGEX = Regex(
            "(?i)(receipt|invoice|bill|statement|document|scan|scanner|camscanner|adobescan|aadhaar|aadhar|pan.?card|passport|license|licence|voter|certificate|resume|brochure|pamphlet|memo|newsletter|menu|schedule|timetable|syllabus|exam|result|admit.?card|marksheet|ticket|boarding|prescription|medical|lab.?report|report|notes|assignment|form|application|id.?card)"
        )
        val DOCUMENT_SOURCE_REGEX = Regex("(?i)(scan|scanner|camscanner|adobe.?scan|documents|docs|receipts)")
        val WHATSAPP_NAME_REGEX = Regex("(?i)(-WA\\d+|IMG-\\d{8}-WA\\d+|VID-\\d{8}-WA\\d+|WA0\\d+)")
        val DOWNLOADED_NAME_REGEX = Regex("(?i)(download|saved|export|share)")
        val DELETE_NAME_REGEX = Regex("(?i)(copy|edited|duplicate|_copy|-copy|\\(\\d+\\))")
    }
}
