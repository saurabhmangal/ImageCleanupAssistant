package com.saura.imagecleanupassistant.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CleanupViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CleanupRepository(application.applicationContext)
    private var snapshot: CleanupSnapshot = CleanupSnapshot.EMPTY

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        val restoredAiModel = repository.restoreAiModelConfig()
        _state.value = _state.value.copy(
            aiModelConfig = restoredAiModel,
            aiVerdicts = repository.restoreAiVerdictCache(restoredAiModel),
            aiStatusText = if (restoredAiModel == null) {
                "AI review is optional and currently off."
            } else {
                "AI model ready: ${restoredAiModel.modelName}."
            }
        )
    }

    fun restoreCachedStateIfAvailable() {
        if (_state.value.isScanning || _state.value.imageCount > 0) {
            return
        }

        val cachedSnapshot = repository.restoreSnapshotFromCache() ?: return
        val cachedSession = repository.restoreUiSession()
        snapshot = cachedSnapshot

        val availableSources = repository.buildSourceOptions(cachedSnapshot)
        val selectedSourceId = cachedSession?.selectedSourceId
            ?.takeIf { sourceId -> availableSources.any { it.id == sourceId } }
            ?: ALL_SOURCE_ID
        val scopedSnapshot = repository.filterSnapshotBySource(cachedSnapshot, selectedSourceId)

        val selectedQueueId = choosePreferredQueue(
            snapshot = scopedSnapshot,
            preferredQueueId = cachedSession?.selectedQueueId ?: _state.value.selectedQueueId
        )
        val dismissedKeys = cachedSession?.dismissedEntryKeys ?: emptySet()
        val entries = repository.buildEntries(scopedSnapshot, selectedQueueId, dismissedKeys)
        val restoredActiveKey = cachedSession?.activeReviewKey
            ?.takeIf { key -> entries.any { it.key == key } }
            ?: entries.firstOrNull()?.key

        val restoredState = _state.value.copy(
            statusText = if (scopedSnapshot.imagesById.isEmpty()) {
                "No cached photos were found."
            } else {
                "Restored your last review session. Rescan only when your library changes."
            },
            summaryText = buildSummaryText(scopedSnapshot),
            queues = repository.buildQueueDefinitions(scopedSnapshot),
            availableSources = availableSources,
            selectedSourceId = selectedSourceId,
            selectedQueueId = selectedQueueId,
            entries = entries,
            activeReviewKey = restoredActiveKey,
            screen = if (cachedSession?.screen == AppScreen.REVIEW && entries.isNotEmpty()) {
                AppScreen.REVIEW
            } else {
                AppScreen.OVERVIEW
            },
            imageCount = scopedSnapshot.imagesById.size,
            lastScanMillis = cachedSession?.lastScanMillis,
            dismissedEntryKeys = dismissedKeys,
            selectedEntryKeys = emptySet(),
            aiVerdicts = repository.pruneAiVerdicts(_state.value.aiVerdicts, cachedSnapshot.imagesById)
        )
        _state.value = restoredState
        repository.persistUiSession(restoredState)
        repository.persistAiVerdictCache(restoredState.aiModelConfig, restoredState.aiVerdicts)
    }

    fun refreshPermission(hasPermission: Boolean) {
        val updated = _state.value.copy(
            hasPermission = hasPermission,
            statusText = when {
                hasPermission && _state.value.imageCount > 0 -> _state.value.statusText
                hasPermission -> "Ready to scan your photo library."
                else -> "Grant photo access to start scanning."
            }
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun scanLibrary() {
        val current = _state.value
        if (!current.hasPermission || current.isScanning) {
            if (!current.hasPermission) {
                _events.tryEmit(UiEvent.ShowMessage("Photo access is required before scanning."))
            }
            return
        }

        viewModelScope.launch {
            val scanningState = _state.value.copy(
                isScanning = true,
                statusText = "Starting library scan..."
            )
            _state.value = scanningState
            repository.persistUiSession(scanningState)

            try {
                val scannedSnapshot = repository.scanLibrary { progressText ->
                    val progressState = _state.value.copy(statusText = progressText)
                    _state.value = progressState
                }

                snapshot = scannedSnapshot
                val availableSources = repository.buildSourceOptions(scannedSnapshot)
                val selectedSourceId = _state.value.selectedSourceId
                    .takeIf { sourceId -> availableSources.any { it.id == sourceId } }
                    ?: ALL_SOURCE_ID
                val scopedSnapshot = repository.filterSnapshotBySource(scannedSnapshot, selectedSourceId)
                val preferredQueue = choosePreferredQueue(
                    snapshot = scopedSnapshot,
                    preferredQueueId = _state.value.selectedQueueId
                )
                val entries = repository.buildEntries(
                    snapshot = scopedSnapshot,
                    queueId = preferredQueue,
                    dismissedEntryKeys = _state.value.dismissedEntryKeys
                )

                val scannedState = _state.value.copy(
                    isScanning = false,
                    statusText = if (scopedSnapshot.imagesById.isEmpty()) {
                        "No photos were found on this device."
                    } else {
                        "Scan finished. Review queues are ready."
                    },
                    summaryText = buildSummaryText(scopedSnapshot),
                    queues = repository.buildQueueDefinitions(scopedSnapshot),
                    availableSources = availableSources,
                    selectedSourceId = selectedSourceId,
                    selectedQueueId = preferredQueue,
                    entries = entries,
                    activeReviewKey = entries.firstOrNull()?.key,
                    imageCount = scopedSnapshot.imagesById.size,
                    lastScanMillis = System.currentTimeMillis(),
                    selectedEntryKeys = emptySet(),
                    aiVerdicts = repository.pruneAiVerdicts(_state.value.aiVerdicts, scannedSnapshot.imagesById)
                )
                _state.value = scannedState
                repository.persistUiSession(scannedState)
                repository.persistAiVerdictCache(scannedState.aiModelConfig, scannedState.aiVerdicts)
            } catch (error: Exception) {
                val failedState = _state.value.copy(
                    isScanning = false,
                    statusText = "Scan failed."
                )
                _state.value = failedState
                repository.persistUiSession(failedState)
                _events.emit(UiEvent.ShowMessage(error.message ?: "Scan failed."))
            }
        }
    }

    fun selectSource(sourceId: String) {
        val availableSources = repository.buildSourceOptions(snapshot)
        val normalizedSourceId = sourceId.takeIf { wanted -> availableSources.any { it.id == wanted } }
            ?: ALL_SOURCE_ID
        val scopedSnapshot = repository.filterSnapshotBySource(snapshot, normalizedSourceId)
        val selectedQueueId = choosePreferredQueue(
            snapshot = scopedSnapshot,
            preferredQueueId = _state.value.selectedQueueId
        )
        val entries = repository.buildEntries(
            snapshot = scopedSnapshot,
            queueId = selectedQueueId,
            dismissedEntryKeys = _state.value.dismissedEntryKeys
        )
        val updated = _state.value.copy(
            availableSources = availableSources,
            selectedSourceId = normalizedSourceId,
            summaryText = buildSummaryText(scopedSnapshot),
            queues = repository.buildQueueDefinitions(scopedSnapshot),
            selectedQueueId = selectedQueueId,
            entries = entries,
            activeReviewKey = entries.firstOrNull()?.key,
            imageCount = scopedSnapshot.imagesById.size,
            selectedEntryKeys = emptySet(),
            statusText = if (scopedSnapshot.imagesById.isEmpty()) {
                "No photos found in this source."
            } else {
                "${scopedSnapshot.imagesById.size} photo(s) ready in ${sourceLabel(normalizedSourceId, availableSources)}."
            }
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun openQueue(queueId: CleanupQueueId) {
        val scopedSnapshot = currentScopedSnapshot()
        val entries = repository.buildEntries(scopedSnapshot, queueId, _state.value.dismissedEntryKeys)
        val updated = _state.value.copy(
            screen = AppScreen.REVIEW,
            selectedQueueId = queueId,
            entries = entries,
            activeReviewKey = entries.firstOrNull()?.key,
            selectedEntryKeys = emptySet(),
            statusText = if (entries.isEmpty()) {
                queueDefinition(queueId).emptyText
            } else {
                "${entries.size} item(s) ready in ${queueDefinition(queueId).title}."
            }
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun returnToOverview() {
        val updated = _state.value.copy(screen = AppScreen.OVERVIEW, selectedEntryKeys = emptySet())
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun selectEntry(entryKey: String) {
        val updated = _state.value.copy(activeReviewKey = entryKey)
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun startEntrySelection(entryKey: String) {
        val current = _state.value
        if (current.entries.none { it.key == entryKey }) {
            return
        }

        val updated = current.copy(
            activeReviewKey = entryKey,
            selectedEntryKeys = setOf(entryKey)
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun toggleEntrySelection(entryKey: String) {
        val current = _state.value
        if (current.entries.none { it.key == entryKey }) {
            return
        }

        val updatedKeys = if (entryKey in current.selectedEntryKeys) {
            current.selectedEntryKeys - entryKey
        } else {
            current.selectedEntryKeys + entryKey
        }
        val updated = current.copy(
            activeReviewKey = entryKey,
            selectedEntryKeys = updatedKeys
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun clearSelectedEntries() {
        val current = _state.value
        if (current.selectedEntryKeys.isEmpty()) {
            return
        }
        val updated = current.copy(selectedEntryKeys = emptySet())
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun selectAllEntries() {
        val current = _state.value
        if (current.entries.isEmpty()) {
            return
        }
        val updated = current.copy(selectedEntryKeys = current.entries.map { it.key }.toSet())
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun moveSelection(step: Int) {
        val current = _state.value
        if (current.entries.isEmpty()) {
            return
        }

        val currentIndex = current.entries.indexOfFirst { it.key == current.activeReviewKey }
            .takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + step).coerceIn(0, current.entries.lastIndex)
        val updated = current.copy(activeReviewKey = current.entries[nextIndex].key)
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun keepCurrentEntry() {
        val current = _state.value
        val activeEntry = current.activeReviewEntry ?: return
        val dismissedKey = scopedEntryKey(current.selectedQueueId, activeEntry.key)
        val newDismissedKeys = current.dismissedEntryKeys + dismissedKey
        val newEntries = repository.buildEntries(currentScopedSnapshot(), current.selectedQueueId, newDismissedKeys)
        val currentIndex = current.entries.indexOfFirst { it.key == activeEntry.key }.takeIf { it >= 0 } ?: 0
        val nextEntry = newEntries.getOrNull(currentIndex) ?: newEntries.lastOrNull()

        val updated = current.copy(
            dismissedEntryKeys = newDismissedKeys,
            entries = newEntries,
            activeReviewKey = nextEntry?.key,
            selectedEntryKeys = emptySet(),
            statusText = if (newEntries.isEmpty()) {
                queueDefinition(current.selectedQueueId).emptyText
            } else {
                "${newEntries.size} item(s) left in ${queueDefinition(current.selectedQueueId).title}."
            }
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun requestDeleteCurrentSingle() {
        val entry = _state.value.activeReviewEntry as? CleanupEntry.SingleEntry ?: return
        launchDeleteCommand(
            DeleteCommand(
                imageIds = setOf(entry.image.id),
                uris = listOf(entry.image.uri),
                successMessage = "${entry.image.name} deleted."
            )
        )
    }

    fun requestDeleteLeft() {
        val entry = _state.value.activeReviewEntry as? CleanupEntry.PairEntry ?: return
        launchDeleteCommand(
            DeleteCommand(
                imageIds = setOf(entry.first.id),
                uris = listOf(entry.first.uri),
                successMessage = "${entry.first.name} deleted."
            )
        )
    }

    fun requestDeleteRight() {
        val entry = _state.value.activeReviewEntry as? CleanupEntry.PairEntry ?: return
        launchDeleteCommand(
            DeleteCommand(
                imageIds = setOf(entry.second.id),
                uris = listOf(entry.second.uri),
                successMessage = "${entry.second.name} deleted."
            )
        )
    }

    fun requestDeleteBoth() {
        val entry = _state.value.activeReviewEntry as? CleanupEntry.PairEntry ?: return
        launchDeleteCommand(
            DeleteCommand(
                imageIds = setOf(entry.first.id, entry.second.id),
                uris = listOf(entry.first.uri, entry.second.uri),
                successMessage = "Both photos deleted."
            )
        )
    }

    fun keepSelectedEntries() {
        val current = _state.value
        val selectedEntries = current.selectedEntries
        if (selectedEntries.isEmpty()) {
            return
        }

        val dismissedKeys = selectedEntries.map { scopedEntryKey(current.selectedQueueId, it.key) }.toSet()
        val newDismissedKeys = current.dismissedEntryKeys + dismissedKeys
        val newEntries = repository.buildEntries(currentScopedSnapshot(), current.selectedQueueId, newDismissedKeys)
        val updated = current.copy(
            dismissedEntryKeys = newDismissedKeys,
            entries = newEntries,
            activeReviewKey = newEntries.firstOrNull()?.key,
            selectedEntryKeys = emptySet(),
            statusText = if (newEntries.isEmpty()) {
                queueDefinition(current.selectedQueueId).emptyText
            } else {
                "${newEntries.size} item(s) left in ${queueDefinition(current.selectedQueueId).title}."
            }
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun requestDeleteSelectedEntries() {
        val current = _state.value
        val selectedEntries = current.selectedEntries
        if (selectedEntries.isEmpty()) {
            return
        }

        val imagesToDelete = linkedMapOf<Long, Uri>()
        if (current.selectedQueueId.isPairQueue()) {
            selectedEntries.filterIsInstance<CleanupEntry.PairEntry>().forEach { entry ->
                val target = if (entry.first.id == entry.pair.suggestedDeleteId) entry.first else entry.second
                imagesToDelete[target.id] = target.uri
            }
        } else {
            selectedEntries.filterIsInstance<CleanupEntry.SingleEntry>().forEach { entry ->
                imagesToDelete[entry.image.id] = entry.image.uri
            }
        }

        if (imagesToDelete.isEmpty()) {
            return
        }

        val successMessage = if (current.selectedQueueId.isPairQueue()) {
            "Deleted recommended photos from ${selectedEntries.size} selected pair(s)."
        } else {
            "Deleted ${imagesToDelete.size} selected photo(s)."
        }

        launchDeleteCommand(
            DeleteCommand(
                imageIds = imagesToDelete.keys,
                uris = imagesToDelete.values.toList(),
                successMessage = successMessage
            )
        )
    }

    fun onDeleteRequestCompleted(command: DeleteCommand?, wasApproved: Boolean) {
        if (command == null) {
            return
        }

        if (!wasApproved) {
            _events.tryEmit(UiEvent.ShowMessage("Delete canceled."))
            return
        }

        snapshot = repository.rebuildSnapshotAfterDelete(snapshot, command.imageIds)
        val current = _state.value
        val availableSources = repository.buildSourceOptions(snapshot)
        val selectedSourceId = current.selectedSourceId.takeIf { sourceId -> availableSources.any { it.id == sourceId } }
            ?: ALL_SOURCE_ID
        val scopedSnapshot = repository.filterSnapshotBySource(snapshot, selectedSourceId)
        val selectedQueue = choosePreferredQueue(scopedSnapshot, current.selectedQueueId)
        val newEntries = repository.buildEntries(scopedSnapshot, selectedQueue, current.dismissedEntryKeys)
        val prunedAiVerdicts = repository.pruneAiVerdicts(current.aiVerdicts, snapshot.imagesById)
        val updated = _state.value.copy(
            summaryText = buildSummaryText(scopedSnapshot),
            queues = repository.buildQueueDefinitions(scopedSnapshot),
            availableSources = availableSources,
            selectedSourceId = selectedSourceId,
            selectedQueueId = selectedQueue,
            entries = newEntries,
            activeReviewKey = newEntries.firstOrNull()?.key,
            imageCount = scopedSnapshot.imagesById.size,
            selectedEntryKeys = emptySet(),
            aiVerdicts = prunedAiVerdicts,
            statusText = command.successMessage
        )
        _state.value = updated
        repository.persistUiSession(updated)
        repository.persistAiVerdictCache(updated.aiModelConfig, updated.aiVerdicts)
    }

    fun importAiModel(uri: Uri) {
        if (_state.value.isAiModelImporting || _state.value.isAiReviewRunning) {
            return
        }

        viewModelScope.launch {
            val importingState = _state.value.copy(
                isAiModelImporting = true,
                aiStatusText = "Importing AI model..."
            )
            _state.value = importingState
            try {
                val modelConfig = repository.importAiModel(uri)
                val updated = _state.value.copy(
                    aiModelConfig = modelConfig,
                    aiVerdicts = emptyMap(),
                    isAiModelImporting = false,
                    aiStatusText = "AI model ready: ${modelConfig.modelName}."
                )
                _state.value = updated
                repository.persistUiSession(updated)
            } catch (error: Exception) {
                val failed = _state.value.copy(
                    isAiModelImporting = false,
                    aiStatusText = "AI model import failed."
                )
                _state.value = failed
                repository.persistUiSession(failed)
                _events.emit(UiEvent.ShowMessage(error.message ?: "Unable to import the AI model."))
            }
        }
    }

    fun clearAiModel() {
        if (_state.value.isAiModelImporting || _state.value.isAiReviewRunning) {
            return
        }

        repository.clearAiModel()
        val updated = _state.value.copy(
            aiModelConfig = null,
            aiVerdicts = emptyMap(),
            aiStatusText = "AI review is optional and currently off."
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun runAiReviewForCurrentEntry() {
        val current = _state.value
        val image = current.activeReviewImage ?: return
        val modelConfig = current.aiModelConfig ?: run {
            _events.tryEmit(UiEvent.ShowMessage("Import a Gemma .litertlm model first."))
            return
        }
        if (current.selectedQueueId != CleanupQueueId.FORWARD || current.isAiReviewRunning) {
            return
        }

        current.activeAiVerdict?.let { verdict ->
            _events.tryEmit(UiEvent.ShowMessage("${verdict.headline}"))
            return
        }

        viewModelScope.launch {
            val runningState = _state.value.copy(
                isAiReviewRunning = true,
                aiStatusText = "Running AI check for ${image.name}..."
            )
            _state.value = runningState
            try {
                val verdict = repository.reviewForwardImageWithAi(image, modelConfig)
                val updatedVerdicts = runningState.aiVerdicts + (image.id to verdict)
                val updated = _state.value.copy(
                    isAiReviewRunning = false,
                    aiVerdicts = updatedVerdicts,
                    aiStatusText = "${verdict.headline} (${verdict.confidence}%)."
                )
                _state.value = updated
                repository.persistUiSession(updated)
                repository.persistAiVerdictCache(updated.aiModelConfig, updated.aiVerdicts)
            } catch (error: Exception) {
                val failed = _state.value.copy(
                    isAiReviewRunning = false,
                    aiStatusText = "AI check failed."
                )
                _state.value = failed
                repository.persistUiSession(failed)
                _events.emit(UiEvent.ShowMessage(error.message ?: "AI check failed."))
            }
        }
    }

    private fun launchDeleteCommand(command: DeleteCommand) {
        _events.tryEmit(UiEvent.LaunchDeleteRequest(command))
    }

    private fun choosePreferredQueue(
        snapshot: CleanupSnapshot,
        preferredQueueId: CleanupQueueId
    ): CleanupQueueId {
        if (repository.queueCount(snapshot, preferredQueueId) > 0) {
            return preferredQueueId
        }

        return defaultQueueDefinitions()
            .firstOrNull { repository.queueCount(snapshot, it.id) > 0 }
            ?.id
            ?: preferredQueueId
    }

    private fun buildSummaryText(snapshot: CleanupSnapshot): String {
        val exactCount = snapshot.exactPairs.size
        val similarCount = snapshot.similarPairs.size
        val blurryCount = snapshot.blurryIds.size
        val forwardCount = snapshot.forwardIds.size
        val screenshotCount = snapshot.screenshotIds.size
        val textHeavyCount = snapshot.textHeavyIds.size

        return if (snapshot.imagesById.isEmpty()) {
            "No photos scanned yet."
        } else {
            "${snapshot.imagesById.size} photos scanned | " +
                "$exactCount exact duplicates | " +
                "$similarCount similar pairs | " +
                "$blurryCount blurry | " +
                "$forwardCount forwards | " +
                "$screenshotCount screenshots | " +
                "$textHeavyCount text-heavy"
        }
    }

    private fun currentScopedSnapshot(fullSnapshot: CleanupSnapshot = snapshot): CleanupSnapshot =
        repository.filterSnapshotBySource(fullSnapshot, _state.value.selectedSourceId)

    private fun sourceLabel(sourceId: String, availableSources: List<SourceOption>): String =
        availableSources.firstOrNull { it.id == sourceId }?.title ?: "All Photos"

    private fun queueDefinition(queueId: CleanupQueueId): QueueDefinition =
        defaultQueueDefinitions().first { it.id == queueId }
}
