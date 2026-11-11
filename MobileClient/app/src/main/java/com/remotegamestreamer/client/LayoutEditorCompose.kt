package com.remotegamestreamer.client

import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

data class EditorOption(val key: String, val label: String, val drawerId: String? = null)

data class LayoutEditorUiState(
    val landscape: Boolean,
    val gameScreen: Boolean,
    val showTools: Boolean,
    val showControls: Boolean,
    val collapsed: Boolean,
    val tab: String,
    val controlOptions: List<EditorOption>,
    val drawers: List<EditorOption>,
    val selectedDrawerId: String,
    val selectedControl: String?,
    val presets: List<String>,
    val selectedPreset: Int,
    val displayScale: Int,
    val stretchToFill: Boolean,
    val selectedVisible: Boolean,
    val selectedInMore: Boolean,
    val moreLocked: Boolean,
    val label: String,
    val scale: Float,
    val opacity: Int,
    val colorIndex: Int,
    val shapeIndex: Int,
    val iconIndex: Int,
    val inputOptions: List<String>,
    val selectedInput: Int,
    val inputDescription: String,
    val mappedOutput: String,
    val touchDragCamera: Boolean,
)

data class LayoutEditorActions(
    val onTab: (String) -> Unit,
    val onScene: (Boolean) -> Unit,
    val onTools: (Boolean) -> Unit,
    val onControls: (Boolean) -> Unit,
    val onCollapse: () -> Unit,
    val onSave: () -> Unit,
    val onPreset: (Int) -> Unit,
    val onSavePreset: () -> Unit,
    val onControl: (String) -> Unit,
    val onAddControl: () -> Unit,
    val onCreateDrawer: () -> Unit,
    val onDeleteDrawer: () -> Unit,
    val onSelectDrawer: (Int) -> Unit,
    val onMoveToDrawer: (String) -> Unit,
    val onVisibility: () -> Unit,
    val onGroup: () -> Unit,
    val onAnchor: () -> Unit,
    val onLabel: (String) -> Unit,
    val onScale: (Float) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onColor: (Int) -> Unit,
    val onShape: (Int) -> Unit,
    val onIcon: (Int) -> Unit,
    val onChooseImage: () -> Unit,
    val onClearImage: () -> Unit,
    val onHideAll: () -> Unit,
    val onShowAll: () -> Unit,
    val onReset: () -> Unit,
    val onInput: (Int) -> Unit,
    val onCameraMode: (Boolean) -> Unit,
    val onDisplayScale: (Int) -> Unit,
    val onStretch: () -> Unit,
)

@Composable
fun LayoutEditorScreen(
    state: LayoutEditorUiState,
    actions: LayoutEditorActions,
    controllerPreview: View,
    gameHud: View,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = listOf(Color(0xFF071720), Color(0xFF0B2932), Color(0xFF0A1D29)),
            ),
        ),
    ) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        if (state.gameScreen) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { gameHud }, modifier = Modifier.fillMaxSize())
                if (state.showTools && !state.collapsed) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .55f))
                            .clickable { actions.onTools(false); actions.onControls(false) },
                    )
                    val panelModifier = if (state.landscape) {
                        Modifier.align(Alignment.CenterStart).fillMaxHeight().widthIn(max = 360.dp).width(viewportWidth * .40f).padding(8.dp)
                    } else {
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(.94f).heightIn(max = viewportHeight * .525f).padding(horizontal = 4.dp, vertical = 8.dp)
                    }
                    GameScreenTools(state, actions, panelModifier)
                } else {
                    MenuIconButton(
                        onClick = { if (state.showTools) actions.onCollapse() else actions.onTools(true) },
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        label = if (state.showTools) "Show game screen tools" else "Open game screen tools",
                    )
                }
            }
        } else if (state.landscape) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.collapsed) EditorInspector(state, actions, Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.weight(if (state.collapsed) 1f else 1.5f).fillMaxHeight()) {
                    AndroidView(factory = { controllerPreview }, modifier = Modifier.fillMaxSize())
                    if (state.collapsed) {
                        MenuIconButton(onClick = actions.onCollapse, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().weight(if (state.collapsed) 1f else .95f)) {
                    AndroidView(factory = { controllerPreview }, modifier = Modifier.fillMaxSize())
                    if (state.collapsed) {
                        MenuIconButton(onClick = actions.onCollapse, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                    }
                }
                if (!state.collapsed) EditorInspector(state, actions, Modifier.fillMaxWidth().weight(1.05f))
            }
        }
    }
}

@Composable
private fun EditorInspector(state: LayoutEditorUiState, actions: LayoutEditorActions, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorTabBar(state.tab, actions.onTab, Modifier.weight(1f))
                CloseIconButton(onClick = actions.onCollapse, label = "Close control layout panel")
            }
            Crossfade(
                targetState = state.tab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(170),
                label = "editorTabContent",
            ) { activeTab ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (activeTab == "Mapping") "Physical controller mapping" else "Control layout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (activeTab) {
                            "Mapping" -> "Inspect the Xbox output from each physical controller input."
                            "Style" -> "Customize the selected on-screen control."
                            "Actions" -> "Manage the whole touch layout."
                            else -> "Arrange touch controls or view the physical controller."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (activeTab) {
                        "Mapping" -> MappingEditor(state, actions)
                        "Style" -> StyleEditor(state, actions)
                        "Actions" -> ActionsEditor(state, actions)
                        else -> LayoutControls(state, actions)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("✓  Save layout settings") }
        }
    }
}

@Composable
private fun LayoutControls(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    EditorCard("WORKSPACE") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectButton("Controller", !state.gameScreen, Modifier.weight(1f)) { actions.onScene(false) }
            SelectButton("Game screen", state.gameScreen, Modifier.weight(1f)) { actions.onScene(true) }
        }
    }
    if (state.gameScreen) {
        EditorCard("GAME SCREEN") {
            Text("Adjust the visible game area, then position your touch controls on the preview.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            EditorSlider("Displayed game area", state.displayScale.toFloat(), 50f..100f, "${state.displayScale}%") { actions.onDisplayScale(it.toInt()) }
            SelectButton(if (state.stretchToFill) "Fill screen · on" else "Fill screen", state.stretchToFill, Modifier.fillMaxWidth(), actions.onStretch)
        }
    }
    EditorCard("PRESETS & CONFIGURATION") {
        EditorDropdown("Preset", state.presets, state.selectedPreset, actions.onPreset)
        OutlinedButton(onClick = actions.onSavePreset, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("＋  Save current layout as preset") }
    }
}

@Composable
private fun MappingEditor(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    EditorCard("PHYSICAL CONTROLLER INPUT") {
        EditorDropdown("Input", state.inputOptions, state.selectedInput, actions.onInput)
        InfoPanel("BEHAVIOR PROFILE", state.inputDescription)
    }
    EditorCard("MAPPED XBOX OUTPUT") { InfoPanel("NATIVE XBOX MAPPING", state.mappedOutput) }
    EditorCard("RIGHT CAMERA CONTROL MODE") {
        Text(
            if (state.touchDragCamera)
                "Touch swipes or the physical right stick move the camera as relative input. The on-screen right joystick stays hidden; press R3 to pause or resume physical-stick camera input."
            else
                "The on-screen right joystick is shown, and the physical right stick passes through as standard analog input.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectButton("Touch drag", state.touchDragCamera, Modifier.weight(1f)) { actions.onCameraMode(true) }
            SelectButton("Right joystick", !state.touchDragCamera, Modifier.weight(1f)) { actions.onCameraMode(false) }
        }
    }
}

@Composable
private fun StyleEditor(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    InfoPanel("EDITING APPEARANCE", state.selectedControl?.let { state.controlOptions.firstOrNull { item -> item.key == it }?.label } ?: "Select a control")
    OutlinedTextField(
        value = state.label,
        onValueChange = actions.onLabel,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.selectedControl != null && state.selectedControl != "stick",
        label = { Text("Button text label") },
        singleLine = true,
    )
    EditorSlider("Size", state.scale, .5f..2f, "${(state.scale * 100).toInt()}%") { actions.onScale(it) }
    EditorSlider("Button opacity", state.opacity.toFloat(), 10f..100f, "${state.opacity}%", enabled = state.selectedControl != null) { actions.onOpacity(it.toInt()) }
    EditorDropdown("Button color", listOf("White", "Sky blue", "Mint", "Amber", "Coral", "Violet"), state.colorIndex, actions.onColor)
    EditorDropdown("Button shape", listOf("Circle", "Square", "Rounded square"), state.shapeIndex, actions.onShape)
    EditorDropdown("Button icon", listOf("Default label", "More menu", "HUD drawer", "Folder", "Hide HUD", "Gamepad", "Keyboard", "Back", "Camera", "Target", "Star"), state.iconIndex, actions.onIcon)
    EditorCard("CUSTOM ICON OR ARTWORK") {
        Text("Upload a PNG, JPEG, WebP, or simple SVG for the selected control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val artworkEnabled = state.selectedControl != null && state.selectedControl != "stick"
            OutlinedButton(onClick = actions.onChooseImage, enabled = artworkEnabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Upload file") }
            OutlinedButton(onClick = actions.onClearImage, enabled = artworkEnabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Clear artwork") }
        }
    }
}

@Composable
private fun ActionsEditor(state: LayoutEditorUiState, actions: LayoutEditorActions) {
    EditorCard("COLLAPSIBLE HUD DRAWERS") {
        Text("Create separate folders for controls you want to keep tucked away.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EditorDropdown("Add controls to", state.drawers.map { it.label }, state.drawers.indexOfFirst { it.key == state.selectedDrawerId }.coerceAtLeast(0), actions.onSelectDrawer)
        OutlinedButton(onClick = actions.onCreateDrawer, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("＋  Create drawer folder") }
        OutlinedButton(
            onClick = actions.onDeleteDrawer,
            enabled = state.selectedDrawerId != "more" && state.selectedDrawerId != "none",
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        ) {
            Text(
                when (state.selectedDrawerId) {
                    "none" -> "No drawer selected"
                    "more" -> "Built-in More drawer"
                    else -> "Delete drawer · return controls to main HUD"
                },
                maxLines = 2,
            )
        }
    }
    EditorCard("ON-SCREEN CONTROLS") {
        Text("Show, hide, or tuck controls into the Collapsible HUD Drawer to keep the game view clear.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = actions.onAddControl, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("＋  Add hidden control") }
        state.controlOptions.forEach { option ->
            val isVisible = !option.label.endsWith("· hidden")
            val controlLabel = option.label.substringAfter("· ").removeSuffix(" · hidden")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .62f),
                shape = MaterialTheme.shapes.small,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 10.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(controlLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        OutlinedButton(
                            onClick = { actions.onControl(option.key); actions.onVisibility() },
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(if (isVisible) "Hide" else "Add") }
                    }
                    if (option.key !in setOf("stick", "more", "hud") && !option.key.startsWith("drawer")) {
                        val isInDrawer = if (state.selectedDrawerId == "none") option.drawerId == null else option.drawerId == state.selectedDrawerId
                        val returningToMain = option.drawerId != null && (state.selectedDrawerId == "none" || option.drawerId == state.selectedDrawerId)
                        val canMove = returningToMain || (state.selectedDrawerId != "none" && !isInDrawer)
                        OutlinedButton(
                            onClick = { actions.onMoveToDrawer(option.key) },
                            enabled = canMove,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).heightIn(min = 40.dp),
                        ) {
                            Text(
                                when {
                                    returningToMain -> "↩  Move to main HUD"
                                    state.selectedDrawerId == "none" -> "On main HUD"
                                    else -> "＋  Move to ${state.drawers.firstOrNull { it.key == state.selectedDrawerId }?.label ?: "selected drawer"}"
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
    EditorCard("GLOBAL LAYOUT MANAGEMENT") {
        Text("Bulk control operations and layout reset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onHideAll, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Hide all") }
            OutlinedButton(onClick = actions.onShowAll, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Show all") }
        }
        OutlinedButton(onClick = actions.onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("Reset layout to default") }
    }
}

@Composable
private fun GameScreenTools(state: LayoutEditorUiState, actions: LayoutEditorActions, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorTabBar(state.tab, actions.onTab, Modifier.weight(1f), includeMapping = !state.gameScreen)
                CloseIconButton(onClick = { actions.onTools(false); actions.onControls(false) }, label = "Close Game Screen tools")
            }
            Crossfade(
                targetState = state.tab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(170),
                label = "gameToolsTabContent",
            ) { activeTab ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (activeTab == "Mapping") "Physical controller mapping" else "Control layout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (activeTab) {
                            "Mapping" -> "Inspect the Xbox output from each physical controller input."
                            "Style" -> "Customize the selected on-screen control."
                            "Actions" -> "Manage the whole touch layout."
                            else -> "Arrange touch controls and resize the game area."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (activeTab) {
                        "Mapping" -> MappingEditor(state, actions)
                        "Style" -> StyleEditor(state, actions)
                        "Actions" -> ActionsEditor(state, actions)
                        else -> LayoutControls(state, actions)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Button(onClick = actions.onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("✓  Save layout settings") }
        }
    }
}

@Composable
private fun MenuIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Toggle editor menu") {
    Surface(
        modifier = modifier.size(44.dp).semantics { contentDescription = label },
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f)),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val iconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f)
            Canvas(Modifier.size(18.dp)) {
                val stroke = 1.9.dp.toPx()
                val left = size.width * .14f
                val right = size.width * .86f
                listOf(.28f, .5f, .72f).forEach { y ->
                    drawLine(iconColor, androidx.compose.ui.geometry.Offset(left, size.height * y), androidx.compose.ui.geometry.Offset(right, size.height * y), stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun CloseIconButton(onClick: () -> Unit, label: String = "Close editor panel") {
    Surface(
        modifier = Modifier.size(44.dp).semantics { contentDescription = label },
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val iconColor = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(16.dp)) {
                val inset = size.minDimension * .2f
                val stroke = 1.8.dp.toPx()
                drawLine(iconColor, androidx.compose.ui.geometry.Offset(inset, inset), androidx.compose.ui.geometry.Offset(size.width - inset, size.height - inset), stroke, cap = StrokeCap.Round)
                drawLine(iconColor, androidx.compose.ui.geometry.Offset(size.width - inset, inset), androidx.compose.ui.geometry.Offset(inset, size.height - inset), stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun EditorCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = .7.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoPanel(title: String, value: String) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelectButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.heightIn(min = 44.dp)) { Text(label, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 44.dp)) { Text(label, maxLines = 1) }
}

@Composable
private fun EditorDropdown(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val index = selected.coerceIn(options.indices)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(options.getOrElse(index) { "Select" }, modifier = Modifier.weight(1f), maxLines = 1)
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { i, option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(i) }) }
            }
        }
    }
}

@Composable
private fun EditorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}
