package ai.musicconverter.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text

import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ai.musicconverter.data.ConversionStatus
import ai.musicconverter.data.MusicFile
import ai.musicconverter.ui.components.AluminumVariant
import ai.musicconverter.ui.components.AquaStyle
import ai.musicconverter.ui.components.BrushedMetalBottomBar
import ai.musicconverter.ui.components.GelButton
import ai.musicconverter.ui.components.aluminumBackgroundModifier
import kotlinx.coroutines.delay

// Bone/off-white background for file listing
private val BoneWhite = Color(0xFFF5F0E6)
private val BoneDivider = Color(0xFFDDD8CE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicConverterScreen(
    viewModel: MusicConverterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val autoConvertEnabled by viewModel.autoConvertEnabled.collectAsState()
    val keepOriginalEnabled by viewModel.keepOriginalEnabled.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    var showConvertConfirmDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState()

    val isConverting = uiState.isBatchConverting || uiState.musicFiles.any { it.isConverting }
    val pendingCount = uiState.musicFiles.count { it.needsConversion && !it.isConverting }
    val displayFiles = uiState.filteredFiles

    val searchFocusRequester = remember { FocusRequester() }

    val conversionProgress = if (uiState.totalToConvert > 0) {
        (uiState.convertedCount.toFloat() / uiState.totalToConvert).coerceIn(0f, 1f)
    } else 0f

    val elapsedTimeText = String.format(
        "%02d:%02d:%02d",
        uiState.convertedCount / 3600,
        (uiState.convertedCount % 3600) / 60,
        uiState.convertedCount % 60
    )

    // Notification text for calculator display (replaces toasts/snackbars)
    var notificationText by remember { mutableStateOf<String?>(null) }

    // Show errors in calculator display instead of snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            notificationText = error
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            delay(4000)
            notificationText = null
            viewModel.clearError()
        }
    }

    // Show completion notification
    LaunchedEffect(uiState.status) {
        if (uiState.status == ConversionStatus.COMPLETED) {
            notificationText = "Conversion complete!"
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            delay(3000)
            notificationText = null
        }
    }

    // Auto-dismiss aluminum variant notification
    LaunchedEffect(notificationText) {
        if (notificationText?.startsWith("Aluminum:") == true) {
            delay(2000)
            notificationText = null
        }
    }

    // Permission launcher
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.setStoragePermissionGranted(true)
        }
    }

    val hasManageStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else true

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                viewModel.setStoragePermissionGranted(true)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    Scaffold(
        topBar = {
            // Metal window frame with traffic lights, search, and settings
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(aluminumBackgroundModifier())
                    .drawBehind {
                        // Bottom edge shadow for metal frame depth
                        drawLine(Color.Black.copy(alpha = 0.15f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1.5f)
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, size.height - 2.5f), Offset(size.width, size.height - 2.5f), strokeWidth = 0.5f)
                    }
            ) {
                // Top row: traffic lights + search bar + settings gear
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Graphite traffic light buttons
                    GraphiteTrafficLights()
                    Spacer(Modifier.width(10.dp))

                    // Search field
                    TextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = {
                            Text("Search...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF888888))
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(16.dp), tint = Color(0xFF777777))
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.5f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.3f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .focusRequester(searchFocusRequester)
                    )

                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp),
                            tint = if (autoConvertEnabled) Color(0xFF4444CC) else Color(0xFF555555)
                        )
                    }
                }
            }
        },
        bottomBar = {
            BrushedMetalBottomBar(
                isConverting = isConverting,
                conversionProgress = conversionProgress,
                elapsedTimeText = elapsedTimeText,
                notificationText = notificationText,
                onConvertClick = {
                    if (isConverting) viewModel.cancelAllConversions()
                    else showConvertConfirmDialog = true
                },
                onSearchClick = { searchFocusRequester.requestFocus() },
                onVariantChanged = { variant ->
                    notificationText = "Aluminum: ${variant.name}"
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BoneWhite)
        ) {
            when {
                !hasManageStoragePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    PermissionRequestContent(
                        onRequestPermission = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
                !uiState.hasStoragePermission -> {
                    PermissionRequestContent(
                        onRequestPermission = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
                            } else {
                                storagePermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            }
                        }
                    )
                }
                uiState.status == ConversionStatus.SCANNING -> {
                    LoadingContent(message = "Scanning for music files...")
                }
                uiState.musicFiles.isEmpty() -> {
                    EmptyContent(
                        message = "No music files need conversion",
                        onRefresh = { viewModel.scanForFiles() }
                    )
                }
                else -> {
                    CondensedMediaList(files = displayFiles, totalFiles = uiState.musicFiles.size)
                }
            }
        }
    }

    // Convert confirmation dialog
    if (showConvertConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConvertConfirmDialog = false },
            title = { Text("Convert ${pendingCount} Files?") },
            text = {
                Column {
                    Text("Convert all ${pendingCount} audio files to AAC format.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (keepOriginalEnabled) "Original files will be kept."
                        else "Original files will be deleted after conversion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                GelButton(onClick = {
                    showConvertConfirmDialog = false
                    viewModel.convertAllFiles()
                }, style = AquaStyle.BLUE) { Text("Convert", fontWeight = FontWeight.Bold, color = Color.White) }
            },
            dismissButton = {
                GelButton(onClick = { showConvertConfirmDialog = false }, style = AquaStyle.GRAY) {
                    Text("Cancel", color = Color(0xFF444444))
                }
            }
        )
    }

    // Settings bottom sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = settingsSheetState,
            containerColor = BoneWhite,
            dragHandle = {
                // Aluminum-styled drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(aluminumBackgroundModifier())
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(Color(0xFF999999), RoundedCornerShape(2.dp))
                    )
                }
            }
        ) {
            SettingsSheetContent(
                autoConvertEnabled = autoConvertEnabled,
                keepOriginalEnabled = keepOriginalEnabled,
                onToggleAutoConvert = { viewModel.toggleAutoConvert() },
                onToggleKeepOriginal = { viewModel.toggleKeepOriginal() },
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Graphite Traffic Light Buttons (decorative window controls)
// ──────────────────────────────────────────────────────────────

@Composable
private fun GraphiteTrafficLights() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Close, minimize, maximize — all graphite gray
        TrafficDot(Color(0xFF6E6E6E), Color(0xFF555555))
        TrafficDot(Color(0xFF6E6E6E), Color(0xFF555555))
        TrafficDot(Color(0xFF6E6E6E), Color(0xFF555555))
    }
}

@Composable
private fun TrafficDot(color: Color, borderColor: Color) {
    Canvas(modifier = Modifier.size(12.dp)) {
        // Outer shadow
        drawCircle(Color.Black.copy(alpha = 0.2f), radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f + 0.5f))
        // Body gradient
        drawCircle(
            Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), color, color.copy(alpha = 0.7f))),
            radius = size.minDimension / 2f - 0.5f
        )
        // Border ring
        drawCircle(borderColor, radius = size.minDimension / 2f - 0.5f, style = Stroke(1f))
        // Top specular highlight
        drawCircle(
            Color.White.copy(alpha = 0.35f),
            radius = size.minDimension / 4f,
            center = Offset(size.width / 2f, size.height * 0.35f)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Tabbed List View
// ──────────────────────────────────────────────────────────────

private data class TabItem(val label: String, val icon: @Composable () -> Unit)

@Composable
private fun MetalTabBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        TabItem("All") { Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF444444)) },
        TabItem("Convert") { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF444444)) },
        TabItem("Files") { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF444444)) },
        TabItem("Favorites") { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF444444)) },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFFD8D8DC), Color(0xFFC8C8CC), Color(0xFFBEBEC2)))
            )
            .drawBehind {
                drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 0.5f)
                drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 0.5f)
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedTab
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.verticalGradient(listOf(Color(0xFFEEEEF0), Color(0xFFDDDDE0)))
                                )
                                .border(0.5.dp, Color(0xFFAAAAB0), RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onTabSelected(index) }
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    tab.icon()
                    Spacer(Modifier.width(4.dp))
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color(0xFF333333) else Color(0xFF666666)
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Condensed Media Table List (bone background)
// ──────────────────────────────────────────────────────────────

@Composable
private fun CondensedMediaList(files: List<MusicFile>, totalFiles: Int) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Filter files based on selected tab
    val filteredFiles = when (selectedTab) {
        1 -> files.filter { it.needsConversion }  // Convert tab
        2 -> files  // Files tab = all
        3 -> files.take(0)  // Favorites = empty for now (stubbed)
        else -> files  // All tab
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MetalTabBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        MediaTableHeader()
        HorizontalDivider(color = BoneDivider)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp)
        ) {
            items(filteredFiles, key = { it.id }) { file ->
                MediaTableRow(file = file)
                HorizontalDivider(color = BoneDivider.copy(alpha = 0.6f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun MediaTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BoneWhite.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Name", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = Color(0xFF555555))
        Text("Time", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp), textAlign = TextAlign.End, color = Color(0xFF555555))
        Text("Track #", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center, color = Color(0xFF555555))
        Text("Artist", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp), textAlign = TextAlign.End, color = Color(0xFF555555))
    }
}

@Composable
private fun MediaTableRow(file: MusicFile) {
    val bgColor = if (file.isConverting) Color(0xFFE8E2D4) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), color = Color(0xFF333333))
            if (file.isConverting) {
                Spacer(modifier = Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF666666))
            }
        }
        Text(file.displayDuration, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp), textAlign = TextAlign.End, color = Color(0xFF666666))
        Text(file.displayTrack, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center, color = Color(0xFF666666))
        Text(file.artist ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp), textAlign = TextAlign.End, color = Color(0xFF666666))
    }
}

// ──────────────────────────────────────────────────────────────
// Settings Sheet
// ──────────────────────────────────────────────────────────────

@Composable
private fun SettingsSheetContent(
    autoConvertEnabled: Boolean,
    keepOriginalEnabled: Boolean,
    onToggleAutoConvert: () -> Unit,
    onToggleKeepOriginal: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BoneWhite)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
            GelButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF444444), fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        SettingsToggleCard(
            title = "Auto-Convert Service",
            description = if (autoConvertEnabled) "Automatically converts new audio files" else "Manual conversion only",
            checked = autoConvertEnabled,
            onToggle = onToggleAutoConvert
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsToggleCard(
            title = "Keep Original Files",
            description = if (keepOriginalEnabled) "Converted files saved next to originals" else "Originals deleted, saved to Music/Converted",
            checked = keepOriginalEnabled,
            onToggle = onToggleKeepOriginal
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "When auto-convert is enabled, the app monitors for new audio files and converts them to AAC format in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF777777)
        )
    }
}

@Composable
private fun SettingsToggleCard(title: String, description: String, checked: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) Color(0xFFE8E2D4) else Color(0xFFF0EDE6)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF777777))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Utility screens (flat buttons, no rounded pill shapes)
// ──────────────────────────────────────────────────────────────

@Composable
private fun PermissionRequestContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color(0xFF888888))
        Spacer(modifier = Modifier.height(16.dp))
        Text("Storage Permission Required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
        Spacer(modifier = Modifier.height(8.dp))
        Text("This app needs access to your storage to find and convert music files.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF777777), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        GelButton(onClick = onRequestPermission) {
            Text("Grant Permission", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF444444))
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Color(0xFF888888))
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, color = Color(0xFF555555))
    }
}

@Composable
private fun EmptyContent(message: String, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color(0xFFAAAAAA))
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF777777))
        Spacer(modifier = Modifier.height(16.dp))
        GelButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF444444))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh", color = Color(0xFF444444), fontWeight = FontWeight.Bold)
        }
    }
}
