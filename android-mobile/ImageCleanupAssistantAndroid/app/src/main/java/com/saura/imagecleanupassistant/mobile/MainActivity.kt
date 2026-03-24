package com.saura.imagecleanupassistant.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi

private val CleanupColors = lightColorScheme(
    primary = Color(0xFFFF5A5F),
    onPrimary = Color.White,
    secondary = Color(0xFF3B82F6),
    onSecondary = Color.White,
    tertiary = Color(0xFF14B8A6),
    background = Color(0xFFF6F7F3),
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F4EF),
    onSurface = Color(0xFF101828),
    onSurfaceVariant = Color(0xFF667085)
)

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<CleanupViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CleanupTheme {
                MobileCleanupApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun CleanupTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CleanupColors,
        content = content
    )
}

@Composable
private fun MobileCleanupApp(viewModel: CleanupViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteCommand by remember { mutableStateOf<DeleteCommand?>(null) }
    var fullscreenPreview by remember { mutableStateOf<MediaImage?>(null) }

    val permission = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermission(granted)
        if (granted) {
            viewModel.restoreCachedStateIfAvailable()
            if (viewModel.state.value.imageCount == 0) {
                viewModel.scanLibrary()
            }
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

    val aiModelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importAiModel)
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.refreshPermission(hasPermission)
        if (hasPermission) {
            viewModel.restoreCachedStateIfAvailable()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.LaunchDeleteRequest -> {
                    pendingDeleteCommand = event.command
                    val request = android.provider.MediaStore.createDeleteRequest(
                        context.contentResolver,
                        event.command.uris
                    )
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }

                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CleanupColors.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFBFBF8),
                            Color(0xFFF4F6F2)
                        )
                    )
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
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
                        onImportAiModel = { aiModelPickerLauncher.launch(arrayOf("*/*")) },
                        onClearAiModel = viewModel::clearAiModel,
                        onOpenAiModelPage = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview")
                            )
                            context.startActivity(intent)
                        }
                    )

                    AppScreen.REVIEW -> ReviewScreen(
                        state = state,
                        modifier = Modifier.padding(innerPadding),
                        onBack = viewModel::returnToOverview,
                        onRescan = viewModel::scanLibrary,
                        onSelectSource = viewModel::selectSource,
                        onSelectEntry = viewModel::selectEntry,
                        onStartSelection = {
                            state.activeReviewKey?.let(viewModel::startEntrySelection)
                        },
                        onToggleEntrySelection = viewModel::toggleEntrySelection,
                        onClearSelection = viewModel::clearSelectedEntries,
                        onSelectAll = viewModel::selectAllEntries,
                        onDeleteSelected = viewModel::requestDeleteSelectedEntries,
                        onKeepSelected = viewModel::keepSelectedEntries,
                        onPreview = { fullscreenPreview = it },
                        onKeep = viewModel::keepCurrentEntry,
                        onNext = { viewModel.moveSelection(1) },
                        onPrevious = { viewModel.moveSelection(-1) },
                        onRunAiReview = viewModel::runAiReviewForCurrentEntry,
                        onDeleteSingle = viewModel::requestDeleteCurrentSingle,
                        onDeleteLeft = viewModel::requestDeleteLeft,
                        onDeleteRight = viewModel::requestDeleteRight,
                        onDeleteBoth = viewModel::requestDeleteBoth
                    )
                }
            }

            fullscreenPreview?.let { image ->
                FullscreenImageDialog(
                    image = image,
                    onDismiss = { fullscreenPreview = null }
                )
            }
        }
    }

    BackHandler(enabled = fullscreenPreview != null) {
        fullscreenPreview = null
    }

    BackHandler(enabled = fullscreenPreview == null && state.screen == AppScreen.REVIEW && state.isSelectionMode) {
        viewModel.clearSelectedEntries()
    }

    BackHandler(enabled = fullscreenPreview == null && state.screen == AppScreen.REVIEW && !state.isSelectionMode) {
        viewModel.returnToOverview()
    }
}

@Composable
private fun OverviewScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    onGrantPermission: () -> Unit,
    onScan: () -> Unit,
    onSelectSource: (String) -> Unit,
    onOpenQueue: (CleanupQueueId) -> Unit,
    onImportAiModel: () -> Unit,
    onClearAiModel: () -> Unit,
    onOpenAiModelPage: () -> Unit
) {
    val lastScanText = remember(state.lastScanMillis) { formatLastScan(state.lastScanMillis) }
    val totalQueueItems = state.queues.sumOf { it.count }
    val queueRows = state.queues.chunked(2)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        OverviewHeader(
            state = state,
            totalQueueItems = totalQueueItems,
            lastScanText = lastScanText,
            onGrantPermission = onGrantPermission,
            onScan = onScan
        )

        if (state.isScanning) {
            ScanProgressCard(statusText = state.statusText)
        }

        SummaryStrip(
            imageCount = state.imageCount,
            totalQueueItems = totalQueueItems,
            lastScanText = lastScanText
        )

        AiSetupCard(
            modelConfig = state.aiModelConfig,
            isImporting = state.isAiModelImporting,
            aiStatusText = state.aiStatusText,
            onImportAiModel = onImportAiModel,
            onClearAiModel = onClearAiModel,
            onOpenAiModelPage = onOpenAiModelPage
        )

        SourceStrip(
            sources = state.availableSources,
            selectedSourceId = state.selectedSourceId,
            onSelectSource = onSelectSource
        )

        Text(
            text = "Queues",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        queueRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { queue ->
                    QueueCard(
                        definition = queue,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenQueue(queue.id) }
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onSelectSource: (String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onStartSelection: () -> Unit,
    onToggleEntrySelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onKeepSelected: () -> Unit,
    onPreview: (MediaImage) -> Unit,
    onKeep: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRunAiReview: () -> Unit,
    onDeleteSingle: () -> Unit,
    onDeleteLeft: () -> Unit,
    onDeleteRight: () -> Unit,
    onDeleteBoth: () -> Unit
) {
    val queueDefinition = state.queues.firstOrNull { it.id == state.selectedQueueId }
        ?: defaultQueueDefinitions().first { it.id == state.selectedQueueId }
    val activeEntry = state.activeReviewEntry
    val position = if (state.entries.isEmpty()) 0 else {
        (state.entries.indexOfFirst { it.key == state.activeReviewKey }.takeIf { it >= 0 } ?: 0) + 1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ReviewHeader(
            queueTitle = queueDefinition.title,
            statusText = state.statusText,
            positionText = "$position/${state.entries.size}",
            onBack = onBack,
            onRescan = onRescan,
            isScanning = state.isScanning,
            selectionMode = state.isSelectionMode,
            selectedCount = state.selectedEntryKeys.size,
            onStartSelection = onStartSelection,
            onClearSelection = onClearSelection
        )

        SourceStrip(
            sources = state.availableSources,
            selectedSourceId = state.selectedSourceId,
            onSelectSource = onSelectSource
        )

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

        if (state.entries.isEmpty() || activeEntry == null) {
            EmptyReviewCard(
                title = queueDefinition.title,
                emptyText = queueDefinition.emptyText
            )
            return
        }

        when (activeEntry) {
            is CleanupEntry.PairEntry -> PairEntryReview(
                entry = activeEntry,
                onPreview = onPreview,
                onDeleteLeft = onDeleteLeft,
                onDeleteRight = onDeleteRight,
                onDeleteBoth = onDeleteBoth,
                onKeep = onKeep,
                onNext = onNext,
                onPrevious = onPrevious
            )

            is CleanupEntry.SingleEntry -> SingleEntryReview(
                entry = activeEntry,
                showAiReview = state.selectedQueueId == CleanupQueueId.FORWARD,
                aiModelConfigured = state.aiModelConfig != null,
                aiReviewRunning = state.isAiReviewRunning,
                aiStatusText = state.aiStatusText,
                aiVerdict = state.activeAiVerdict,
                onPreview = onPreview,
                onRunAiReview = onRunAiReview,
                onDelete = onDeleteSingle,
                onKeep = onKeep,
                onNext = onNext,
                onPrevious = onPrevious
            )
        }

        ThumbnailRail(
            entries = state.entries,
            selectedKey = state.activeReviewKey,
            selectedKeys = state.selectedEntryKeys,
            selectionMode = state.isSelectionMode,
            onSelectEntry = onSelectEntry,
            onToggleSelection = onToggleEntrySelection,
            onLongPressEntry = { entryKey ->
                if (state.isSelectionMode) {
                    onToggleEntrySelection(entryKey)
                } else {
                    onStartSelection()
                    onToggleEntrySelection(entryKey)
                }
            }
        )
    }
}

@Composable
private fun OverviewHeader(
    state: UiState,
    totalQueueItems: Int,
    lastScanText: String?,
    onGrantPermission: () -> Unit,
    onScan: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Your cleanup",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.hasPermission) "Review faster, delete with confidence." else "Grant access to start reviewing your gallery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CountBadge(
                    text = "$totalQueueItems ready",
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.primary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetaPill(text = "${state.imageCount} photos")
                lastScanText?.let { MetaPill(text = it) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.hasPermission) {
                    Button(onClick = onGrantPermission, modifier = Modifier.weight(1f)) {
                        Text("Grant Access")
                    }
                } else {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                        Text(if (state.imageCount == 0) "Scan Library" else "Rescan")
                    }
                }
                FilledTonalButton(
                    onClick = onScan,
                    enabled = state.hasPermission && !state.isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

@Composable
private fun ScanProgressCard(statusText: String) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SummaryStrip(
    imageCount: Int,
    totalQueueItems: Int,
    lastScanText: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryTile(
            modifier = Modifier.weight(1f),
            label = "Library",
            value = imageCount.toString()
        )
        SummaryTile(
            modifier = Modifier.weight(1f),
            label = "To review",
            value = totalQueueItems.toString()
        )
        SummaryTile(
            modifier = Modifier.weight(1f),
            label = "Session",
            value = lastScanText ?: "New"
        )
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AiSetupCard(
    modelConfig: AiModelConfig?,
    isImporting: Boolean,
    aiStatusText: String,
    onImportAiModel: () -> Unit,
    onClearAiModel: () -> Unit,
    onOpenAiModelPage: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI review",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = aiStatusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CountBadge(
                    text = if (modelConfig == null) "Optional" else "Ready",
                    background = if (modelConfig == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                    },
                    content = if (modelConfig == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
            }

            modelConfig?.let { config ->
                MetaPillRow(
                    items = listOf(
                        config.modelName,
                        config.sizeText,
                        config.backendLabel
                    )
                )
            }

            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImportAiModel,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (modelConfig == null) "Import Model" else "Replace Model")
                }
                FilledTonalButton(
                    onClick = onOpenAiModelPage,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Get Gemma")
                }
            }

            if (modelConfig != null) {
                OutlinedButton(
                    onClick = onClearAiModel,
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove AI Model")
                }
            }
        }
    }
}

@Composable
private fun SourceStrip(
    sources: List<SourceOption>,
    selectedSourceId: String,
    onSelectSource: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Source",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items = sources, key = { it.id }) { source ->
                val selected = source.id == selectedSourceId
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.clickable { onSelectSource(source.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = source.title,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        CountBadge(
                            text = source.count.toString(),
                            background = if (selected) {
                                Color.White.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            },
                            content = if (selected) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    definition: QueueDefinition,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = queueAccent(definition.id)
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(modifier = Modifier.weight(1f))
                CountBadge(
                    text = definition.count.toString(),
                    background = accent.copy(alpha = 0.12f),
                    content = accent
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = definition.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (definition.count == 0) "Empty" else "Review",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BulkActionBar(
    selectedCount: Int,
    pairQueue: Boolean,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onKeepSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (pairQueue) {
                    "Batch delete removes the recommended photo from each selected pair."
                } else {
                    "$selectedCount photo(s) selected."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                    Text("Select All")
                }
                OutlinedButton(onClick = onClearSelection, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDeleteSelected, modifier = Modifier.weight(1f)) {
                    Text(if (pairQueue) "Delete Suggested" else "Delete Selected")
                }
                FilledTonalButton(onClick = onKeepSelected, modifier = Modifier.weight(1f)) {
                    Text("Keep Selected")
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(
    queueTitle: String,
    statusText: String,
    positionText: String,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    isScanning: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    onStartSelection: () -> Unit,
    onClearSelection: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selectionMode) {
                    FilledTonalButton(onClick = onClearSelection) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    FilledTonalButton(onClick = onStartSelection) {
                        Text("Select")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                CountBadge(
                    text = if (selectionMode) "$selectedCount selected" else positionText,
                    background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = queueTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onRescan) {
                    Text(if (isScanning) "Scanning..." else "Rescan")
                }
            }

            if (isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyReviewCard(
    title: String,
    emptyText: String
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = emptyText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SingleEntryReview(
    entry: CleanupEntry.SingleEntry,
    showAiReview: Boolean,
    aiModelConfigured: Boolean,
    aiReviewRunning: Boolean,
    aiStatusText: String,
    aiVerdict: AiReviewVerdict?,
    onPreview: (MediaImage) -> Unit,
    onRunAiReview: () -> Unit,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PreviewTile(
                image = entry.image,
                badge = "Photo",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPreview(entry.image) }
            )

            MetaPillRow(
                items = listOf(
                    entry.image.folder,
                    entry.image.dimensionsText,
                    entry.image.sizeText
                )
            )

            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (showAiReview) {
                AiReviewCard(
                    aiModelConfigured = aiModelConfigured,
                    isRunning = aiReviewRunning,
                    aiStatusText = aiStatusText,
                    verdict = aiVerdict,
                    onRunAiReview = onRunAiReview
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
                FilledTonalButton(onClick = onKeep, modifier = Modifier.weight(1f)) {
                    Text("Keep")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f)) {
                    Text("Previous")
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text("Next")
                }
            }
        }
    }
}

@Composable
private fun AiReviewCard(
    aiModelConfigured: Boolean,
    isRunning: Boolean,
    aiStatusText: String,
    verdict: AiReviewVerdict?,
    onRunAiReview: () -> Unit
) {
    val badgeBackground = when (verdict?.label) {
        AiReviewLabel.FORWARD -> Color(0xFFFFE3D8)
        AiReviewLabel.PHOTO -> Color(0xFFDDF7F0)
        AiReviewLabel.UNSURE -> Color(0xFFE6EEF9)
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeContent = when (verdict?.label) {
        AiReviewLabel.FORWARD -> Color(0xFFE76F51)
        AiReviewLabel.PHOTO -> Color(0xFF0F766E)
        AiReviewLabel.UNSURE -> Color(0xFF3B82F6)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI check",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (aiModelConfigured) aiStatusText else "Import Gemma 3n E2B on the overview screen first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                verdict?.let {
                    CountBadge(
                        text = "${it.confidence}%",
                        background = badgeBackground,
                        content = badgeContent
                    )
                }
            }

            verdict?.let {
                CountBadge(
                    text = it.headline,
                    background = badgeBackground,
                    content = badgeContent
                )
                Text(
                    text = it.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                MetaPillRow(
                    items = listOf(
                        it.modelName,
                        it.backendLabel,
                        formatLastScan(it.reviewedAtMillis) ?: "Just now"
                    )
                )
            }

            if (isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            FilledTonalButton(
                onClick = onRunAiReview,
                enabled = aiModelConfigured && !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (verdict == null) "Run AI Check" else "Run Again")
            }
        }
    }
}

@Composable
private fun PairEntryReview(
    entry: CleanupEntry.PairEntry,
    onPreview: (MediaImage) -> Unit,
    onDeleteLeft: () -> Unit,
    onDeleteRight: () -> Unit,
    onDeleteBoth: () -> Unit,
    onKeep: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val suggestedDeleteId = entry.pair.suggestedDeleteId

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CountBadge(
                    text = "${entry.pair.confidence}% match",
                    background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.secondary
                )
                CountBadge(
                    text = "Suggested ${if (suggestedDeleteId == entry.first.id) "left" else "right"}",
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 720.dp
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreviewTile(
                            image = entry.first,
                            badge = if (entry.first.id == suggestedDeleteId) "Left | suggested" else "Left",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onPreview(entry.first) }
                        )
                        PreviewTile(
                            image = entry.second,
                            badge = if (entry.second.id == suggestedDeleteId) "Right | suggested" else "Right",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onPreview(entry.second) }
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreviewTile(
                            image = entry.first,
                            badge = if (entry.first.id == suggestedDeleteId) "Left | suggested" else "Left",
                            modifier = Modifier.weight(1f),
                            onClick = { onPreview(entry.first) }
                        )
                        PreviewTile(
                            image = entry.second,
                            badge = if (entry.second.id == suggestedDeleteId) "Right | suggested" else "Right",
                            modifier = Modifier.weight(1f),
                            onClick = { onPreview(entry.second) }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDeleteLeft, modifier = Modifier.weight(1f)) {
                    Text("Delete Left")
                }
                Button(onClick = onDeleteRight, modifier = Modifier.weight(1f)) {
                    Text("Delete Right")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onDeleteBoth, modifier = Modifier.weight(1f)) {
                    Text("Delete Both")
                }
                FilledTonalButton(onClick = onKeep, modifier = Modifier.weight(1f)) {
                    Text("Keep")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f)) {
                    Text("Previous")
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text("Next")
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(
    image: MediaImage,
    badge: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box {
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.12f)
                    .clip(RoundedCornerShape(26.dp)),
                contentScale = ContentScale.Crop
            )

            CountBadge(
                text = badge,
                background = Color.Black.copy(alpha = 0.55f),
                content = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                        )
                    )
                    .padding(14.dp)
            ) {
                Text(
                    text = image.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${image.folder} | ${image.dimensionsText}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ThumbnailRail(
    entries: List<CleanupEntry>,
    selectedKey: String?,
    selectedKeys: Set<String>,
    selectionMode: Boolean,
    onSelectEntry: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onLongPressEntry: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "All items",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items = entries, key = { "${it.queueId.name}-${it.key}" }) { entry ->
                    val isSelected = entry.key == selectedKey
                    val isMarked = entry.key in selectedKeys
                    val accent = queueAccent(entry.queueId)
                    val previewImage = when (entry) {
                        is CleanupEntry.PairEntry -> entry.first
                        is CleanupEntry.SingleEntry -> entry.image
                    }
                    Card(
                        modifier = Modifier
                            .width(138.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        onToggleSelection(entry.key)
                                    } else {
                                        onSelectEntry(entry.key)
                                    }
                                },
                                onLongClick = { onLongPressEntry(entry.key) }
                            ),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMarked || isSelected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AsyncImage(
                                model = previewImage.uri,
                                contentDescription = previewImage.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop
                            )
                            if (isMarked) {
                                CountBadge(
                                    text = "Selected",
                                    background = accent.copy(alpha = 0.14f),
                                    content = accent
                                )
                            }
                            Text(
                                text = previewImage.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    image: MediaImage,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF111315)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = image.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${image.folder} | ${image.dimensionsText} | ${image.sizeText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }

                AsyncImage(
                    model = image.uri,
                    contentDescription = image.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(26.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
private fun MetaPillRow(items: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { MetaPill(text = it, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun MetaPill(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CountBadge(
    text: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = background
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun queueAccent(queueId: CleanupQueueId): Color =
    when (queueId) {
        CleanupQueueId.EXACT -> Color(0xFFFF5A5F)
        CleanupQueueId.SIMILAR -> Color(0xFF3B82F6)
        CleanupQueueId.BLURRY -> Color(0xFF7C8798)
        CleanupQueueId.FORWARD -> Color(0xFFE76F51)
        CleanupQueueId.SCREENSHOT -> Color(0xFF2A9D8F)
        CleanupQueueId.TEXT_HEAVY -> Color(0xFF8D6E63)
    }

private fun formatLastScan(lastScanMillis: Long?): String? {
    val millis = lastScanMillis?.takeIf { it > 0L } ?: return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(millis)
}
