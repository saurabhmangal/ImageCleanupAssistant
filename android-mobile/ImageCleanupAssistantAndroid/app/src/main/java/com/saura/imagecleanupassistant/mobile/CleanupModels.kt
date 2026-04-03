package com.saura.imagecleanupassistant.mobile

import android.net.Uri

enum class CleanupQueueId {
    EXACT,
    SIMILAR,
    BLURRY,
    FORWARD,
    SCREENSHOT,
    TEXT_HEAVY
}

enum class AppScreen {
    OVERVIEW,
    REVIEW
}

const val ALL_SOURCE_ID = "__all__"
const val ALL_FOLDERS_ID = "__all_folders__"

data class ImageMetrics(
    val averageHash: String,
    val gradientHash: String,
    val toneSignature: IntArray,
    val sharpnessEstimate: Double,
    val brightness: Double,
    val edgeDensity: Double,
    val averageSaturation: Double,
    val whitePixelRatio: Double,
    val uniqueColorCount: Int,
    val qualityScore: Double,
    val likelyBlurry: Boolean,
    val blurText: String,
    val likelyForward: Boolean,
    val likelyScreenshot: Boolean,
    val likelyTextHeavy: Boolean
)

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val modifiedAtMillis: Long,
    val contentHash: String?,
    val metrics: ImageMetrics
) {
    val aspectRatio: Double
        get() = if (height == 0) 1.0 else width.toDouble() / height.toDouble()

    val dimensionsText: String
        get() = "$width x $height"

    val sizeText: String
        get() = formatBytes(sizeBytes)
}

data class PairMatch(
    val firstId: Long,
    val secondId: Long,
    val label: String,
    val suggestedDeleteId: Long,
    val confidence: Int,
    val matchScore: Double,
    val recommendationText: String
) {
    val key: String
        get() = "$firstId|$secondId"
}

data class CleanupSnapshot(
    val imagesById: Map<Long, MediaImage> = emptyMap(),
    val exactPairs: List<PairMatch> = emptyList(),
    val similarPairs: List<PairMatch> = emptyList(),
    val blurryIds: List<Long> = emptyList(),
    val forwardIds: List<Long> = emptyList(),
    val screenshotIds: List<Long> = emptyList(),
    val textHeavyIds: List<Long> = emptyList()
) {
    companion object {
        val EMPTY = CleanupSnapshot()
    }
}

data class QueueDefinition(
    val id: CleanupQueueId,
    val title: String,
    val count: Int,
    val description: String,
    val emptyText: String
)

data class SourceOption(
    val id: String,
    val title: String,
    val count: Int
)

data class FolderOption(
    val folder: String,
    val count: Int
)

sealed interface CleanupEntry {
    val key: String
    val queueId: CleanupQueueId
    val title: String
    val subtitle: String
    val metaText: String

    data class PairEntry(
        override val queueId: CleanupQueueId,
        val pair: PairMatch,
        val first: MediaImage,
        val second: MediaImage,
        override val title: String,
        override val subtitle: String,
        override val metaText: String
    ) : CleanupEntry {
        override val key: String = pair.key
    }

    data class SingleEntry(
        override val queueId: CleanupQueueId,
        val image: MediaImage,
        override val title: String,
        override val subtitle: String,
        override val metaText: String
    ) : CleanupEntry {
        override val key: String = image.id.toString()
    }
}

data class DeleteCommand(
    val imageIds: Set<Long>,
    val uris: List<Uri>,
    val successMessage: String
)

data class UiState(
    val hasPermission: Boolean = false,
    val isScanning: Boolean = false,
    val statusText: String = "Grant photo access to start scanning.",
    val summaryText: String = "No scan yet.",
    val queues: List<QueueDefinition> = defaultQueueDefinitions(),
    val availableSources: List<SourceOption> = defaultSourceOptions(),
    val selectedSourceId: String = ALL_SOURCE_ID,
    val selectedQueueId: CleanupQueueId = CleanupQueueId.EXACT,
    val entries: List<CleanupEntry> = emptyList(),
    val activeReviewKey: String? = null,
    val screen: AppScreen = AppScreen.OVERVIEW,
    val imageCount: Int = 0,
    val lastScanMillis: Long? = null,
    val dismissedEntryKeys: Set<String> = emptySet(),
    val selectedEntryKeys: Set<String> = emptySet(),
    val selectedScanFolder: String? = null,
    val availableFolders: List<FolderOption> = emptyList()
) {
    val activeReviewEntry: CleanupEntry?
        get() = entries.firstOrNull { it.key == activeReviewKey }

    val selectedEntries: List<CleanupEntry>
        get() = entries.filter { it.key in selectedEntryKeys }

    val isSelectionMode: Boolean
        get() = selectedEntryKeys.isNotEmpty()

    val activeReviewImage: MediaImage?
        get() = when (val entry = activeReviewEntry) {
            is CleanupEntry.PairEntry -> null
            is CleanupEntry.SingleEntry -> entry.image
            null -> null
        }

    val totalReclaimableBytes: Long
        get() {
            var total = 0L
            for (queue in queues) {
                // Rough estimate: average image size per queue item
                // For pairs, count the suggested-delete image
            }
            return total
        }
}

sealed interface UiEvent {
    data class LaunchDeleteRequest(val command: DeleteCommand) : UiEvent
    data class ShowMessage(val message: String) : UiEvent
}

fun defaultQueueDefinitions(): List<QueueDefinition> = listOf(
    QueueDefinition(
        id = CleanupQueueId.EXACT,
        title = "Exact Duplicates",
        count = 0,
        description = "Byte-identical files. Safest queue to clean first.",
        emptyText = "No exact duplicates were found."
    ),
    QueueDefinition(
        id = CleanupQueueId.SIMILAR,
        title = "Similar Photos",
        count = 0,
        description = "Stricter visual matches for resized or lightly edited copies.",
        emptyText = "No strong similar-photo pairs were found."
    ),
    QueueDefinition(
        id = CleanupQueueId.BLURRY,
        title = "Blurry Photos",
        count = 0,
        description = "Photos with weak sharpness and low edge detail.",
        emptyText = "No blurry photos are waiting."
    ),
    QueueDefinition(
        id = CleanupQueueId.FORWARD,
        title = "Likely Forwards",
        count = 0,
        description = "Greeting cards, festival wishes, quotes, and WhatsApp-style forwards.",
        emptyText = "No likely forward images are waiting."
    ),
    QueueDefinition(
        id = CleanupQueueId.SCREENSHOT,
        title = "Screenshots",
        count = 0,
        description = "Captured screens and UI images.",
        emptyText = "No screenshots are waiting."
    ),
    QueueDefinition(
        id = CleanupQueueId.TEXT_HEAVY,
        title = "Text-Heavy",
        count = 0,
        description = "Quote cards, posters, flyers, and text graphics.",
        emptyText = "No text-heavy images are waiting."
    )
)

fun CleanupQueueId.isPairQueue(): Boolean =
    this == CleanupQueueId.EXACT || this == CleanupQueueId.SIMILAR

fun defaultSourceOptions(): List<SourceOption> = listOf(
    SourceOption(
        id = ALL_SOURCE_ID,
        title = "All Photos",
        count = 0
    )
)

fun scopedEntryKey(queueId: CleanupQueueId, entryKey: String): String =
    "${queueId.name}|$entryKey"

fun formatBytes(bytes: Long): String {
    val value = bytes.toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.1f KB", value / 1024.0)
        else -> "${bytes} B"
    }
}
