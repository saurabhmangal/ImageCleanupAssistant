package com.saura.imagecleanupassistant.mobile

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class CleanupViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CleanupRepository(application.applicationContext)
    private val networkStatusManager = NetworkStatusManager(application.applicationContext)
    @Volatile
    private var snapshot: CleanupSnapshot = CleanupSnapshot.EMPTY
    private var remoteServer: RemoteAccessServer? = null

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val remoteController = object : RemoteAccessServer.Controller {
        override fun sessionJson(): JSONObject = buildRemoteSessionJson()

        override fun entriesJson(queueId: String?, sourceId: String?): JSONObject =
            buildRemoteEntriesJson(queueId = queueId, sourceId = sourceId)

        override fun startScan(folder: String?): JSONObject = startRemoteScan(folder)

        override fun deleteImages(imageIds: Set<Long>): JSONObject = deleteImagesFromRemote(imageIds)

        override fun openImage(imageId: Long): RemoteImagePayload? = openRemoteImage(imageId)
    }

    init {
        refreshRemoteCapabilities()
        observeNetworkStatus()
    }

    private fun observeNetworkStatus() {
        viewModelScope.launch {
            networkStatusManager.observeStatus().collect { status ->
                val statusText = when (status) {
                    NetworkStatusManager.NetworkStatus.Connected -> "Connected"
                    NetworkStatusManager.NetworkStatus.ConnectedMetered -> "ConnectedMetered"
                    NetworkStatusManager.NetworkStatus.Reconnecting -> "Reconnecting"
                    NetworkStatusManager.NetworkStatus.Offline -> "Offline"
                }
                
                _state.value = _state.value.copy(
                    networkStatus = NetworkStatusSnapshot(
                        status = statusText,
                        isConnected = status == NetworkStatusManager.NetworkStatus.Connected || 
                                     status == NetworkStatusManager.NetworkStatus.ConnectedMetered,
                        isMetered = status == NetworkStatusManager.NetworkStatus.ConnectedMetered
                    )
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(currentError = null, retryAttempts = 0)
    }

    fun retryLastOperation() {
        val error = _state.value.currentError ?: return
        if (!error.isRetryable) return
        
        val attempts = _state.value.retryAttempts + 1
        _state.value = _state.value.copy(retryAttempts = attempts)
        
        when (error) {
            is AppError.ScanError -> scanLibrary()
            is AppError.DeleteError -> {} // Handle delete retry if needed
            else -> scanLibrary()
        }
    }

    fun refreshRemoteCapabilities() {
        applyRemoteAccessState()
    }

    fun toggleRemoteAccess() {
        val current = _state.value
        if (!current.hasPermission) {
            _events.tryEmit(UiEvent.ShowMessage("Grant photo access on the phone before starting Wi-Fi access."))
            return
        }

        if (remoteServer != null || current.remoteAccess.isStarting) {
            remoteServer?.stop()
            remoteServer = null
            applyRemoteAccessState(
                isEnabled = false,
                isStarting = false,
                statusText = "Wi-Fi dashboard stopped."
            )
            return
        }

        applyRemoteAccessState(
            isEnabled = false,
            isStarting = true,
            statusText = "Starting Wi-Fi dashboard..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = RemoteAccessServer(DEFAULT_REMOTE_PORT, remoteController)
                server.start(5_000, false)
                remoteServer = server
                applyRemoteAccessState(
                    isEnabled = true,
                    isStarting = false,
                    statusText = "Open the address below on another device connected to the same Wi-Fi."
                )
            } catch (error: Exception) {
                remoteServer = null
                applyRemoteAccessState(
                    isEnabled = false,
                    isStarting = false,
                    statusText = error.message ?: "Could not start the Wi-Fi dashboard."
                )
                _events.emit(UiEvent.ShowMessage(error.message ?: "Could not start the Wi-Fi dashboard."))
            }
        }
    }

    fun restoreCachedStateIfAvailable() {
        if (_state.value.isScanning || _state.value.imageCount > 0) return

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
                "Restored your last session. Rescan when your library changes."
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
            selectedEntryKeys = emptySet()
        )
        _state.value = restoredState
        repository.persistUiSession(restoredState)
        applyRemoteAccessState()
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
        applyRemoteAccessState()
    }

    fun loadAvailableFolders() {
        viewModelScope.launch {
            val folders = repository.queryAvailableFolders()
            _state.value = _state.value.copy(availableFolders = folders)
        }
    }

    fun selectScanFolder(folder: String?) {
        val normalized = folder?.takeIf { it != ALL_FOLDERS_ID }
        _state.value = _state.value.copy(selectedScanFolder = normalized)
    }

    fun scanLibrary() {
        launchScan()
    }

    private fun launchScan(
        folderOverride: String? = _state.value.selectedScanFolder,
        preferredSourceId: String? = null
    ) {
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
                statusText = "Starting library scan...",
                selectedScanFolder = folderOverride
            )
            _state.value = scanningState
            repository.persistUiSession(scanningState)

            try {
                val folderFilter = folderOverride
                val scannedSnapshot = repository.scanLibrary(
                    folderFilter = folderFilter
                ) { progressText ->
                    _state.value = _state.value.copy(statusText = progressText)
                }

                snapshot = scannedSnapshot
                val availableSources = repository.buildSourceOptions(scannedSnapshot)
                val selectedSourceId = (preferredSourceId ?: _state.value.selectedSourceId)
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

                val totalItems = scopedSnapshot.exactPairs.size + scopedSnapshot.similarPairs.size +
                    scopedSnapshot.blurryIds.size + scopedSnapshot.forwardIds.size +
                    scopedSnapshot.screenshotIds.size + scopedSnapshot.textHeavyIds.size

                val scannedState = _state.value.copy(
                    isScanning = false,
                    statusText = if (scopedSnapshot.imagesById.isEmpty()) {
                        "No photos were found on this device."
                    } else if (totalItems == 0) {
                        "Your library looks clean!"
                    } else {
                        "$totalItems items found to review."
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
                    selectedEntryKeys = emptySet()
                )
                _state.value = scannedState
                repository.persistUiSession(scannedState)
                applyRemoteAccessState()
            } catch (error: Exception) {
                val appError = when (error) {
                    is java.io.IOException -> AppError.NetworkError(
                        "Network error during scan: ${error.message ?: \"Unknown error\"}"
                    )
                    is java.util.concurrent.TimeoutException -> AppError.TimeoutError(
                        "Scan timed out. Check your connection and retry."
                    )
                    is SecurityException -> AppError.PermissionError(
                        "Storage permission denied during scan"
                    )
                    else -> AppError.ScanError(
                        error.message ?: "Scan failed",
                        itemsProcessed = 0,
                        total = 0
                    )
                }
                
                val failedState = _state.value.copy(
                    isScanning = false,
                    statusText = appError.message,
                    currentError = appError
                )
                _state.value = failedState
                repository.persistUiSession(failedState)
                applyRemoteAccessState()
                _events.emit(UiEvent.ShowError(appError))
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
                "${scopedSnapshot.imagesById.size} photo(s) in ${sourceLabel(normalizedSourceId, availableSources)}."
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
                "${entries.size} item(s) in ${queueDefinition(queueId).title}."
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
        if (current.entries.none { it.key == entryKey }) return
        val updated = current.copy(
            activeReviewKey = entryKey,
            selectedEntryKeys = setOf(entryKey)
        )
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun toggleEntrySelection(entryKey: String) {
        val current = _state.value
        if (current.entries.none { it.key == entryKey }) return
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
        if (current.selectedEntryKeys.isEmpty()) return
        val updated = current.copy(selectedEntryKeys = emptySet())
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun selectAllEntries() {
        val current = _state.value
        if (current.entries.isEmpty()) return
        val updated = current.copy(selectedEntryKeys = current.entries.map { it.key }.toSet())
        _state.value = updated
        repository.persistUiSession(updated)
    }

    fun moveSelection(step: Int) {
        val current = _state.value
        if (current.entries.isEmpty()) return
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
            queues = repository.buildQueueDefinitions(currentScopedSnapshot()),
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
        if (selectedEntries.isEmpty()) return

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
        if (selectedEntries.isEmpty()) return

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

        if (imagesToDelete.isEmpty()) return

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
        if (command == null) return

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
            statusText = command.successMessage
        )
        _state.value = updated
        repository.persistUiSession(updated)
        applyRemoteAccessState()
    }

    private fun buildRemoteSessionJson(): JSONObject {
        val current = _state.value
        val availableFolders = current.availableFolders.ifEmpty {
            runBlocking { repository.queryAvailableFolders() }
        }
        val fullSummary = buildSummaryText(snapshot)
        val queues = repository.buildQueueDefinitions(snapshot)
        val sources = repository.buildSourceOptions(snapshot)

        return JSONObject().apply {
            put("hasPermission", current.hasPermission)
            put("isScanning", current.isScanning)
            put("statusText", current.statusText)
            put("summaryText", if (snapshot.imagesById.isEmpty()) "No photos scanned yet." else fullSummary)
            put("imageCount", snapshot.imagesById.size)
            put("totalReviewItems", queues.sumOf { it.count })
            put("activeQueueCount", queues.count { it.count > 0 })
            put("lastScanMillis", current.lastScanMillis ?: JSONObject.NULL)
            put("selectedScanFolder", current.selectedScanFolder ?: JSONObject.NULL)
            put(
                "availableFolders",
                JSONArray().apply {
                    availableFolders.forEach { folder ->
                        put(
                            JSONObject()
                                .put("folder", folder.folder)
                                .put("count", folder.count)
                        )
                    }
                }
            )
            put(
                "availableSources",
                JSONArray().apply {
                    sources.forEach { source ->
                        put(
                            JSONObject()
                                .put("id", source.id)
                                .put("title", source.title)
                                .put("count", source.count)
                        )
                    }
                }
            )
            put(
                "queues",
                JSONArray().apply {
                    queues.forEach { queue ->
                        put(
                            JSONObject()
                                .put("id", queue.id.name)
                                .put("title", queue.title)
                                .put("count", queue.count)
                                .put("description", queue.description)
                                .put("emptyText", queue.emptyText)
                        )
                    }
                }
            )
            put(
                "remoteAccess",
                JSONObject().apply {
                    put("isEnabled", current.remoteAccess.isEnabled)
                    put("isStarting", current.remoteAccess.isStarting)
                    put("port", current.remoteAccess.port)
                    put("localUrl", current.remoteAccess.localUrl ?: JSONObject.NULL)
                    put("statusText", current.remoteAccess.statusText)
                    put("remoteDeleteEnabled", current.remoteAccess.remoteDeleteEnabled)
                    put("deleteHint", current.remoteAccess.deleteHint)
                }
            )
        }
    }

    private fun buildRemoteEntriesJson(queueId: String?, sourceId: String?): JSONObject {
        val requestedQueue = queueId
            ?.runCatching { CleanupQueueId.valueOf(this) }
            ?.getOrNull()
            ?: CleanupQueueId.EXACT
        val availableSources = repository.buildSourceOptions(snapshot)
        val normalizedSourceId = sourceId
            ?.takeIf { wanted -> availableSources.any { it.id == wanted } }
            ?: ALL_SOURCE_ID
        val scopedSnapshot = repository.filterSnapshotBySource(snapshot, normalizedSourceId)
        val entries = repository.buildEntries(
            snapshot = scopedSnapshot,
            queueId = requestedQueue,
            dismissedEntryKeys = _state.value.dismissedEntryKeys
        )
        val definition = queueDefinition(requestedQueue)

        return JSONObject().apply {
            put("queueId", requestedQueue.name)
            put("sourceId", normalizedSourceId)
            put("emptyText", definition.emptyText)
            put(
                "entries",
                JSONArray().apply {
                    entries.forEach { entry -> put(remoteEntryJson(entry)) }
                }
            )
        }
    }

    private fun remoteEntryJson(entry: CleanupEntry): JSONObject =
        when (entry) {
            is CleanupEntry.PairEntry -> JSONObject().apply {
                put("kind", "pair")
                put("key", entry.key)
                put("title", entry.title)
                put("subtitle", entry.subtitle)
                put("metaText", entry.metaText)
                put("confidence", entry.pair.confidence)
                put("suggestedDeleteId", entry.pair.suggestedDeleteId)
                put("first", remoteImageJson(entry.first))
                put("second", remoteImageJson(entry.second))
            }
            is CleanupEntry.SingleEntry -> JSONObject().apply {
                put("kind", "single")
                put("key", entry.key)
                put("title", entry.title)
                put("subtitle", entry.subtitle)
                put("metaText", entry.metaText)
                put("image", remoteImageJson(entry.image))
            }
        }

    private fun remoteImageJson(image: MediaImage): JSONObject =
        JSONObject().apply {
            put("id", image.id)
            put("name", image.name)
            put("folder", image.folder)
            put("dimensionsText", image.dimensionsText)
            put("sizeText", image.sizeText)
        }

    private fun startRemoteScan(folder: String?): JSONObject {
        val current = _state.value
        if (!current.hasPermission) {
            return JSONObject()
                .put("accepted", false)
                .put("error", "Grant photo access on the phone before scanning.")
        }
        if (current.isScanning) {
            return JSONObject()
                .put("accepted", false)
                .put("error", "A scan is already running.")
        }

        val normalizedFolder = folder?.takeIf { it.isNotBlank() && it != ALL_FOLDERS_ID }
        val updated = current.copy(
            selectedScanFolder = normalizedFolder,
            selectedSourceId = ALL_SOURCE_ID
        )
        _state.value = updated
        repository.persistUiSession(updated)
        launchScan(folderOverride = normalizedFolder, preferredSourceId = ALL_SOURCE_ID)

        return JSONObject()
            .put("accepted", true)
            .put("folder", normalizedFolder ?: JSONObject.NULL)
    }

    private fun deleteImagesFromRemote(imageIds: Set<Long>): JSONObject = runBlocking {
        if (imageIds.isEmpty()) {
            return@runBlocking JSONObject()
                .put("deletedIds", JSONArray())
                .put("errors", JSONArray().put("No images were selected."))
        }
        if (!Environment.isExternalStorageManager()) {
            return@runBlocking JSONObject()
                .put("deletedIds", JSONArray())
                .put("errors", JSONArray().put("Grant All files access on the phone to enable browser-driven delete."))
        }

        val images = repository.findImagesByIds(snapshot, imageIds)
        if (images.isEmpty()) {
            return@runBlocking JSONObject()
                .put("deletedIds", JSONArray())
                .put("errors", JSONArray().put("Those images are no longer available."))
        }

        val result = repository.deleteImagesDirect(images)
        if (result.deletedIds.isNotEmpty()) {
            snapshot = repository.rebuildSnapshotAfterDelete(snapshot, result.deletedIds)
            val current = _state.value
            val availableSources = repository.buildSourceOptions(snapshot)
            val selectedSourceId = current.selectedSourceId
                .takeIf { sourceId -> availableSources.any { it.id == sourceId } }
                ?: ALL_SOURCE_ID
            val scopedSnapshot = repository.filterSnapshotBySource(snapshot, selectedSourceId)
            val selectedQueue = choosePreferredQueue(scopedSnapshot, current.selectedQueueId)
            val newEntries = repository.buildEntries(scopedSnapshot, selectedQueue, current.dismissedEntryKeys)
            val updated = current.copy(
                summaryText = buildSummaryText(scopedSnapshot),
                queues = repository.buildQueueDefinitions(scopedSnapshot),
                availableSources = availableSources,
                selectedSourceId = selectedSourceId,
                selectedQueueId = selectedQueue,
                entries = newEntries,
                activeReviewKey = newEntries.firstOrNull()?.key,
                imageCount = scopedSnapshot.imagesById.size,
                selectedEntryKeys = emptySet(),
                statusText = "Deleted ${result.deletedIds.size} photo(s) from the browser."
            )
            _state.value = updated
            repository.persistUiSession(updated)
            applyRemoteAccessState()
        }

        JSONObject().apply {
            put(
                "deletedIds",
                JSONArray().apply {
                    result.deletedIds.forEach { deletedId -> put(deletedId) }
                }
            )
            put(
                "errors",
                JSONArray().apply {
                    result.errors.forEach { error -> put(error) }
                }
            )
        }
    }

    private fun openRemoteImage(imageId: Long): RemoteImagePayload? =
        snapshot.imagesById[imageId]?.let(repository::openRemoteImage)

    private fun applyRemoteAccessState(
        isEnabled: Boolean = remoteServer != null,
        isStarting: Boolean = false,
        statusText: String? = null
    ) {
        val localUrl = if (isEnabled) resolveLocalUrl(DEFAULT_REMOTE_PORT) else null
        val hasDeleteAccess = Environment.isExternalStorageManager()
        val resolvedStatus = statusText ?: when {
            isStarting -> "Starting Wi-Fi dashboard..."
            isEnabled && localUrl != null -> "Open the address below on another device connected to the same Wi-Fi."
            isEnabled -> "Wi-Fi dashboard is running, but the phone does not currently expose a Wi-Fi address."
            else -> "Start Wi-Fi access to review this phone from another device."
        }

        _state.value = _state.value.copy(
            remoteAccess = RemoteAccessState(
                isEnabled = isEnabled,
                isStarting = isStarting,
                port = DEFAULT_REMOTE_PORT,
                localUrl = localUrl,
                statusText = resolvedStatus,
                remoteDeleteEnabled = hasDeleteAccess,
                deleteHint = if (hasDeleteAccess) {
                    "Browser delete is enabled. Photos are removed directly from the phone."
                } else {
                    "Browser review works now. To delete from the browser too, grant All files access in the Android app."
                }
            )
        )
    }

    private fun resolveLocalUrl(port: Int): String? =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
                ?.let { address -> "http://$address:$port" }
        }.getOrNull()

    private fun launchDeleteCommand(command: DeleteCommand) {
        _events.tryEmit(UiEvent.LaunchDeleteRequest(command))
    }

    private fun choosePreferredQueue(
        snapshot: CleanupSnapshot,
        preferredQueueId: CleanupQueueId
    ): CleanupQueueId {
        if (repository.queueCount(snapshot, preferredQueueId) > 0) return preferredQueueId
        return defaultQueueDefinitions()
            .firstOrNull { repository.queueCount(snapshot, it.id) > 0 }
            ?.id ?: preferredQueueId
    }

    private fun buildSummaryText(snapshot: CleanupSnapshot): String {
        val total = snapshot.exactPairs.size + snapshot.similarPairs.size +
            snapshot.blurryIds.size + snapshot.forwardIds.size +
            snapshot.screenshotIds.size + snapshot.textHeavyIds.size
        return if (snapshot.imagesById.isEmpty()) "No photos scanned yet."
        else "${snapshot.imagesById.size} photos scanned, $total items to review."
    }

    private fun currentScopedSnapshot(fullSnapshot: CleanupSnapshot = snapshot): CleanupSnapshot =
        repository.filterSnapshotBySource(fullSnapshot, _state.value.selectedSourceId)

    private fun sourceLabel(sourceId: String, availableSources: List<SourceOption>): String =
        availableSources.firstOrNull { it.id == sourceId }?.title ?: "All Photos"

    private fun queueDefinition(queueId: CleanupQueueId): QueueDefinition =
        defaultQueueDefinitions().first { it.id == queueId }

    override fun onCleared() {
        remoteServer?.stop()
        remoteServer = null
        super.onCleared()
    }
}
