# Android Code Structure - Quick Reference Guide

## File Organization

```
📦 Image Cleanup Assistant Android
├── 📄 MainActivity.kt (2000 lines)
│   ├── Material 3 Theme Definition
│   ├── MobileCleanupApp (Root Composable)
│   ├── OverviewScreen (Dashboard UI)
│   ├── ReviewScreen (Detail UI)
│   └── 20+ Helper Composables
│
├── 📄 CleanupViewModel.kt (800 lines)
│   ├── State Management (UiState Flow)
│   ├── Event Handling (UiEvent Flow)
│   ├── Scan Operations
│   ├── Remote Access Control
│   └── Cache Persistence
│
├── 📄 CleanupRepository.kt (1200 lines)
│   ├── Data Operations
│   ├── Image Analysis Engine
│   ├── Bitmap Processing
│   ├── Pair Matching Algorithm
│   ├── Cache I/O
│   └── MediaStore Queries
│
├── 📄 RemoteAccessServer.kt (800 lines)
│   ├── NanoHTTPD Server
│   ├── REST API Endpoints
│   ├── Dashboard HTML
│   └── JSON Serialization
│
└── 📄 CleanupModels.kt (200+ lines)
    ├── Data Classes
    ├── Enums (QueueId, AppScreen)
    └── Constants (ALL_SOURCE_ID, etc.)
```

---

## Data Flow Diagram

```
┌─────────────────────┐
│  User Interaction   │
│  (UI Tap/Gesture)   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────────────┐
│  MainActivity (Compose)      │
│  - Event Handler Callback    │
└──────────┬──────────────────┘
           │
           ▼
┌─────────────────────────────┐
│  CleanupViewModel            │
│  - Process Event             │
│  - Update State (Flow)       │
└──────────┬──────────────────┘
           │
           ├──────────────────────────┐
           │                          │
           ▼                          ▼
┌──────────────────────┐  ┌──────────────────┐
│ CleanupRepository    │  │ RemoteAccessServer │
│ - Data Operations    │  │ - HTTP Responses │
│ - Image Analysis     │  │ - JSON API       │
└──────────┬───────────┘  └──────────────────┘
           │
           ├─────────────┬──────────┬──────────┐
           │             │          │          │
           ▼             ▼          ▼          ▼
      [MediaStore]  [JSON Cache] [Bitmaps] [File I/O]
           │
           ▼
    ┌──────────────────┐
    │ State Update     │
    │ MutableStateFlow │
    └────────┬─────────┘
             │
             ▼
    ┌──────────────────┐
    │  Compose UI      │
    │  Recomposition   │
    └──────────────────┘
```

---

## State Flow Architecture

```
CleanupViewModel
├── _state: MutableStateFlow<UiState>
│   └── public: state.asStateFlow() [Read-only]
│
├── _events: MutableSharedFlow<UiEvent>
│   └── public: events.asSharedFlow() [Read-only]
│
└── snapshot: CleanupSnapshot (in-memory cache)
    ├── imagesById: Map<Long, MediaImage>
    ├── exactPairs: List<PairMatch>
    ├── similarPairs: List<PairMatch>
    ├── blurryIds: List<Long>
    ├── forwardIds: List<Long>
    ├── screenshotIds: List<Long>
    └── textHeavyIds: List<Long>
```

---

## UiState Composition

```kotlin
data class UiState(
    // Permission & Scanning
    val hasPermission: Boolean = false,
    val isScanning: Boolean = false,
    
    // Status/UI Text
    val statusText: String = "...",
    val summaryText: String = "...",
    
    // Data Collections
    val queues: List<QueueDefinition> = [],
    val entries: List<CleanupEntry> = [],
    val availableSources: List<SourceOption> = [],
    val availableFolders: List<FolderOption> = [],
    
    // Selection State
    val selectedSourceId: String = ALL_SOURCE_ID,
    val selectedQueueId: CleanupQueueId = CleanupQueueId.EXACT,
    val activeReviewKey: String? = null,
    val dismissedEntryKeys: Set<String> = emptySet(),
    val selectedEntryKeys: Set<String> = emptySet(),
    
    // Navigation
    val screen: AppScreen = AppScreen.OVERVIEW,
    
    // Metadata
    val imageCount: Int = 0,
    val lastScanMillis: Long? = null,
    val selectedScanFolder: String? = null,
    
    // Remote Access
    val remoteAccess: RemoteAccessState = RemoteAccessState()
)
```

---

## Image Analysis Pipeline

```
┌──────────────┐
│ Raw Image    │
│ (Media Uri)  │
└──────┬───────┘
       │
       ▼
┌────────────────────────────┐
│ 1. Query MediaStore        │
│    - ID, Name, Size, etc.  │
└──────┬─────────────────────┘
       │
       ▼
┌────────────────────────────┐
│ 2. Decode Bitmap           │
│    - Load into memory      │
│    - Low-RAM optimization  │
└──────┬─────────────────────┘
       │
       ▼
┌────────────────────────────────┐
│ 3. Create Scaled Samples       │
│    - 8x8 (brightness hash)     │
│    - 9x8 (gradient hash)       │
│    - 24x24 (color analysis)    │
└──────┬─────────────────────────┘
       │
       ▼
┌────────────────────────────────┐
│ 4. Calculate Metrics           │
│    - Perceptual Hashes         │
│    - Quality Scores            │
│    - Color/Tone Analysis       │
│    - Sharpness Estimation      │
└──────┬─────────────────────────┘
       │
       ▼
┌────────────────────────────────┐
│ 5. Classify Image              │
│    - Blurry Detection          │
│    - Screenshot Detection      │
│    - Document Detection        │
│    - Messaging Clutter         │
└──────┬─────────────────────────┘
       │
       ▼
┌────────────────────────────────┐
│ 6. Create MediaImage Object    │
│    - Store all metrics         │
│    - Cache to disk             │
└──────┬─────────────────────────┘
       │
       ▼
  [Database Cache]
```

---

## Pair Matching Algorithm

```
For each image in library:
    │
    ├─ With Content Hash (Exact Match)
    │  └─ Group by hash
    │     ├─ Multiple images = Exact duplicates
    │     └─ Sort by quality, keep best
    │        
    └─ Without exact match (Similar Match)
       ├─ Filter by aspect ratio buckets
       ├─ For each pair:
       │  ├─ Check visual metrics (hash distance)
       │  ├─ Check color metrics (brightness, saturation)
       │  ├─ Check name affinity
       │  ├─ Check modified time delta
       │  └─ If all pass thresholds:
       │     ├─ Calculate confidence score
       │     ├─ Determine suggested delete
       │     └─ Add to similar pairs
       │
       └─ Quality Score:
          = (baseline - hash_distance_penalty)
          + color_metric_bonus
          + name_affinity_bonus
          + time_proximity_bonus
          - (if threshold not met, score < 205 = rejected)
```

---

## Event Types

```kotlin
sealed interface UiEvent {
    data class LaunchDeleteRequest(val command: DeleteCommand) : UiEvent
    data class ShowMessage(val message: String) : UiEvent
}
```

## Queue Types

```kotlin
enum class CleanupQueueId {
    EXACT,         // 100% duplicates (same content hash)
    SIMILAR,       // Visually similar images
    BLURRY,        // Low sharpness/soft focus
    FORWARD,       // Messaging clutter (memes, quotes, stickers)
    SCREENSHOT,    // Device screenshots
    TEXT_HEAVY     // Documents, receipts, forms, tickets
}
```

---

## Memory Layout Estimates

### Typical Scan (5000 images)

```
Data Structure               Size Est.     Notes
─────────────────────────────────────────────────────
MediaImage (single)          ~10-15 KB     + 64 char hash strings
ImageMetrics (single)        ~2-3 KB       + int arrays
CleanupSnapshot Header       ~50 KB        Fixed overhead
imagesById Map               ~50-75 MB     5000 images × 12 KB avg
exactPairs List              ~500 KB       Typically 10-50 pairs
similarPairs List            ~2-5 MB       Typically 100-500 pairs
UI State Object              ~100 KB       Current UI state
Session Cache (JSON)         ~5-10 MB      Disk cache

TOTAL HEAP:                  ~60-100 MB  
TEMPORARY (during scan):     +50-100 MB    Bitmaps, intermediate objects
PEAK MEMORY:                 ~150-200 MB
```

### Red Flags for GC Pressure

```
if (memory_increase_per_image > 50 KB):
    "Potential leak - should be ~10-15 KB per image"

if (heap_never_returns_to_baseline):
    "Possible memory leak or large snapshot retention"

if (GC_frequency > 1_per_second):
    "High allocation rate - optimize JSON/object creation"
```

---

## Critical Code Sections to Review

### Section 1: Scan Entry Point
**File:** `CleanupViewModel.kt` line 150-250
**What:** Core scan initiation & coroutine management
**Risk:** Coroutine lifetime, snapshot mutation
**Action:** ✅ Add cancellation support

### Section 2: Bitmap Analysis
**File:** `CleanupRepository.kt` line 700-850
**What:** Bitmap loading, scaling, metrics calculation
**Risk:** Memory leaks, exception handling
**Action:** ✅ Fix cleanup, add error handling

### Section 3: Cache Persistence
**File:** `CleanupRepository.kt` line 550-650
**What:** JSON serialization to disk
**Risk:** Synchronous I/O, JSON overhead
**Action:** ✅ Make async, optimize serialization

### Section 4: Remote Server
**File:** `RemoteAccessServer.kt` line 1-100
**What:** HTTP server initialization & request handling
**Risk:** Socket leaks, InputStream not closed
**Action:** ✅ Add lifecycle management, wrap streams

---

## Performance Benchmarks

### Expected Scan Times (Reference)

```
Device              Library Size    Time        Memory Peak
────────────────────────────────────────────────────────────
Pixel 4 (4GB RAM)   1,000 images    ~30 sec     ~120 MB
Pixel 5 (8GB RAM)   5,000 images    ~2 min      ~150 MB
Pixel 6 (12GB RAM) 10,000 images    ~4 min      ~180 MB

If scan time > baseline by 50%:
    → Check for GC pauses (memory pressure)
    
If memory > 250 MB:
    → Investigate for leaks
```

---

## Debugging Commands

### Logcat Filters

```bash
# Monitor memory-related logs
adb logcat | grep -E "Memory|Bitmap|Stream|Cleanup"

# Watch for exceptions
adb logcat | grep -E "Exception|Error|Crash"

# Track remote server events
adb logcat | grep "RemoteAccess"

# Monitor scan progress
adb logcat | grep "BitmapAnalysis\|Scan:"
```

### ADB Commands

```bash
# Check heap size
adb shell dumpsys meminfo com.saura.imagecleanupassistant.mobile

# Trigger GC and get memory info
adb shell am dumpheap <PID> /data/local/tmp/heap.bin

# Monitor real-time memory
adb shell watch -n 1 'dumpsys meminfo | grep TOTAL'
```

---

## Dependency Versions

```gradle
androidx.compose:compose-bom:2024.06.00
├── androidx.compose.ui:ui
├── androidx.compose.material3:material3
└── androidx.compose.foundation:foundation

androidx.lifecycle:lifecycle-runtime-ktx:2.8.3
├── androidx.lifecycle:lifecycle-viewmodel-ktx
└── androidx.lifecycle:lifecycle-runtime-compose

kotlinx-coroutines-android:1.9.0
├── kotlinx-coroutines-core
└── kotlinx-coroutines-android-native

io.coil-kt:coil-compose:2.7.0
├── Image loading & caching
└── Bitmap fallbacks

org.nanohttpd:nanohttpd:2.3.1
└── Simple HTTP server

androidx.core:core-ktx:1.13.1
├── Context utilities
└── Android 13+ APIs
```

---

## Key Constants

```kotlin
// Queue Types
const val ALL_SOURCE_ID = "__all__"
const val ALL_FOLDERS_ID = "__all_folders__"
const val DEFAULT_REMOTE_PORT = 9864

// File Paths
const val CACHE_FILE_NAME = "image_cleanup_cache.json"
const val SESSION_PREFS = "image_cleanup_session"

// Thresholds for Classification
const val BLUR_THRESHOLD = 10.0  // Sharpness estimate
const val SCREENSHOT_THRESHOLD = 5  // Score
const val DOCUMENT_THRESHOLD = 5   // Score
const val CLUTTER_THRESHOLD = 5    // Score

// Hash Matching Thresholds
const val AVERAGE_HASH_MAX_DISTANCE = 3
const val GRADIENT_HASH_MAX_DISTANCE = 6
const val TONE_MAX_DIFFERENCE = 10.0

// Pair Matching Score
const val SIMILAR_PAIR_MIN_SCORE = 205.0
const val CONFIDENCE_MIN = 72
const val CONFIDENCE_MAX = 99
```

---

## Gradle Build Configuration

```kotlin
android {
    namespace = "com.saura.imagecleanupassistant.mobile"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.saura.imagecleanupassistant.mobile"
        minSdk = 30  // Android 11+
        targetSdk = 34
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures {
        compose = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}
```

---

## Next Steps for Developers

### Before Merging to Main

- [ ] Run all unit tests
- [ ] Run memory leak tests
- [ ] Profile scan operation (Memory Profiler)
- [ ] Check for ANR during scan
- [ ] Test configuration changes (device rotation)
- [ ] Verify remote server stops on app close
- [ ] Check for logcat errors

### Before Release

- [ ] Run LeakCanary on production build
- [ ] Test on low-memory devices
- [ ] Verify performance on 10,000+ image library
- [ ] Set up monitoring/crash reporting
- [ ] Document known limitations
- [ ] Create user documentation

---

**Last Updated:** April 11, 2026
**Codebase Version:** 1.0 (Alpha)
**Status:** ⚠️ Ready for fixes, not production-ready

