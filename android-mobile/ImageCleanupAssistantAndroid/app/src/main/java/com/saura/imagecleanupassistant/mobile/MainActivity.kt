package com.saura.imagecleanupassistant.mobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import java.text.DateFormat

// ── Color System (Light Premium Theme) ──────────────────────────────────────────

private val BgLight = Color(0xFFF5F7FA)
private val CardWhite = Color(0xFFFFFFFF)
private val CardElevated = Color(0xFFF0F2F5)
private val PrimaryGreen = Color(0xFF00C853)
private val PrimaryGreenDark = Color(0xFF00A844)
private val AccentBlue = Color(0xFF2979FF)
private val AccentRed = Color(0xFFFF5252)
private val AccentOrange = Color(0xFFFF9100)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentTeal = Color(0xFF00BFA5)
private val AccentPink = Color(0xFFFF4081)
private val TextDark = Color(0xFF1A1D26)
private val TextMedium = Color(0xFF5A6070)
private val TextLight = Color(0xFF9CA3B0)
private val DividerColor = Color(0xFFE8ECF0)
private val ShadowColor = Color(0x0A000000)

private val AppColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = Color.White,
    secondary = AccentBlue,
    tertiary = AccentPurple,
    background = BgLight,
    surface = CardWhite,
    surfaceVariant = CardElevated,
    onSurface = TextDark,
    onSurfaceVariant = TextMedium,
    error = AccentRed,
    onBackground = TextDark
)

// ── Activity ────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CleanupViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                MobileCleanupApp(viewModel = viewModel)
            }
        }
    }
}

// ── Root ─────────────────────────────────────────────────────────────────────────

@Composable
private fun MobileCleanupApp(viewModel: CleanupViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteCommand by remember { mutableStateOf<DeleteCommand?>(null) }
    var fullscreenPreview by remember { mutableStateOf<MediaImage?>(null) }

    val permission = remember {
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermission(granted)
        if (granted) {
            viewModel.restoreCachedStateIfAvailable()
            if (viewModel.state.value.imageCount == 0) viewModel.scanLibrary()
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDeleteRequestCompleted(
            command = pendingDeleteCommand,
            wasApproved = result.resultCode == Activity.RESULT_OK
        )
        pendingDeleteCommand = null
    }

    LaunchedEffect(Unit) {
        val has = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.refreshPermission(has)
        if (has) viewModel.restoreCachedStateIfAvailable()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.LaunchDeleteRequest -> {
                    pendingDeleteCommand = event.command
                    val request = android.provider.MediaStore.createDeleteRequest(
                        context.contentResolver, event.command.uris
                    )
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        containerColor = BgLight,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when (state.screen) {
            AppScreen.OVERVIEW -> OverviewScreen(
                state = state,
                modifier = Modifier.padding(innerPadding),
                onGrantPermission = { permissionLauncher.launch(permission) },
                onScan = viewModel::scanLibrary,
                onSelectSource = viewModel::selectSource,
                onOpenQueue = viewModel::openQueue,
                onSelectFolder = viewModel::selectScanFolder,
                onLoadFolders = viewModel::loadAvailableFolders
            )
            AppScreen.REVIEW -> ReviewScreen(
                state = state,
                modifier = Modifier.padding(innerPadding),
                onBack = viewModel::returnToOverview,
                onRescan = viewModel::scanLibrary,
                onSelectSource = viewModel::selectSource,
                onSelectEntry = viewModel::selectEntry,
                onStartSelection = { state.activeReviewKey?.let(viewModel::startEntrySelection) },
                onToggleEntrySelection = viewModel::toggleEntrySelection,
                onClearSelection = viewModel::clearSelectedEntries,
                onSelectAll = viewModel::selectAllEntries,
                onDeleteSelected = viewModel::requestDeleteSelectedEntries,
                onKeepSelected = viewModel::keepSelectedEntries,
                onPreview = { fullscreenPreview = it },
                onKeep = viewModel::keepCurrentEntry,
                onNext = { viewModel.moveSelection(1) },
                onPrevious = { viewModel.moveSelection(-1) },
                onDeleteSingle = viewModel::requestDeleteCurrentSingle,
                onDeleteLeft = viewModel::requestDeleteLeft,
                onDeleteRight = viewModel::requestDeleteRight,
                onDeleteBoth = viewModel::requestDeleteBoth
            )
        }
    }

    fullscreenPreview?.let { image ->
        FullscreenImageDialog(image = image, onDismiss = { fullscreenPreview = null })
    }

    BackHandler(enabled = fullscreenPreview != null) { fullscreenPreview = null }
    BackHandler(enabled = fullscreenPreview == null && state.screen == AppScreen.REVIEW && state.isSelectionMode) { viewModel.clearSelectedEntries() }
    BackHandler(enabled = fullscreenPreview == null && state.screen == AppScreen.REVIEW && !state.isSelectionMode) { viewModel.returnToOverview() }
}

// ── Overview Screen ─────────────────────────────────────────────────────────────

@Composable
private fun OverviewScreen(
    state: UiState, modifier: Modifier = Modifier,
    onGrantPermission: () -> Unit, onScan: () -> Unit,
    onSelectSource: (String) -> Unit, onOpenQueue: (CleanupQueueId) -> Unit,
    onSelectFolder: (String?) -> Unit, onLoadFolders: () -> Unit
) {
    val totalQueueItems = state.queues.sumOf { it.count }

    LaunchedEffect(state.hasPermission) {
        if (state.hasPermission) onLoadFolders()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Clean up",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        }

        // Hero summary card
        HeroSummaryCard(
            state = state,
            totalQueueItems = totalQueueItems,
            onGrantPermission = onGrantPermission,
            onScan = onScan
        )

        Spacer(Modifier.height(16.dp))

        // Scanning progress
        AnimatedVisibility(visible = state.isScanning) {
            ScanProgressCard(statusText = state.statusText)
        }

        // Folder picker
        if (state.hasPermission && state.availableFolders.isNotEmpty()) {
            SectionTitle("Scan folder")
            FolderChipRow(
                folders = state.availableFolders,
                selectedFolder = state.selectedScanFolder,
                onSelectFolder = onSelectFolder
            )
            Spacer(Modifier.height(8.dp))
        }

        // Source filter
        if (state.availableSources.size > 1) {
            SectionTitle("Source")
            SourceChipRow(
                sources = state.availableSources,
                selectedSourceId = state.selectedSourceId,
                onSelect = onSelectSource
            )
            Spacer(Modifier.height(8.dp))
        }

        // Cleanup queues
        if (state.imageCount > 0) {
            SectionTitle("Photo cleanup")
            CleanupQueueList(queues = state.queues, onOpenQueue = onOpenQueue)

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Hero Summary Card ───────────────────────────────────────────────────────────

@Composable
private fun HeroSummaryCard(
    state: UiState, totalQueueItems: Int,
    onGrantPermission: () -> Unit, onScan: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.imageCount > 0) {
                Text(
                    text = "$totalQueueItems",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    fontSize = 56.sp
                )
                Text(
                    text = "items to review",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMedium
                )

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStat(label = "Photos", value = "${state.imageCount}")
                    MiniStat(label = "Queues", value = "${state.queues.count { it.count > 0 }}")
                    MiniStat(
                        label = "Last scan",
                        value = state.lastScanMillis?.let { formatRelativeTime(it) } ?: "Never"
                    )
                }
            } else {
                Text(
                    text = if (state.hasPermission) "Ready to scan" else "Photo Cleanup",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
                Text(
                    text = if (state.hasPermission) "Scan your library to find cleanup opportunities."
                    else "Grant access to find duplicates, blurry photos, forwards, and more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMedium,
                    textAlign = TextAlign.Center
                )
            }

            if (!state.hasPermission) {
                GreenButton(
                    text = "Grant Photo Access",
                    onClick = onGrantPermission,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                GreenButton(
                    text = if (state.isScanning) "Scanning..." else if (state.imageCount == 0) "Scan Library" else "Rescan",
                    onClick = onScan,
                    enabled = !state.isScanning,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextDark
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextLight
        )
    }
}

// ── Scan Progress ───────────────────────────────────────────────────────────────

@Composable
private fun ScanProgressCard(statusText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = PrimaryGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = TextMedium)
            }
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = PrimaryGreen,
                trackColor = DividerColor
            )
        }
    }
}

// ── Cleanup Queue List (like the cleaner app) ───────────────────────────────────

@Composable
private fun CleanupQueueList(queues: List<QueueDefinition>, onOpenQueue: (CleanupQueueId) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            queues.forEachIndexed { index, queue ->
                QueueListItem(
                    definition = queue,
                    onClick = { onOpenQueue(queue.id) }
                )
                if (index < queues.lastIndex) {
                    HorizontalDivider(
                        color = DividerColor,
                        modifier = Modifier.padding(start = 68.dp, end = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueListItem(definition: QueueDefinition, onClick: () -> Unit) {
    val (icon, iconBg) = queueVisuals(definition.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Colored icon circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }

        Spacer(Modifier.width(14.dp))

        // Title
        Column(modifier = Modifier.weight(1f)) {
            Text(
                definition.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextDark
            )
            if (definition.count == 0) {
                Text(
                    "Clean",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrimaryGreen
                )
            }
        }

        // Count
        if (definition.count > 0) {
            Text(
                "${definition.count}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextMedium
            )
            Spacer(Modifier.width(4.dp))
        }

        Icon(
            Icons.Rounded.ChevronRight,
            null,
            tint = TextLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

// ── Section Title ───────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = TextLight,
        letterSpacing = 0.5.sp
    )
}

// ── Folder Picker ───────────────────────────────────────────────────────────────

@Composable
private fun FolderChipRow(folders: List<FolderOption>, selectedFolder: String?, onSelectFolder: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        item(key = ALL_FOLDERS_ID) {
            FilterChip(
                text = "All folders",
                selected = selectedFolder == null,
                onClick = { onSelectFolder(null) }
            )
        }
        items(items = folders, key = { it.folder }) { folder ->
            FilterChip(
                text = "${folder.folder} (${folder.count})",
                selected = folder.folder == selectedFolder,
                onClick = { onSelectFolder(folder.folder) }
            )
        }
    }
}

@Composable
private fun SourceChipRow(sources: List<SourceOption>, selectedSourceId: String, onSelect: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(items = sources, key = { it.id }) { source ->
            FilterChip(
                text = "${source.title} (${source.count})",
                selected = source.id == selectedSourceId,
                onClick = { onSelect(source.id) }
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) PrimaryGreen else CardElevated, label = "chipBg")
    val fg by animateColorAsState(if (selected) Color.White else TextMedium, label = "chipFg")

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Review Screen ───────────────────────────────────────────────────────────────

@Composable
private fun ReviewScreen(
    state: UiState, modifier: Modifier = Modifier,
    onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") onRescan: () -> Unit,
    onSelectSource: (String) -> Unit, onSelectEntry: (String) -> Unit,
    onStartSelection: () -> Unit, onToggleEntrySelection: (String) -> Unit,
    onClearSelection: () -> Unit, onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit, onKeepSelected: () -> Unit,
    onPreview: (MediaImage) -> Unit, onKeep: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit,
    onDeleteSingle: () -> Unit,
    onDeleteLeft: () -> Unit, onDeleteRight: () -> Unit, onDeleteBoth: () -> Unit
) {
    val queueDef = state.queues.firstOrNull { it.id == state.selectedQueueId }
        ?: defaultQueueDefinitions().first { it.id == state.selectedQueueId }
    val position = if (state.entries.isEmpty()) 0
    else (state.entries.indexOfFirst { it.key == state.activeReviewKey }.takeIf { it >= 0 } ?: 0) + 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        ReviewTopBar(
            queueTitle = queueDef.title,
            positionText = "$position / ${state.entries.size}",
            onBack = onBack,
            selectionMode = state.isSelectionMode,
            selectedCount = state.selectedEntryKeys.size,
            onClearSelection = onClearSelection
        )

        // Source filter
        if (state.availableSources.size > 1) {
            SourceChipRow(
                sources = state.availableSources,
                selectedSourceId = state.selectedSourceId,
                onSelect = onSelectSource
            )
            Spacer(Modifier.height(8.dp))
        }

        // Bulk actions
        if (state.isSelectionMode) {
            BulkActionBar(
                selectedCount = state.selectedEntryKeys.size,
                pairQueue = state.selectedQueueId.isPairQueue(),
                onSelectAll = onSelectAll,
                onDeleteSelected = onDeleteSelected,
                onKeepSelected = onKeepSelected,
                onClearSelection = onClearSelection
            )
        }

        if (state.entries.isEmpty()) {
            EmptyStateCard(title = queueDef.title, emptyText = queueDef.emptyText)
            return
        }

        // Swipeable pager for entry review
        @OptIn(ExperimentalFoundationApi::class)
        run {
            val pagerState = rememberPagerState(pageCount = { state.entries.size })

            // Sync external selection -> pager (e.g. thumbnail tap)
            LaunchedEffect(state.activeReviewKey, state.entries) {
                val targetIndex = state.entries.indexOfFirst { it.key == state.activeReviewKey }
                if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
                    pagerState.animateScrollToPage(targetIndex)
                }
            }

            // Sync pager swipe -> external state
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    val entry = state.entries.getOrNull(page)
                    if (entry != null && entry.key != state.activeReviewKey) {
                        onSelectEntry(entry.key)
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                beyondViewportPageCount = 1
            ) { page ->
                val entry = state.entries.getOrNull(page) ?: return@HorizontalPager
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (entry) {
                        is CleanupEntry.PairEntry -> PairEntryReview(
                            entry = entry,
                            onPreview = onPreview,
                            onDeleteLeft = onDeleteLeft, onDeleteRight = onDeleteRight,
                            onDeleteBoth = onDeleteBoth, onKeep = onKeep
                        )
                        is CleanupEntry.SingleEntry -> SingleEntryReview(
                            entry = entry,
                            onPreview = onPreview,
                            onDelete = onDeleteSingle, onKeep = onKeep
                        )
                    }
                }
            }
        }

        ThumbnailRail(
            entries = state.entries,
            selectedKey = state.activeReviewKey,
            selectedKeys = state.selectedEntryKeys,
            selectionMode = state.isSelectionMode,
            onSelectEntry = onSelectEntry,
            onToggleSelection = onToggleEntrySelection,
            onLongPressEntry = { entryKey ->
                if (state.isSelectionMode) onToggleEntrySelection(entryKey)
                else { onStartSelection(); onToggleEntrySelection(entryKey) }
            }
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReviewTopBar(
    queueTitle: String, positionText: String,
    onBack: () -> Unit,
    selectionMode: Boolean, selectedCount: Int,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = TextDark)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                queueTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Text(
                if (selectionMode) "$selectedCount selected" else positionText,
                style = MaterialTheme.typography.bodySmall,
                color = TextMedium
            )
        }
        if (selectionMode) {
            FilledTonalButton(
                onClick = onClearSelection,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardElevated)
            ) {
                Text("Clear", color = TextMedium)
            }
        }
    }
}

@Composable
private fun BulkActionBar(
    selectedCount: Int, pairQueue: Boolean,
    onSelectAll: () -> Unit, onDeleteSelected: () -> Unit,
    onKeepSelected: () -> Unit, onClearSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (pairQueue) "Batch delete removes the suggested photo from each pair."
                else "$selectedCount photo(s) selected.",
                style = MaterialTheme.typography.bodySmall, color = TextMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("All", Icons.Rounded.SelectAll, onSelectAll, Modifier.weight(1f))
                SmallButton("Clear", Icons.Rounded.Close, onClearSelection, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerButton(if (pairQueue) "Delete Suggested" else "Delete", Icons.Rounded.Delete, onDeleteSelected, Modifier.weight(1f))
                GreenButton("Keep", onKeepSelected, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, emptyText: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = PrimaryGreen, modifier = Modifier.size(56.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = TextDark)
            Text(emptyText, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = TextMedium)
        }
    }
}

// ── Single Entry Review ─────────────────────────────────────────────────────────

@Composable
private fun SingleEntryReview(
    entry: CleanupEntry.SingleEntry,
    onPreview: (MediaImage) -> Unit,
    onDelete: () -> Unit, onKeep: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PreviewTile(image = entry.image, onClick = { onPreview(entry.image) })

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoTag(entry.image.folder)
                InfoTag(entry.image.dimensionsText)
                InfoTag(entry.image.sizeText)
            }

            Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextDark)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerButton("Delete", Icons.Rounded.Delete, onDelete, Modifier.weight(1f))
                GreenButton("Keep", onKeep, Modifier.weight(1f))
            }
        }
    }
}

// ── Pair Entry Review ───────────────────────────────────────────────────────────

@Composable
private fun PairEntryReview(
    entry: CleanupEntry.PairEntry,
    onPreview: (MediaImage) -> Unit,
    onDeleteLeft: () -> Unit, onDeleteRight: () -> Unit,
    onDeleteBoth: () -> Unit, onKeep: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit
) {
    val sugId = entry.pair.suggestedDeleteId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge("${entry.pair.confidence}% match", AccentBlue)
                StatusBadge("Suggested: ${if (sugId == entry.first.id) "left" else "right"}", AccentRed)
            }

            Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextDark)

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 720.dp
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewTile(entry.first, if (entry.first.id == sugId) "Left (delete)" else "Left") { onPreview(entry.first) }
                        PreviewTile(entry.second, if (entry.second.id == sugId) "Right (delete)" else "Right") { onPreview(entry.second) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewTile(entry.first, if (entry.first.id == sugId) "Left (delete)" else "Left", Modifier.weight(1f)) { onPreview(entry.first) }
                        PreviewTile(entry.second, if (entry.second.id == sugId) "Right (delete)" else "Right", Modifier.weight(1f)) { onPreview(entry.second) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerButton("Del Left", Icons.Rounded.Delete, onDeleteLeft, Modifier.weight(1f))
                DangerButton("Del Right", Icons.Rounded.Delete, onDeleteRight, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Delete Both", Icons.Rounded.Delete, onDeleteBoth, Modifier.weight(1f))
                GreenButton("Keep", onKeep, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Previous", Icons.AutoMirrored.Rounded.ArrowBack, onPrevious, Modifier.weight(1f))
                SmallButton("Next", Icons.AutoMirrored.Rounded.ArrowForward, onNext, Modifier.weight(1f))
            }
        }
    }
}

// ── Preview Tile ────────────────────────────────────────────────────────────────

@Composable
private fun PreviewTile(image: MediaImage, badge: String = "Photo", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardElevated)
    ) {
        Box {
            AsyncImage(
                model = image.uri, contentDescription = image.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.12f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
            StatusBadge(
                text = badge,
                color = Color.Black.copy(alpha = 0.55f),
                textColor = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(10.dp)
            ) {
                Text(image.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${image.folder} | ${image.dimensionsText}", color = Color.White.copy(0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

// ── Thumbnail Rail ──────────────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ThumbnailRail(
    entries: List<CleanupEntry>, selectedKey: String?,
    selectedKeys: Set<String>, selectionMode: Boolean,
    onSelectEntry: (String) -> Unit, onToggleSelection: (String) -> Unit,
    onLongPressEntry: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("All items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextDark)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = entries, key = { "${it.queueId.name}-${it.key}" }) { entry ->
                    val isSelected = entry.key == selectedKey
                    val isMarked = entry.key in selectedKeys
                    val (_, accent) = queueVisuals(entry.queueId)
                    val previewImage = when (entry) {
                        is CleanupEntry.PairEntry -> entry.first
                        is CleanupEntry.SingleEntry -> entry.image
                    }
                    val borderColor by animateColorAsState(
                        if (isSelected) accent else if (isMarked) accent.copy(0.4f) else Color.Transparent,
                        label = "border"
                    )
                    Card(
                        modifier = Modifier
                            .width(100.dp)
                            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { if (selectionMode) onToggleSelection(entry.key) else onSelectEntry(entry.key) },
                                onLongClick = { onLongPressEntry(entry.key) }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardElevated)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AsyncImage(
                                model = previewImage.uri, contentDescription = previewImage.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            if (isMarked) {
                                StatusBadge("Selected", accent.copy(0.15f), accent)
                            }
                            Text(
                                previewImage.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextLight,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Fullscreen Dialog ───────────────────────────────────────────────────────────

@Composable
private fun FullscreenImageDialog(image: MediaImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF060810)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(image.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${image.folder} | ${image.dimensionsText} | ${image.sizeText}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.7f))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, "Close", tint = Color.White)
                    }
                }
                AsyncImage(
                    model = image.uri, contentDescription = image.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

// ── Shared Components ───────────────────────────────────────────────────────────

@Composable
private fun GreenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DangerButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.1f), contentColor = AccentRed),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SmallButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardElevated, contentColor = TextMedium),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusBadge(text: String, color: Color, textColor: Color = Color.White, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = color) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoTag(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = CardElevated) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = TextLight, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun queueVisuals(queueId: CleanupQueueId): Pair<ImageVector, Color> = when (queueId) {
    CleanupQueueId.EXACT -> Icons.Rounded.ContentCopy to Color(0xFFFF5252)
    CleanupQueueId.SIMILAR -> Icons.Rounded.CameraAlt to Color(0xFF2979FF)
    CleanupQueueId.BLURRY -> Icons.Rounded.BlurOn to Color(0xFF78909C)
    CleanupQueueId.FORWARD -> Icons.AutoMirrored.Rounded.Forward to Color(0xFFFF9100)
    CleanupQueueId.SCREENSHOT -> Icons.Rounded.Screenshot to Color(0xFF00BFA5)
    CleanupQueueId.TEXT_HEAVY -> Icons.Rounded.TextFields to Color(0xFF7C4DFF)
}

private fun formatRelativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(millis)
    }
}
