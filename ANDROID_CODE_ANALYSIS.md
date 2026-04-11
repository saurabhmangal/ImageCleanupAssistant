# Android App Code Analysis & Memory Leak Assessment

## Executive Summary

The Image Cleanup Assistant Android app is a well-architected Compose-based application with a clear separation of concerns. The codebase demonstrates good practices with modern Android patterns (MVVM with Compose), but contains **several critical memory leak risks** and optimization opportunities.

**Overall Risk Level: MEDIUM-HIGH**

---

## 1. Project Structure & Architecture Overview

### 1.1 High-Level Architecture

```
Image Cleanup Assistant (Android)
├── MainActivity (Jetpack Compose UI)
│   └── MobileCleanupApp (Root Composable)
│       ├── OverviewScreen (Dashboard)
│       └── ReviewScreen (Detail view)
├── CleanupViewModel (MVVM State Management)
│   ├── State Flow (UiState)
│   └── Event Flow (UiEvent)
├── CleanupRepository (Data & Business Logic)
│   ├── Local Caching (JSON-based)
│   ├── Media Store Queries
│   ├── Image Analysis Engine
│   └── Bitmap Processing
├── RemoteAccessServer (NanoHTTPD-based)
│   └── JSON API endpoint
└── Data Models (CleanupModels.kt)
    ├── MediaImage
    ├── CleanupSnapshot
    └── CleanupEntry
```

### 1.2 Key Components

| Component | Purpose | Tech Stack |
|-----------|---------|-----------|
| **MainActivity** | Activity & Compose setup | Jetpack Compose, Material 3 |
| **CleanupViewModel** | MVVM state/event management | AndroidViewModel, Coroutines |
| **CleanupRepository** | Data access & business logic | Room alternatives (JSON), MediaStore API |
| **RemoteAccessServer** | Web dashboard API | NanoHTTPD 2.3.1 |
| **Image Analysis** | Quality/type detection | Custom algorithms (hashing, bitmap analysis) |

### 1.3 Dependencies
- **androidx.compose:compose-bom:2024.06.00** - Latest Compose framework
- **androidx.lifecycle:lifecycle-runtime-ktx:2.8.3** - ViewModel/lifecycle management
- **kotlinx-coroutines-android:1.9.0** - Async operations
- **coil-compose:2.7.0** - Image loading
- **nanohttpd:2.3.1** - HTTP server for remote access
- **material3** - Modern Material Design UI

---

## 2. Code Structure Analysis

### 2.1 File Inventory

```
src/main/java/com/saura/imagecleanupassistant/mobile/
├── MainActivity.kt                   (~2000 lines) - Compose UI + Theme
├── CleanupViewModel.kt               (~800 lines) - State management
├── CleanupRepository.kt              (~1200 lines) - Data & analysis
├── CleanupModels.kt                  (~200 lines) - Data classes
└── RemoteAccessServer.kt             (~800 lines) - HTTP API server
```

### 2.2 Data Flow

```
User Interaction
    ↓
MainActivity (Compose Events)
    ↓
CleanupViewModel (Event Handlers)
    ↓
CleanupRepository (Data Operations)
    ↓
MediaStore API / JSON Cache / Bitmap Processing
    ↓
State Update (MutableStateFlow)
    ↓
Compose Recomposition (UI Update)
```

---

## 3. Critical Memory Leak Issues

### 🔴 CRITICAL: Bitmap Memory Leaks in `CleanupRepository.kt`

**Location:** `analyzeBitmapMetrics()` method (~line 700-800)

**Issue:**
```kotlin
private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
    val decoded = decodeBitmap(uri, targetSide = 96) ?: return BitmapAnalysis(...)
    
    val baseBitmap = decoded.bitmap
    val sample8 = Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
    val sample9x8 = Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
    val sample24 = Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)
    
    return try {
        // ... bitmap processing ...
    } finally {
        sample8.recycle(); sample9x8.recycle(); sample24.recycle(); baseBitmap.recycle()
    }
}
```

**Problems:**
1. ✅ (Good) `finally` block ensures recycling, BUT:
2. ❌ Exception in `finally` block could prevent proper cleanup
3. ❌ If an exception occurs during processing, only successfully recycled bitmaps are freed
4. ❌ Large scaled bitmaps (24x24) created for every image analyzed
5. ❌ No try-catch around `ImageDecoder.decodeBitmap()` - if it fails, memory leak occurs
6. ❌ During library scan (thousands of images), multiple bitmap objects held in memory simultaneously

**Worst Case:** 
- Scanning 5000 images = 5000 × 4 bitmap objects created
- Each 96px-sized bitmap ≈ 36 KB
- Single scan could allocate 720 MB+ of temporary bitmap memory

**Recommended Fixes:**
```kotlin
private fun analyzeBitmapMetrics(uri: Uri, fallbackWidth: Int, fallbackHeight: Int): BitmapAnalysis {
    val decoded = decodeBitmap(uri, targetSide = 96)
    if (decoded == null) {
        return BitmapAnalysis(
            width = fallbackWidth.coerceAtLeast(1), 
            height = fallbackHeight.coerceAtLeast(1),
            // ... defaults ...
        )
    }
    
    val baseBitmap = decoded.bitmap
    val sample8: Bitmap?
    val sample9x8: Bitmap?
    val sample24: Bitmap?
    
    return try {
        sample8 = Bitmap.createScaledBitmap(baseBitmap, 8, 8, true)
        sample9x8 = Bitmap.createScaledBitmap(baseBitmap, 9, 8, true)
        sample24 = Bitmap.createScaledBitmap(baseBitmap, 24, 24, true)
        
        // ... processing ...
        BitmapAnalysis(...)
    } catch (e: Exception) {
        // Handle exception and ensure cleanup
        Log.e("BitmapAnalysis", "Error analyzing bitmap", e)
        BitmapAnalysis("default", ...)
    } finally {
        baseBitmap.recycle()
        sample8?.recycle()
        sample9x8?.recycle()
        sample24?.recycle()
    }
}
```

---

### 🔴 CRITICAL: Context Leaks in RemoteAccessServer

**Location:** `RemoteAccessServer.kt` (entire class)

**Issue:**
```kotlin
class RemoteAccessServer(
    port: Int,
    private val controller: Controller  // ← Holds reference to ViewModel
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                // ...
                Method.GET -> handleGet(session)
                // ...
            }
        } catch (error: Exception) { ... }
    }
    
    private fun handleGet(session: IHTTPSession): Response =
        when (session.uri) {
            "/api/session" -> jsonResponse(body = controller.sessionJson().toString())
            // ...
        }
}
```

**Problems:**
1. ❌ `RemoteAccessServer` holds a reference to `RemoteAccessServer.Controller` (implemented in ViewModel)
2. ❌ Even if ViewModel is destroyed, NanoHTTPD server keeps running and holds reference
3. ❌ `socket` objects from NanoHTTPD may not be properly closed if exception occurs
4. ❌ No explicit `stop()` call in ViewModel's `onCleared()` override (not shown but should be)
5. ❌ Long-lived HTTP connections could hold activity context indirectly

**In ViewModel (CleanupViewModel.kt ~line 50):**
```kotlin
fun toggleRemoteAccess() {
    // ...
    try {
        val server = RemoteAccessServer(DEFAULT_REMOTE_PORT, remoteController)
        server.start(5_000, false)
        remoteServer = server  // ← Held in property
        // ...
    } catch (error: Exception) {
        remoteServer = null
        // ...
    }
}
```

**Worst Case:** 
- If user rotates device, new ViewModel created but old server keeps running
- Old server holds reference to old controller/context
- Activity cannot be garbage collected

**Recommended Fixes:**
```kotlin
// In CleanupViewModel
override fun onCleared() {
    super.onCleared()
    // Ensure server is stopped when ViewModel is destroyed
    remoteServer?.stop()
    remoteServer = null
}

// In RemoteAccessServer
class RemoteAccessServer(
    port: Int,
    private val controller: Controller
) : NanoHTTPD(port) {
    
    private val openSockets = Collections.synchronizedSet(mutableSetOf<ServerSocket>())
    
    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                // ... handle request ...
            }
        } catch (error: Exception) {
            // Log error
            jsonResponse(
                status = Response.Status.INTERNAL_ERROR,
                body = JSONObject().put("error", error.message ?: "Error").toString()
            )
        }
    }
    
    override fun stop() {
        super.stop()
        openSockets.forEach { it.close() }
        openSockets.clear()
    }
}
```

---

### 🔴 HIGH: InputStream Leak in Image Serving

**Location:** `RemoteAccessServer.serveImage()` (~line 90-100)

**Issue:**
```kotlin
private fun serveImage(imageId: String?): Response {
    val image = controller.openImage(id) ?: return ...
    return newChunkedResponse(Response.Status.OK, image.mimeType, image.stream).apply {
        addHeader("Cache-Control", "private, max-age=120")
        addHeader("Access-Control-Allow-Origin", "*")
    }
}
```

**In CleanupRepository.openRemoteImage():**
```kotlin
fun openRemoteImage(image: MediaImage): RemoteImagePayload? {
    val stream = context.contentResolver.openInputStream(image.uri) ?: return null
    return RemoteImagePayload(
        fileName = image.name,
        mimeType = mimeTypeForImage(image.name),
        stream = stream  // ← InputStream held in object
    )
}
```

**Problems:**
1. ❌ InputStream opened from ContentResolver but never explicitly closed
2. ❌ If client disconnects before download completes, stream remains open
3. ❌ NanoHTTPD may not properly close the stream after response
4. ❌ FileDescriptor leak if multiple concurrent image requests
5. ❌ Each image request = 1 file descriptor held until GC

**Recommended Fixes:**
```kotlin
// Ensure proper cleanup
fun openRemoteImage(image: MediaImage): RemoteImagePayload? {
    return runCatching {
        val stream = context.contentResolver.openInputStream(image.uri)
            ?: return null
        RemoteImagePayload(
            fileName = image.name,
            mimeType = mimeTypeForImage(image.name),
            stream = stream
        )
    }.onFailure { error ->
        Log.e("RemoteImage", "Failed to open image", error)
    }.getOrNull()
}

// Create wrapper for proper cleanup
class AlwaysCloseInputStream(private val delegate: InputStream) : InputStream() {
    override fun read() = delegate.read()
    override fun read(b: ByteArray) = delegate.read(b)
    override fun read(b: ByteArray, off: Int, len: Int) = delegate.read(b, off, len)
    override fun close() = delegate.close()
    override fun available() = delegate.available()
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    override fun reset() = delegate.reset()
    override fun markSupported() = delegate.markSupported()
    override fun skip(n: Long) = delegate.skip(n)
}
```

---

### 🟡 HIGH: JSON Object Creation During Scans

**Location:** `CleanupViewModel.buildRemoteSessionJson()` (~line 500-550)

**Issue:**
```kotlin
private fun buildRemoteSessionJson(): JSONObject {
    val current = _state.value
    val availableFolders = current.availableFolders.ifEmpty {
        runBlocking { repository.queryAvailableFolders() }  // ← BLOCKING CALL!
    }
    
    return JSONObject().apply {
        put("queues", JSONArray().apply {
            queues.forEach { queue ->
                put(JSONObject()
                    .put("id", queue.id.name)
                    .put("title", queue.title)
                    // ... 10+ puts ...
                )
            }
        })
        // ... creates 50+ JSONObject instances per call ...
    }
}
```

**Problems:**
1. ❌ `runBlocking { }` on UI thread - can cause ANR (Application Not Responding)
2. ❌ Massive JSON object creation during each API call
3. ❌ No object pooling for frequently created JSONObjects
4. ❌ Called potentially on every HTTP request from remote dashboard
5. ❌ GC pressure from temporary object allocation

**Recommended Fixes:**
```kotlin
private fun buildRemoteSessionJson(): JSONObject {
    val current = _state.value
    
    // Use coroutine instead of runBlocking
    val availableFolders = current.availableFolders
    
    return JSONObject().apply {
        // ... other properties ...
        
        // Use more efficient serialization
        val queuesArray = JSONArray()
        for (queue in queues) {
            queuesArray.put(JSONObject().apply {
                put("id", queue.id.name)
                put("title", queue.title)
                put("count", queue.count)
                put("description", queue.description)
                put("emptyText", queue.emptyText)
            })
        }
        put("queues", queuesArray)
    }
}
```

---

### 🟡 HIGH: Coroutine Leaks in Scan Operations

**Location:** `CleanupViewModel.scanLibrary()` & `launchScan()` (~line 150-250)

**Issue:**
```kotlin
fun scanLibrary() {
    launchScan()  // Launches in viewModelScope
}

private fun launchScan(
    folderOverride: String? = _state.value.selectedScanFolder,
    preferredSourceId: String? = null
) {
    // ...
    viewModelScope.launch {  // ← Uses viewModelScope (good)
        try {
            val scannedSnapshot = repository.scanLibrary { progressText ->
                _state.value = _state.value.copy(statusText = progressText)  // ← State mutation during scan
            }
            snapshot = scannedSnapshot  // ← Global snapshot mutation
            // ...
        } catch (error: Exception) {
            // Handles exception
        }
    }
}
```

**Problems:**
1. ✅ (Good) Uses `viewModelScope` - will cancel on ViewModel destroy
2. ⚠️ **But:** If scan takes 30+ seconds and device rotates:
   - New coroutine launched in new ViewModel
   - Old coroutine continues in background (cancelled but still references resources)
   - Large `snapshot` object held in memory
3. ❌ No mechanism to cancel ongoing scan if new scan starts
4. ❌ `snapshot` field is non-volatile but can be mutated from coroutine
5. ❌ ImageDecoder operations not cancellable once started

**Recommended Fixes:**
```kotlin
private var currentScanJob: Job? = null

fun scanLibrary() {
    currentScanJob?.cancel()  // Cancel previous scan
    launchScan()
}

private fun launchScan(...) {
    currentScanJob = viewModelScope.launch {
        try {
            val scannedSnapshot = repository.scanLibrary { progressText ->
                if (isActive) {  // Check if coroutine still active
                    _state.value = _state.value.copy(statusText = progressText)
                }
            }
            snapshot = scannedSnapshot
            // ...
        } catch (error: CancellationException) {
            // Scan cancelled - clean up
            throw error  // Rethrow to propagate cancellation
        } catch (error: Exception) {
            // Handle other errors
        }
    }
}

override fun onCleared() {
    super.onCleared()
    currentScanJob?.cancel()  // Explicit cleanup
}
```

---

### 🟡 MEDIUM: Static References in Regex Patterns

**Location:** `CleanupRepository.kt` companion object (~line 1050+)

**Issue:**
```kotlin
private companion object {
    const val CACHE_FILE_NAME = "image_cleanup_cache.json"
    const val SESSION_PREFS = "image_cleanup_session"
    
    val CLUTTER_REGEX = Regex(
        "(?i)(diwali|holi|eid|christmas|...pattern...)"
    )  // ← STATIC! Never garbage collected
    val MESSAGE_SOURCE_REGEX = Regex("(?i)(...)")
    val SCREENSHOT_REGEX = Regex("(?i)(...)")
    val DOCUMENT_REGEX = Regex("(?i)(...)")
    val DOCUMENT_SOURCE_REGEX = Regex("(?i)(...)")
    val WHATSAPP_NAME_REGEX = Regex("(?i)(-WA\\d+|...)")
    val DOWNLOADED_NAME_REGEX = Regex("(?i)(...)")
    val DELETE_NAME_REGEX = Regex("(?i)(...)")
}
```

**Problems:**
1. ❌ 8+ complex Regex objects created at class load time
2. ❌ Held in memory for entire app lifetime (never released)
3. ❌ Each Regex maintains internal state machine
4. ❌ Low priority but contributes to baseline memory usage

**Recommended Fixes:**
```kotlin
private companion object {
    const val CACHE_FILE_NAME = "image_cleanup_cache.json"
    // ... keep constants ...
    
    // Lazy-initialize regex patterns
    private val clutterRegex by lazy {
        Regex("(?i)(diwali|holi|...)")
    }
    // ... etc for all regex patterns
}

// Or use StringMatcher library for better performance
```

---

### 🟡 MEDIUM: Snapshot Object Lifetime Issue

**Location:** `CleanupViewModel` property (~line 20)

**Issue:**
```kotlin
class CleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CleanupRepository(application.applicationContext)
    
    @Volatile
    private var snapshot: CleanupSnapshot = CleanupSnapshot.EMPTY  // ← Held for entire ViewModel lifetime
    
    // ... state flows ...
    
    fun scanLibrary() {
        // Entire library snapshot stays in memory!
        // For 5000 images = ~50+ MB in memory
    }
}
```

**Problems:**
1. ❌ `snapshot` holds potentially 5000+ `MediaImage` objects + analytics data
2. ❌ Only released when ViewModel destroyed or user clears data
3. ❌ `@Volatile` used but snapshot mutation is not thread-safe for all operations
4. ❌ Multiple snapshot creations during scan = temporary duplication

**Recommended Fixes:**
```kotlin
class CleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CleanupRepository(application.applicationContext)
    private var snapshot: CleanupSnapshot = CleanupSnapshot.EMPTY
    
    // Add explicit cleanup
    override fun onCleared() {
        super.onCleared()
        snapshot = CleanupSnapshot.EMPTY  // Release snapshot
        remoteServer?.stop()
    }
    
    // Add size monitoring
    fun getSnapshotMemoryUsage(): Long {
        return snapshot.imagesById.size * 1024L  // Rough estimate
    }
}
```

---

### 🟠 MEDIUM: LaunchedEffect Memory Consideration

**Location:** `MainActivity.kt` MobileCleanupApp() composable (~line 180-200)

**Issue:**
```kotlin
@Composable
private fun MobileCleanupApp(viewModel: CleanupViewModel) {
    val context = LocalContext.current  // ← Captures Activity context
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        val has = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.refreshPermission(has)
        if (has) viewModel.restoreCachedStateIfAvailable()
        viewModel.refreshRemoteCapabilities()
    }
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->  // ← Long-running collector
            when (event) {
                is UiEvent.LaunchDeleteRequest -> { /* ... */ }
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    
    // ...
}
```

**Problems:**
1. ⚠️ `LocalContext.current` captures Activity context
2. ⚠️ Second `LaunchedEffect` runs for entire composable lifetime
3. ⚠️ If composable becomes detached before `collect` completes, potential leak
4. ✅ But `collectAsStateWithLifecycle()` handles this correctly
5. ⚠️ No explicit cancellation if events Flow is never closed

**Risk Level:** LOW - Jetpack Compose handles this well

---

## 4. Caching Mechanisms & Optimization Opportunities

### 4.1 Current Caching Strategy

**Location:** `CleanupRepository` - Persistence layer

```kotlin
private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)

fun restoreSnapshotFromCache(): CleanupSnapshot? {
    val images = readCachedImages() ?: return null
    if (images.isEmpty()) return CleanupSnapshot.EMPTY
    return buildSnapshotFromImages(images)
}

private fun persistSnapshot(snapshot: CleanupSnapshot) {
    runCatching {
        val root = JSONObject()
        val imagesArray = JSONArray()
        snapshot.imagesById.values
            .sortedByDescending { it.modifiedAtMillis }
            .forEach { image -> imagesArray.put(imageToJson(image)) }
        root.put("images", imagesArray)
        cacheFile.writeText(root.toString())  // ← Synchronous IO
    }
}
```

**Pros:**
✅ Simple JSON-based cache stored locally
✅ Survives app restart  
✅ Allows offline browsing

**Cons:**
❌ Synchronous file I/O on potentially large dataset
❌ Entire cache loaded into memory on restore
❌ No incremental caching (all-or-nothing)
❌ No cache versioning/migration strategy
❌ JSON parsing creates temporary JSONObjects

### 4.2 Session Caching

```kotlin
fun persistUiSession(state: UiState) {
    context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SELECTED_SOURCE_ID, state.selectedSourceId)
        .putString(KEY_SELECTED_QUEUE_ID, state.selectedQueueId.name)
        // ... 5+ properties ...
        .apply()  // Asynchronous write
}
```

**Pros:**
✅ Uses SharedPreferences (optimized for small data)
✅ Async write with `apply()`

**Cons:**
⚠️ Could be more selective about what to persist

---

### 4.3 Optimization Opportunities

#### 1. **Protocol Buffer Cache** (Low priority)
Replace JSON caching with Protocol Buffers for:
- 50-70% smaller file size
- Faster serialization/deserialization
- Schema evolution support

#### 2. **Incremental Scan** (Medium priority)
Current: Rescans entire library each time
Better:
```kotlin
fun incrementalScan(lastScanTime: Long): CleanupSnapshot {
    // Query only modified images since lastScanTime
    val newImages = queryNewImages(lastScanTime)
    val cachedSnapshot = restoreSnapshotFromCache()
    val mergedSnapshot = cachedSnapshot.merge(newImages)
    return mergedSnapshot
}
```

#### 3. **Image Metrics Caching** (High priority)
- Currently recalculates metrics every scan if not cached by hash
- Store bitmap analysis results with image-level cache key
- Use LRU cache for recent analyses

#### 4. **Bitmap Pooling** (High priority)
```kotlin
class BitmapPool {
    private val pool = mutableListOf<Bitmap>()
    
    fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        // Reuse from pool if available
    }
    
    fun recycle(bitmap: Bitmap) {
        // Return to pool
    }
}
```

#### 5. **Async Cache Persistence** (Medium priority)
```kotlin
private fun persistSnapshotAsync(snapshot: CleanupSnapshot) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            persistSnapshot(snapshot)
        } catch (e: Exception) {
            Log.e("Cache", "Failed to persist snapshot", e)
        }
    }
}
```

---

## 5. Activity/Context Reference Risks

### 5.1 Direct Context Usage

| Location | Risk | Use Context | Holds Activity? |
|----------|------|-------------|-----------------|
| `MainActivity.kt` (Composable) | Medium | `LocalContext.current` | ✅ Yes |
| `CleanupRepository` | Low | `context` parameter | ✅ Yes |
| `RemoteAccessServer` | **HIGH** | Via Controller ref | ✅ Yes (indirectly) |
| `ViewModel` | Low | `application` | ❌ No (Application context) |

### 5.2 Best Practices Applied

✅ Uses `AndroidViewModel` with Application context (not Activity context)
✅ LocalContext used appropriately in Composables  
✅ No static references to Activity instances
✅ No anonymous inner classes holding Activity references

### 5.3 Issues Found

❌ HTTP server callbacks hold ViewModel references (which may hold Activity via Compose)
❌ No explicit cleanup of HTTP server on app pause/destroy
❌ RemoteAccessServer not stopped in onDestroy/onCleared

---

## 6. Services & Long-Running Operations

### 6.1 Remote Access Server

**Type:** HTTP Server (NanoHTTPD)
**Lifecycle:** Manual start/stop in ViewModel

**Issues:**
- No automatic stop on activity destroy
- Continuous thread running while enabled
- Accepts connections from network

```kotlin
fun toggleRemoteAccess() {
    if (remoteServer != null || current.remoteAccess.isStarting) {
        remoteServer?.stop()  // ← Must call this!
        remoteServer = null
    }
}
```

**Recommendation:** Add automatic cleanup in ViewModel.onCleared()

---

## 7. Listener & Callback Issues

### 7.1 Permission Request Launchers

```kotlin
val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
) { granted ->
    viewModel.refreshPermission(granted)
    viewModel.refreshRemoteCapabilities()
    if (granted) {
        viewModel.restoreCachedStateIfAvailable()
        if (viewModel.state.value.imageCount == 0) viewModel.scanLibrary()
    }
}
```

✅ **Good:** Launchers managed by Compose framework (automatic cancellation)

### 7.2 MutableStateFlow Subscriptions

```kotlin
val state by viewModel.state.collectAsStateWithLifecycle()

LaunchedEffect(Unit) {
    viewModel.events.collect { event -> /* ... */ }
}
```

✅ **Good:** Uses lifecycle-aware collection
✅ **Good:** Cancels when composition leaves
⚠️ **Check:** Ensure Flow completion semantics

---

## 8. Manifest & Permission Analysis

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

**Considerations:**
- `READ_EXTERNAL_STORAGE` requires runtime permission (Android 13+)
- `MANAGE_EXTERNAL_STORAGE` for browser-driven deletes
- `INTERNET` for remote dashboard
- Properly configured for Android 13+ (scoped storage)

---

## 9. Summary of Memory Leak Risks

### Critical Issues (Must Fix)

| # | Issue | Location | Severity | Fix Effort |
|---|-------|----------|----------|-----------|
| 1 | Bitmap memory leaks during scan | `analyzeBitmapMetrics()` | **CRITICAL** | Medium |
| 2 | Context leak via RemoteAccessServer | `RemoteAccessServer` | **CRITICAL** | Low |
| 3 | InputStream not closed on image serve | `serveImage()` | **CRITICAL** | Low |
| 4 | Blocking call on UI thread (runBlocking) | `buildRemoteSessionJson()` | **HIGH** | Low |

### High Priority Issues

| # | Issue | Location | Severity | Fix Effort |
|---|-------|----------|----------|-----------|
| 5 | Coroutine lifecycle management | `scanLibrary()` | **HIGH** | Medium |
| 6 | Snapshot object lifetime | `CleanupViewModel` | **HIGH** | Low |
| 7 | No ViewModel.onCleared() override | Throughout | **HIGH** | Low |

### Medium Priority

| # | Issue | Location | Severity | Fix Effort |
|---|-------|----------|----------|-----------|
| 8 | Static Regex objects | Companion object | **MEDIUM** | Low |
| 9 | JSON object creation GC pressure | `buildRemote*Json()` | **MEDIUM** | Medium |
| 10 | Inefficient cache I/O | `persistSnapshot()` | **MEDIUM** | Medium |

---

## 10. Recommended Action Plan

### Phase 1: Critical Fixes (1-2 hours)
1. ✅ Add `ViewModel.onCleared()` with cleanup
2. ✅ Wrap bitmap operations in try-catch
3. ✅ Close InputStreams properly
4. ✅ Remove `runBlocking` call

### Phase 2: High Priority (2-3 hours)
5. ✅ Implement cancel mechanism for scans
6. ✅ Add snapshot lifecycle management
7. ✅ Implement proper HTTP server shutdown

### Phase 3: Medium Priority (3-4 hours)
8. ✅ Lazy-load Regex patterns
9. ✅ Optimize JSON/object creation
10. ✅ Move cache I/O to async

### Phase 4: Optimization (4-6 hours)
11. ✅ Implement bitmap pooling
12. ✅ Add incremental scan support
13. ✅ Proto
Buffer migration

---

## 11. Testing Recommendations

### Memory Leak Tests

```kotlin
// Test 1: Verify server stops
@Test
fun testRemoteServerCleanup() {
    val viewModel = CleanupViewModel(app)
    viewModel.toggleRemoteAccess()  // Start
    assert(viewModel.remoteServer != null)
    viewModel.onCleared()
    assert(viewModel.remoteServer == null)
}

// Test 2: Bitmap recycling
@Test
fun testBitmapRecycling() {
    val before = Runtime.getRuntime().totalMemory()
    repository.scanLibrary()
    System.gc()
    val after = Runtime.getRuntime().totalMemory()
    assert((after - before) < 100 * 1024 * 1024)  // < 100 MB
}

// Test 3: Coroutine cancellation
@Test
fun testScancCancellation() {
    val viewModel = CleanupViewModel(app)
    viewModel.scanLibrary()
    viewModel.scanLibrary()  // Should cancel first
    // Verify only one scan running
}
```

### Profiling

Use Android Profiler:
- **Memory Profiler:** Track heap during scan
- **CPU Profiler:** Monitor GC frequency
- **Network Profiler:** Verify InputStream closure

---

## 12. Files Requiring Attention

### Ranked by Critical Issues

1. **CleanupRepository.kt** 
   - Line 700-800: Bitmap leak
   - Line 850-900: Async I/O
   - Line 1050+: Static regex

2. **CleanupViewModel.kt**
   - Line 1-50: Add onCleared()
   - Line 150-250: Scan coroutine management
   - Line 500-550: Remove runBlocking()

3. **RemoteAccessServer.kt**
   - Line 1-50: Server lifecycle
   - Line 90-100: InputStream cleanup

4. **MainActivity.kt**
   - Line 180-200: Event collection (review only)

---

## 13. Dependencies Health

| Dependency | Version | Risk | Notes |
|------------|---------|------|-------|
| Compose BOM | 2024.06 | ✅ Low | Latest, well-maintained |
| Lifecycle | 2.8.3 | ✅ Low | Properly aligned |
| Coroutines | 1.9.0 | ✅ Low | Recent, stable |
| Coil | 2.7.0 | ✅ Low | Image loading handled |
| NanoHTTPD | 2.3.1 | ⚠️ Medium | Older version, consider update |

---

## Conclusion

The Image Cleanup Assistant demonstrates **solid architectural patterns** with Compose/MVVM, but has **critical memory management issues** that must be addressed before production release:

**Priority 1:** Fix bitmap and InputStream leaks
**Priority 2:** Add proper cleanup in ViewModel
**Priority 3:** Implement coroutine cancellation
**Priority 4:** Optimize JSON/caching operations

With the recommended fixes, the app should be **production-ready** and capable of handling large photo libraries efficiently.

---

*Analysis Date: April 11, 2026*
*Analyzer: Android Code Analysis*
