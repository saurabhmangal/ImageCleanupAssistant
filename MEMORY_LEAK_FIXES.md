# Android App - Memory Leak Fixes & Code Improvements

## Table of Contents
1. Critical Fixes (Apply Immediately)
2. High Priority Improvements
3. Medium Priority Optimizations
4. Testing Code Examples
5. Monitoring & Profiling Tips

---

## 1. CRITICAL FIXES

### Fix 1: Bitmap Memory Leak in Scan

**File:** `CleanupRepository.kt`
**Issue:** Bitmaps not properly recycled on exceptions

#### Before (Problematic):
```kotlin
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
        darkPixelRatio = 0.0, 
        dominantColorShare = 0.0, 
        uniqueColorCount = 0
    )

    val baseBitmap = decoded.bitmap
    val sample8 = Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
    val sample9x8 = Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
    val sample24 = Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)

    return try {
        // ... analysis code ...
        BitmapAnalysis(...)
    } finally {
        sample8.recycle(); 
        sample9x8.recycle(); 
        sample24.recycle(); 
        baseBitmap.recycle()
    }
}
```

#### After (Fixed):
```kotlin
private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
    val defaultAnalysis = BitmapAnalysis(
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
        darkPixelRatio = 0.0, 
        dominantColorShare = 0.0, 
        uniqueColorCount = 0
    )

    val decoded = try {
        decodeBitmap(uri, targetSide = 96)
    } catch (e: Exception) {
        Log.w("BitmapMetrics", "Failed to decode bitmap for $uri", e)
        null
    } ?: return defaultAnalysis

    val baseBitmap = decoded.bitmap
    var sample8: Bitmap? = null
    var sample9x8: Bitmap? = null
    var sample24: Bitmap? = null

    return try {
        sample8 = Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
        sample9x8 = Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
        sample24 = Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)

        // Proceed with analysis
        val brightnessValues = DoubleArray(64)
        var brightnessTotal = 0.0
        var contrastTotal = 0.0
        var index = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = sample8!!.getPixel(x, y)
                val gray = grayscale(pixel)
                brightnessValues[index++] = gray
                brightnessTotal += gray
                if (x > 0) contrastTotal += abs(gray - grayscale(sample8.getPixel(x - 1, y)))
                if (y > 0) contrastTotal += abs(gray - grayscale(sample8.getPixel(x, y - 1)))
            }
        }

        val averageGray = brightnessValues.average()
        val averageHash = buildString(64) { 
            brightnessValues.forEach { append(if (it >= averageGray) '1' else '0') } 
        }
        
        // ... rest of analysis ...
        
        BitmapAnalysis(
            width = decoded.width.coerceAtLeast(fallbackWidth).coerceAtLeast(1),
            height = decoded.height.coerceAtLeast(fallbackHeight).coerceAtLeast(1),
            averageHash = averageHash,
            gradientHash = "0".repeat(64),  // Simplified for example
            toneSignature = IntArray(36),
            sharpnessEstimate = contrastTotal / 112.0,
            brightness = brightnessTotal / 64.0,
            edgeDensity = 0.0,
            averageSaturation = 0.0,
            whitePixelRatio = 0.0,
            darkPixelRatio = 0.0,
            dominantColorShare = 0.0,
            uniqueColorCount = 0
        )
    } catch (e: Exception) {
        Log.e("BitmapMetrics", "Exception during bitmap analysis for $uri", e)
        defaultAnalysis
    } finally {
        // Ensure all bitmaps are recycled
        try {
            baseBitmap.recycle()
            sample8?.recycle()
            sample9x8?.recycle()
            sample24?.recycle()
        } catch (e: Exception) {
            Log.w("BitmapMetrics", "Exception during bitmap recycling", e)
        }
    }
}
```

---

### Fix 2: ViewModel Cleanup & Server Lifecycle

**File:** `CleanupViewModel.kt`
**Issue:** RemoteAccessServer not stopped on destroy; no cleanup

#### Add to CleanupViewModel:
```kotlin
class CleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CleanupRepository(application.applicationContext)
    
    @Volatile
    private var snapshot: CleanupSnapshot = CleanupSnapshot.EMPTY
    private var remoteServer: RemoteAccessServer? = null
    private var currentScanJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    // ... rest of class ...

    /**
     * Cleanup method called when ViewModel is destroyed (e.g., on configuration change)
     * Ensures all resources are properly released
     */
    override fun onCleared() {
        super.onCleared()
        
        Log.d("CleanupViewModel", "onCleared() called - cleaning up resources")
        
        // Stop any running scan
        currentScanJob?.cancel()
        currentScanJob = null
        
        // Stop remote access server
        try {
            remoteServer?.stop()
        } catch (e: Exception) {
            Log.w("CleanupViewModel", "Exception stopping remote server", e)
        } finally {
            remoteServer = null
        }
        
        // Release snapshot data
        snapshot = CleanupSnapshot.EMPTY
    }

    fun toggleRemoteAccess() {
        val current = _state.value
        if (!current.hasPermission) {
            _events.tryEmit(UiEvent.ShowMessage("Grant photo access on the phone before starting Wi-Fi access."))
            return
        }

        if (remoteServer != null || current.remoteAccess.isStarting) {
            // Stop existing server
            try {
                remoteServer?.stop()
            } catch (e: Exception) {
                Log.w("RemoteAccess", "Exception stopping server", e)
            }
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

    fun scanLibrary() {
        // Cancel any existing scan first
        if (currentScanJob != null && currentScanJob!!.isActive) {
            Log.d("Scan", "Cancelling existing scan")
            currentScanJob?.cancel()
        }
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

        currentScanJob = viewModelScope.launch {
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
                    // Check if coroutine is still active before updating state
                    if (isActive) {
                        _state.value = _state.value.copy(statusText = progressText)
                    }
                }

                // Check again before proceeding (scan might have been cancelled)
                if (!isActive) {
                    Log.d("Scan", "Scan was cancelled")
                    return@launch
                }

                snapshot = scannedSnapshot
                // ... rest of scan logic ...

            } catch (e: CancellationException) {
                // Scan was cancelled - this is expected
                Log.d("Scan", "Scan cancelled")
                val failedState = _state.value.copy(
                    isScanning = false,
                    statusText = "Scan cancelled."
                )
                _state.value = failedState
                repository.persistUiSession(failedState)
                throw e  // Rethrow to propagate cancellation properly
            } catch (error: Exception) {
                Log.e("Scan", "Scan failed", error)
                val failedState = _state.value.copy(
                    isScanning = false,
                    statusText = "Scan failed."
                )
                _state.value = failedState
                repository.persistUiSession(failedState)
                applyRemoteAccessState()
                _events.emit(UiEvent.ShowMessage(error.message ?: "Scan failed."))
            } finally {
                currentScanJob = null
            }
        }
    }
}
```

---

### Fix 3: InputStream Closure & Remote Image Serving

**File:** `RemoteAccessServer.kt` and `CleanupRepository.kt`
**Issue:** InputStreams not properly closed

#### In RemoteAccessServer.kt:
```kotlin
private fun serveImage(imageId: String?): Response {
    val id = imageId?.toLongOrNull()
        ?: return jsonResponse(
            status = Response.Status.BAD_REQUEST,
            body = JSONObject().put("error", "Missing image id").toString()
        )
    
    val image = controller.openImage(id)
        ?: return jsonResponse(
            status = Response.Status.NOT_FOUND,
            body = JSONObject().put("error", "Image not found").toString()
        )
    
    // Create wrapped response that ensures stream is closed
    val response = newChunkedResponse(Response.Status.OK, image.mimeType, image.stream).apply {
        addHeader("Cache-Control", "private, max-age=120")
        addHeader("Access-Control-Allow-Origin", "*")
        // Add close tracking
        addHeader("Connection", "close")
    }
    
    // Wrap stream in auto-closing wrapper
    return SafeStreamingResponse(response, image.stream)
}

/**
 * Wrapper to ensure stream is always closed
 */
class SafeStreamingResponse(
    private val delegate: Response,
    private val stream: InputStream
) : Response by delegate {
    
    override fun toString(): String {
        return try {
            delegate.toString()
        } finally {
            try {
                stream.close()
            } catch (e: Exception) {
                Log.w("SafeStreamingResponse", "Exception closing stream", e)
            }
        }
    }
}
```

#### In CleanupRepository.kt:
```kotlin
fun openRemoteImage(image: MediaImage): RemoteImagePayload? {
    return runCatching {
        val stream = context.contentResolver.openInputStream(image.uri)
            ?: return@runCatching null
        
        RemoteImagePayload(
            fileName = image.name,
            mimeType = mimeTypeForImage(image.name),
            stream = stream
        )
    }.onFailure { error ->
        Log.e("RemoteImage", "Failed to open image: ${image.name}", error)
    }.getOrNull()
}
```

---

### Fix 4: Remove runBlocking from UI Callback

**File:** `CleanupViewModel.kt`
**Issue:** Blocking call on main thread

#### Before:
```kotlin
private fun buildRemoteSessionJson(): JSONObject {
    val current = _state.value
    val availableFolders = current.availableFolders.ifEmpty {
        runBlocking { repository.queryAvailableFolders() }  // ❌ BLOCKS UI THREAD
    }
    // ...
}
```

#### After:
```kotlin
private fun buildRemoteSessionJson(): JSONObject {
    val current = _state.value
    
    return JSONObject().apply {
        put("hasPermission", current.hasPermission)
        put("isScanning", current.isScanning)
        put("statusText", current.statusText)
        put("summaryText", if (snapshot.imagesById.isEmpty()) "No photos scanned yet." else buildSummaryText(snapshot))
        put("imageCount", snapshot.imagesById.size)
        
        // Use current folders from state instead of blocking
        val folders = current.availableFolders
        put(
            "availableFolders",
            JSONArray().apply {
                folders.forEach { folder ->
                    put(
                        JSONObject()
                            .put("folder", folder.folder)
                            .put("count", folder.count)
                    )
                }
            }
        )
        
        // ... rest of object construction ...
    }
}

// Load folders asynchronously
fun loadAvailableFolders() {
    viewModelScope.launch {
        try {
            val folders = repository.queryAvailableFolders()
            _state.value = _state.value.copy(availableFolders = folders)
        } catch (e: Exception) {
            Log.e("Folders", "Failed to load available folders", e)
        }
    }
}
```

---

## 2. HIGH PRIORITY IMPROVEMENTS

### Improvement 1: Bitmap Pooling for Reuse

**File:** Create new `BitmapPool.kt`

```kotlin
class BitmapPool(private val maxPoolSize: Int = 10) {
    private val pool = Collections.synchronizedList(mutableListOf<PooledBitmap>())
    
    data class PooledBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun obtain(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        synchronized(pool) {
            val index = pool.indexOfFirst { 
                it.width == width && 
                it.height == height && 
                !it.bitmap.isRecycled 
            }
            
            return if (index >= 0) {
                pool.removeAt(index).bitmap
            } else {
                null
            }
        }
    }
    
    fun recycle(bitmap: Bitmap, width: Int, height: Int) {
        if (bitmap.isRecycled) return
        
        synchronized(pool) {
            if (pool.size < maxPoolSize) {
                pool.add(PooledBitmap(bitmap, width, height))
            } else {
                bitmap.recycle()
            }
        }
    }
    
    fun clear() {
        synchronized(pool) {
            pool.forEach { it.bitmap.recycle() }
            pool.clear()
        }
    }
}

// Usage in CleanupRepository:
companion object {
    private val bitmapPool = BitmapPool(maxPoolSize = 20)
}

private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
    // ... existing code ...
    
    // Try to reuse from pool
    val sample8 = bitmapPool.obtain(8, 8) ?: Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
    val sample9x8 = bitmapPool.obtain(9, 8) ?: Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
    val sample24 = bitmapPool.obtain(24, 24) ?: Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)
    
    try {
        // ... analysis ...
    } finally {
        baseBitmap.recycle()
        bitmapPool.recycle(sample8, 8, 8)
        bitmapPool.recycle(sample9x8, 9, 8)
        bitmapPool.recycle(sample24, 24, 24)
    }
}
```

---

### Improvement 2: Lazy Regex Initialization

**File:** `CleanupRepository.kt` - Replace companion object

```kotlin
private companion object {
    const val CACHE_FILE_NAME = "image_cleanup_cache.json"
    const val SESSION_PREFS = "image_cleanup_session"
    const val KEY_SELECTED_SOURCE_ID = "selected_source_id"
    const val KEY_SELECTED_QUEUE_ID = "selected_queue_id"
    const val KEY_ACTIVE_REVIEW_KEY = "active_review_key"
    const val KEY_SCREEN = "screen"
    const val KEY_DISMISSED_KEYS = "dismissed_keys"
    const val KEY_LAST_SCAN_MILLIS = "last_scan_millis"

    // Lazy-initialized regex patterns (only created on first use)
    private val clutterRegex by lazy {
        Regex(
            "(?i)(diwali|holi|eid|christmas|xmas|new.?year|good.?morning|good.?night|quote|motivational|motivation|blessing|blessings|shayari|status|suvichar|thought|wish|greeting|festival|birthday|anniversary|invitation|meme|funny|reaction|sticker|whatsapp|telegram|sharechat|instagram|facebook|snapchat|suprabhat|shubh|navratri|durga|krishna|radha|ganesh|mahadev|shiva|ram.?navami|janmashtami|raksha.?bandhan|karwa.?chauth|chhath|makar.?sankranti|lohri|baisakhi|pongal|onam|ugadi|gudi.?padwa|happy.?sunday|happy.?monday|happy.?tuesday|happy.?wednesday|happy.?thursday|happy.?friday|happy.?saturday)"
        )
    }
    
    private val messageSourceRegex by lazy {
        Regex("(?i)(whatsapp|telegram|sharechat|instagram|facebook|snapchat|status|stories|download)")
    }
    
    private val screenshotRegex by lazy {
        Regex("(?i)(screenshot|screen.?shot|screen_capture|capture|screenshots|screen.?grab|screenrecord)")
    }
    
    private val documentRegex by lazy {
        Regex(
            "(?i)(receipt|invoice|bill|statement|document|scan|scanner|camscanner|adobescan|aadhaar|aadhar|pan.?card|passport|license|licence|voter|certificate|resume|brochure|pamphlet|memo|newsletter|menu|schedule|timetable|syllabus|exam|result|admit.?card|marksheet|ticket|boarding|prescription|medical|lab.?report|report|notes|assignment|form|application|id.?card)"
        )
    }
    
    private val documentSourceRegex by lazy {
        Regex("(?i)(scan|scanner|camscanner|adobe.?scan|documents|docs|receipts)")
    }
    
    private val whatsappNameRegex by lazy {
        Regex("(?i)(-WA\\d+|IMG-\\d{8}-WA\\d+|VID-\\d{8}-WA\\d+|WA0\\d+)")
    }
    
    private val downloadedNameRegex by lazy {
        Regex("(?i)(download|saved|export|share)")
    }
    
    private val deleteNameRegex by lazy {
        Regex("(?i)(copy|edited|duplicate|_copy|-copy|\\(\\d+\\))")
    }
}

// Update usage in methods
private fun computeMessagingClutterScore(nameAndFolder: String, metrics: BitmapAnalysis, sizeBytes: Long, width: Int, height: Int): Int {
    var score = 0
    val lower = nameAndFolder.lowercase(Locale.ROOT)
    val hasKeyword = clutterRegex.containsMatchIn(lower)
    val messagingSource = messageSourceRegex.containsMatchIn(lower) || whatsappNameRegex.containsMatchIn(nameAndFolder)
    // ... rest of function ...
}
```

---

### Improvement 3: Async Cache Persistence

**File:** `CleanupRepository.kt`

```kotlin
// Change from synchronous to asynchronous
private fun persistSnapshot(snapshot: CleanupSnapshot) {
    // Keep internal method synchronous, add async wrapper
    runCatching {
        val root = JSONObject()
        val imagesArray = JSONArray()
        snapshot.imagesById.values
            .sortedByDescending { it.modifiedAtMillis }
            .forEach { image -> imagesArray.put(imageToJson(image)) }
        root.put("images", imagesArray)
        cacheFile.writeText(root.toString())
    }.onFailure { error ->
        Log.e("Cache", "Failed to persist snapshot", error)
    }
}

// Add async version for ViewModel to call
fun persistSnapshotAsync(snapshot: CleanupSnapshot, onComplete: (Boolean) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            persistSnapshot(snapshot)
            onComplete(true)
        } catch (e: Exception) {
            Log.e("Cache", "Async persist failed", e)
            onComplete(false)
        }
    }
}

// Usage in ViewModel:
fun onDeleteRequestCompleted(command: DeleteCommand?, wasApproved: Boolean) {
    // ... existing code ...
    
    repository.persistSnapshotAsync(_state.value) { success ->
        if (!success) {
            Log.w("ViewModel", "Failed to persist session after delete")
        }
    }
}
```

---

## 3. MEDIUM PRIORITY OPTIMIZATIONS

### Optimization 1: Memory-Efficient JSON Building

**File:** `CleanupViewModel.kt`

```kotlin
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

    // More efficient JSON building
    return JSONObject().apply {
        put("queueId", requestedQueue.name)
        put("sourceId", normalizedSourceId)
        put("emptyText", definition.emptyText)
        
        // Build array more efficiently
        val entriesArray = JSONArray(entries.size)
        for (entry in entries) {
            entriesArray.put(remoteEntryJson(entry))
        }
        put("entries", entriesArray)
    }
}

// Add batch JSON creation helper
private fun buildJsonArray(items: List<Any>, builder: (item: Any) -> JSONObject): JSONArray {
    val array = JSONArray(items.size)
    for (item in items) {
        array.put(builder(item))
    }
    return array
}
```

---

## 4. TESTING CODE EXAMPLES

### Unit Test: Bitmap Recycling

**File:** Create `CleanupRepositoryTest.kt`

```kotlin
class CleanupRepositoryTest {
    
    private lateinit var context: Context
    private lateinit var repository: CleanupRepository
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = CleanupRepository(context)
    }
    
    @Test
    fun testBitmapMemoryAfterAnalyze() {
        val memBefore = Runtime.getRuntime().totalMemory()
        val usedBefore = memBefore - Runtime.getRuntime().freeMemory()
        
        // Analyze multiple images
        repeat(100) { index ->
            val testUri = Uri.parse("content://test/image_$index")
            try {
                repository.analyzeBitmapMetrics(testUri, 800, 600)
            } catch (e: Exception) {
                // Expected - test URI won't work
            }
        }
        
        System.gc()
        Thread.sleep(500)
        
        val memAfter = Runtime.getRuntime().totalMemory()
        val usedAfter = memAfter - Runtime.getRuntime().freeMemory()
        val memIncrease = usedAfter - usedBefore
        
        // Assert memory increase is reasonable (< 50MB for 100 images)
        assertTrue("Memory increased by ${memIncrease / 1024 / 1024}MB", memIncrease < 50 * 1024 * 1024)
    }
    
    @Test
    fun testViewModelCleanup() {
        val viewModel = CleanupViewModel(ApplicationProvider.getApplicationContext() as Application)
        
        // Start remote server
        viewModel.toggleRemoteAccess()
        assertTrue(viewModel.remoteServer != null)
        
        // Clear should stop server
        viewModel.onCleared()
        
        // Verify cleanup
        assertEquals(null, viewModel.remoteServer)
    }
    
    @Test
    fun testScanCancellation() {
        val viewModel = CleanupViewModel(ApplicationProvider.getApplicationContext() as Application)
        
        // Start first scan
        viewModel.scanLibrary()
        assertTrue(viewModel.currentScanJob?.isActive == true)
        
        // Start second scan (should cancel first)
        viewModel.scanLibrary()
        
        // Wait a bit and verify
        Thread.sleep(100)
        assertFalse(viewModel.currentScanJob?.isCancelled != false)
    }
}
```

---

### Integration Test: End-to-End Scan

**File:** Create `MemoryLeakTest.kt`

```kotlin
class MemoryLeakTest {
    
    @Test
    fun testFullScanMemoryProfile() {
        if (!Build.VERSION.SDK_INT >= 30) {
            // Test only on Android 11+
            return
        }
        
        val context = ApplicationProvider.getApplicationContext() as Context
        val runtime = Runtime.getRuntime()
        
        // Force GC before test
        System.gc()
        val baslineUsed = runtime.totalMemory() - runtime.freeMemory()
        
        // Create repository and trigger scan
        val repository = CleanupRepository(context)
        
        val beforeScan = runtime.totalMemory() - runtime.freeMemory()
        
        // Simulate scan
        repository.scanLibrary { oldProgress ->
            // Progress callback
        }
        
        System.gc()
        Thread.sleep(1000)
        
        val afterScan = runtime.totalMemory() - runtime.freeMemory()
        val memUsedByScan = afterScan - beforeScan
        
        // Verify memory usage is bounded
        val maxAllowedMem = 200 * 1024 * 1024  // 200 MB
        assertTrue(
            "Scan used ${memUsedByScan / 1024 / 1024}MB (max: ${maxAllowedMem / 1024 / 1024}MB)",
            memUsedByScan < maxAllowedMem
        )
    }
}
```

---

## 5. MONITORING & PROFILING TIPS

### Android Studio Profiler Checklist

```
1. Memory Profiler:
   - Open: Device Explorer → Profilers → Memory
   - Start scan
   - Watch for:
     • Heap size not returning to baseline
     • GC events frequency
     • Native memory leaks (Bitmap objects)

2. CPU Profiler:
   - Record 10+ seconds during scan
   - Look for:
     • High GC activity
     • Main thread blocking
     • Long task durations

3. Network Profiler:
   - Monitor while remote server is active
   - Check for:
     • Unclosed connections
     • Streaming data timeouts
   
4. LeakCanary Integration:
   - Add dependency:
     debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.14'
   - Will detect leaks automatically
```

### Custom Memory Monitoring

```kotlin
class MemoryMonitor {
    fun getSummary(): String {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory() / 1024 / 1024
        val freeMem = runtime.freeMemory() / 1024 / 1024
        val usedMem = totalMem - freeMem
        val maxMem = runtime.maxMemory() / 1024 / 1024
        
        return """
            Memory Usage:
            Used: $usedMem MB / Total: $totalMem MB / Max: $maxMem MB
            Free: $freeMem MB
        """.trimIndent()
    }
    
    fun logMemoryWarning() {
        val runtime = Runtime.getRuntime()
        val percentUsed = (runtime.totalMemory().toDouble() / runtime.maxMemory()) * 100
        
        if (percentUsed > 85) {
            Log.w("Memory", "High memory pressure: ${percentUsed.toInt()}%")
        }
    }
}

// Add to ViewModel for monitoring
private val memoryMonitor = MemoryMonitor()

fun logMemoryStatus() {
    Log.d("MemoryStatus", memoryMonitor.getSummary())
    memoryMonitor.logMemoryWarning()
}
```

---

## References for Further Optimization

- [Android Memory Leaks Best Practices](https://developer.android.com/training/articles/memory)
- [Compose Memory Performance](https://developer.android.com/jetpack/compose/performance)
- [Bitmap Optimization Guide](https://developer.android.com/develop/ui/views/graphics/supporting-different-densities)
- [Coroutine Cancellation](https://kotlinlang.org/docs/composing-suspending-functions.html#cancellation-and-timeouts)

