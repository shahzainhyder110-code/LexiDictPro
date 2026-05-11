package com.lexidict.pro.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lexidict.pro.core.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            _settings.update(transform)
            // In a full implementation, persist to DataStore here
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {

            // ── Appearance ──────────────────────────────────────────────────
            SettingsSectionHeader(title = "Appearance")

            SwitchSettingItem(
                icon = Icons.Outlined.DarkMode,
                title = "Dark Mode",
                subtitle = "Use dark color scheme",
                checked = settings.darkMode,
                onCheckedChange = { viewModel.updateSettings { it.copy(darkMode = !it.darkMode) } }
            )

            SwitchSettingItem(
                icon = Icons.Outlined.Palette,
                title = "Dynamic Colors",
                subtitle = "Use wallpaper-based colors (Android 12+)",
                checked = settings.dynamicColors,
                onCheckedChange = { viewModel.updateSettings { it.copy(dynamicColors = !it.dynamicColors) } }
            )

            // ── Search ──────────────────────────────────────────────────────
            SettingsSectionHeader(title = "Search")

            SwitchSettingItem(
                icon = Icons.Outlined.ManageSearch,
                title = "Fuzzy Search",
                subtitle = "Show approximate matches for misspellings",
                checked = settings.fuzzySearchEnabled,
                onCheckedChange = { viewModel.updateSettings { it.copy(fuzzySearchEnabled = !it.fuzzySearchEnabled) } }
            )

            SwitchSettingItem(
                icon = Icons.Outlined.Spellcheck,
                title = "Morphological Search",
                subtitle = "Match word stems and inflections",
                checked = settings.morphologicalSearch,
                onCheckedChange = { viewModel.updateSettings { it.copy(morphologicalSearch = !it.morphologicalSearch) } }
            )

            SwitchSettingItem(
                icon = Icons.Outlined.Translate,
                title = "Transliteration",
                subtitle = "Search across scripts (e.g. Cyrillic / Latin)",
                checked = settings.transliterationEnabled,
                onCheckedChange = { viewModel.updateSettings { it.copy(transliterationEnabled = !it.transliterationEnabled) } }
            )

            // ── Audio ────────────────────────────────────────────────────────
            SettingsSectionHeader(title = "Audio & Pronunciation")

            SwitchSettingItem(
                icon = Icons.Outlined.VolumeUp,
                title = "Auto-play Pronunciation",
                subtitle = "Play audio automatically on word open",
                checked = settings.autoPlayPronunciation,
                onCheckedChange = { viewModel.updateSettings { it.copy(autoPlayPronunciation = !it.autoPlayPronunciation) } }
            )

            SwitchSettingItem(
                icon = Icons.Outlined.RecordVoiceOver,
                title = "TTS Fallback",
                subtitle = "Use text-to-speech when audio file is unavailable",
                checked = settings.ttsEnabled,
                onCheckedChange = { viewModel.updateSettings { it.copy(ttsEnabled = !it.ttsEnabled) } }
            )

            // ── Floating / Overlay ──────────────────────────────────────────
            SettingsSectionHeader(title = "Floating Overlay")

            SwitchSettingItem(
                icon = Icons.Outlined.BubbleChart,
                title = "Floating Bubble",
                subtitle = "Show floating overlay for quick lookup",
                checked = settings.floatingBubbleEnabled,
                onCheckedChange = { viewModel.updateSettings { it.copy(floatingBubbleEnabled = !it.floatingBubbleEnabled) } }
            )

            SwitchSettingItem(
                icon = Icons.Outlined.ContentPaste,
                title = "Clipboard Monitoring",
                subtitle = "Auto-lookup words copied to clipboard",
                checked = settings.clipboardMonitoring,
                onCheckedChange = { viewModel.updateSettings { it.copy(clipboardMonitoring = !it.clipboardMonitoring) } }
            )

            ClickableSettingItem(
                icon = Icons.Outlined.AdminPanelSettings,
                title = "Overlay Permission",
                subtitle = "Grant system overlay permission",
                onClick = {
                    context.startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            )

            ClickableSettingItem(
                icon = Icons.Outlined.Accessibility,
                title = "Accessibility Service",
                subtitle = "Enable for copy-to-translate feature",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            )

            // ── Storage ─────────────────────────────────────────────────────
            SettingsSectionHeader(title = "Storage")

            ClickableSettingItem(
                icon = Icons.Outlined.FolderOpen,
                title = "Dictionary Folder",
                subtitle = settings.defaultImportPath.ifBlank { "Not set — use import screen" },
                onClick = { /* Launch SAF folder picker */ }
            )

            ClickableSettingItem(
                icon = Icons.Outlined.DeleteSweep,
                title = "Clear Audio Cache",
                subtitle = "Delete cached TTS audio files",
                onClick = { /* Implement cache clear */ }
            )

            // ── About ────────────────────────────────────────────────────────
            SettingsSectionHeader(title = "About")

            ClickableSettingItem(
                icon = Icons.Outlined.Info,
                title = "Version",
                subtitle = "LexiDict Pro 1.0.0 (Build 1)",
                onClick = {}
            )

            ClickableSettingItem(
                icon = Icons.Outlined.Code,
                title = "Open Source Licenses",
                subtitle = "Third-party library licenses",
                onClick = { /* Show OSS licenses */ }
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ─── Reusable Setting Components ─────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun ClickableSettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
