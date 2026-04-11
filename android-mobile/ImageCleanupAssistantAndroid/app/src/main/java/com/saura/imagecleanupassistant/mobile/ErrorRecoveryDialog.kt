package com.saura.imagecleanupassistant.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.HelpOutline

/**
 * Represents different types of errors that can occur in the app.
 * Each type has different recovery strategies.
 */
sealed interface AppError {
    val message: String
    val isRetryable: Boolean
    
    data class NetworkError(
        override val message: String = "Network connection lost",
        val retryable: Boolean = true
    ) : AppError {
        override val isRetryable = retryable
    }
    
    data class TimeoutError(
        override val message: String = "Operation timed out. Please check your connection."
    ) : AppError {
        override val isRetryable = true
    }
    
    data class StorageError(
        override val message: String,
        val needsPermission: Boolean = false
    ) : AppError {
        override val isRetryable = !needsPermission
    }
    
    data class PermissionError(
        override val message: String = "Permission denied"
    ) : AppError {
        override val isRetryable = false
    }
    
    data class ScanError(
        override val message: String,
        val itemsProcessed: Int = 0,
        val total: Int = 0
    ) : AppError {
        override val isRetryable = true
    }
    
    data class DeleteError(
        override val message: String,
        val itemId: String
    ) : AppError {
        override val isRetryable = true
    }
}

/**
 * Enhanced error dialog with recovery options.
 * Shows detailed error info, retry buttons, and guidance.
 */
@Composable
fun ErrorRecoveryDialog(
    error: AppError,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onMoreDetails: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showDetails by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = when (error) {
                    is AppError.NetworkError -> Icons.Filled.Warning
                    is AppError.TimeoutError -> Icons.Filled.Warning
                    is AppError.PermissionError -> Icons.Filled.Error
                    is AppError.StorageError -> Icons.Filled.Error
                    is AppError.ScanError -> Icons.Filled.Info
                    is AppError.DeleteError -> Icons.Filled.Error
                },
                contentDescription = "Error",
                tint = when (error) {
                    is AppError.NetworkError -> MaterialTheme.colorScheme.warning
                    is AppError.TimeoutError -> MaterialTheme.colorScheme.warning
                    is AppError.PermissionError -> MaterialTheme.colorScheme.error
                    is AppError.StorageError -> MaterialTheme.colorScheme.error
                    is AppError.ScanError -> MaterialTheme.colorScheme.tertiary
                    is AppError.DeleteError -> MaterialTheme.colorScheme.error
                }
            )
        },
        title = {
            Text(
                text = getErrorTitle(error),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Show details if expanded
                if (showDetails) {
                    DetailsSection(error)
                }
                
                // Show guidance for specific errors
                GuidanceSection(error)
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (error.isRetryable) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Retry")
                    }
                }
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss")
                }
            }
        },
        dismissButton = {
            if (onMoreDetails != null) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide Details" else "More Details")
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun DetailsSection(error: AppError) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            
            when (error) {
                is AppError.ScanError -> {
                    Text(
                        text = "Processed: ${error.itemsProcessed} / ${error.total} items",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                is AppError.StorageError -> {
                    Text(
                        text = "Check available storage space and permissions",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                is AppError.DeleteError -> {
                    Text(
                        text = "Item: ${error.itemId}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun GuidanceSection(error: AppError) {
    val guidance = getGuidanceText(error)
    if (guidance.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = guidance,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun getErrorTitle(error: AppError): String = when (error) {
    is AppError.NetworkError -> "Connection Lost"
    is AppError.TimeoutError -> "Request Timed Out"
    is AppError.PermissionError -> "Permission Denied"
    is AppError.StorageError -> "Storage Error"
    is AppError.ScanError -> "Scan Failed"
    is AppError.DeleteError -> "Delete Failed"
}

private fun getGuidanceText(error: AppError): String = when (error) {
    is AppError.NetworkError -> "• Check WiFi connection\n• Move closer to router\n• Retry when connection is stable"
    is AppError.TimeoutError -> "• Check network speed\n• Retry the operation\n• Consider transferring fewer images"
    is AppError.PermissionError -> "Grant permission in Settings > Apps > Image Cleanup"
    is AppError.StorageError -> "Free up storage space or use a different folder"
    is AppError.ScanError -> "Scan was interrupted. You can resume from where it stopped."
    is AppError.DeleteError -> "File may be in use. Ensure it's not open in another app."
}

// Color extension for warning
val MaterialTheme.ColorScheme.warning
    get() = errorContainer
