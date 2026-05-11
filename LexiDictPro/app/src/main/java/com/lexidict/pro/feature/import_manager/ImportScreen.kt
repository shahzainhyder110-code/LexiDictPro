package com.lexidict.pro.feature.import_manager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lexidict.pro.core.domain.model.ImportState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
//  IMPORT SCREEN
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onImportComplete: () -> Unit
) {
    // SAF file picker — supports multi-select
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // TODO: trigger import for each URI via ViewModel
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Dictionary", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onImportComplete) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Drop zone
            ImportDropZone(
                onPickFiles = {
                    importLauncher.launch(
                        arrayOf(
                            "*/*",
                            "application/octet-stream"
                        )
                    )
                }
            )

            // Supported formats card
            SupportedFormatsCard()

            // Instructions
            ImportInstructions()
        }
    }
}

@Composable
private fun ImportDropZone(onPickFiles: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.FileUpload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Select Dictionary Files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "BGL, DSL, IFO, ZIP and more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPickFiles,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse Files")
            }
        }
    }
}

@Composable
private fun SupportedFormatsCard() {
    val formats = listOf(
        Triple("BGL", "Babylon Dictionary", Icons.Outlined.Book),
        Triple("DSL/LSD", "ABBYY Lingvo", Icons.Outlined.Translate),
        Triple("IFO/IDX", "StarDict", Icons.Outlined.Star),
        Triple("ZIP", "Compressed Archive", Icons.Outlined.Archive)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Supported Formats",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            formats.forEach { (ext, name, icon) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(icon, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        ".${ext.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(60.dp)
                    )
                    Text(name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ImportInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tips", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            listOf(
                "For StarDict: select the .ifo file",
                "Large dictionaries (500MB+) may take a few minutes to index",
                "Dictionaries are stored in internal app storage",
                "You can import from SD card or Downloads folder"
            ).forEach { tip ->
                Text(
                    text = "• $tip",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  IMPORT PROGRESS ITEM
// ═══════════════════════════════════════════════════════════════

@Composable
fun ImportProgressItem(
    fileName: String,
    state: ImportState,
    progress: Int,
    error: String? = null
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // State icon
            when (state) {
                ImportState.COMPLETED -> Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary)
                ImportState.FAILED -> Icon(Icons.Default.Error, null,
                    tint = MaterialTheme.colorScheme.error)
                ImportState.CANCELLED -> Icon(Icons.Default.Cancel, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1)
                Text(
                    text = when (state) {
                        ImportState.QUEUED -> "Queued"
                        ImportState.DETECTING_FORMAT -> "Detecting format..."
                        ImportState.EXTRACTING_METADATA -> "Reading metadata..."
                        ImportState.INDEXING -> "Indexing... $progress%"
                        ImportState.COMPLETED -> "Completed"
                        ImportState.FAILED -> "Failed: ${error ?: "Unknown error"}"
                        ImportState.CANCELLED -> "Cancelled"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == ImportState.FAILED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state == ImportState.INDEXING) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
