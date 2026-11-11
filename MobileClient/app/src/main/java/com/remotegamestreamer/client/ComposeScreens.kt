package com.remotegamestreamer.client

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api

@Composable
fun ConnectionScreen(
    initialAddress: String,
    onConnect: (String, String) -> Unit,
    onSettings: () -> Unit,
) {
    var address by remember { mutableStateOf(initialAddress) }
    var pin by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(54.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("OD", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("OmniDeck", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Stream your PC games to your phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Connect to your PC", style = MaterialTheme.typography.titleLarge)
                        Text("Use the address and one-time PIN shown on the host.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("PC address") },
                        placeholder = { Text("192.168.0.200") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("6-digit PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Button(
                        onClick = { onConnect(address.trim(), pin) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = address.isNotBlank() && pin.length == 6,
                    ) { Text("Connect to PC") }
                }
            }
            TextButton(onClick = onSettings, modifier = Modifier.padding(top = 8.dp).heightIn(min = 44.dp)) {
                Text("Settings")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionLabel: String,
    hevcAvailable: Boolean,
    onBack: () -> Unit,
    onEditLayout: () -> Unit,
    onCheckUpdates: () -> Unit,
    onForgetHosts: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 44.dp)) { Text("‹  Back") }
        Column(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Tune your stream and on-screen controls", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSection("Stream", "Video quality and playback") {
            DropdownSetting("Resolution", AppSettings.RESOLUTIONS.map { "${it}p" }, AppSettings.RESOLUTIONS.indexOf(settings.maxHeight).coerceAtLeast(0)) {
                settings.maxHeight = AppSettings.RESOLUTIONS[it]
            }
            DropdownSetting("Frame rate", AppSettings.FRAME_RATES.map { "$it FPS" }, AppSettings.FRAME_RATES.indexOf(settings.fps).coerceAtLeast(0)) {
                settings.fps = AppSettings.FRAME_RATES[it]
            }
            SliderSetting("Bitrate", settings.bitrateMbps, AppSettings.MIN_BITRATE, AppSettings.MAX_BITRATE, { "$it Mbps" }) {
                settings.bitrateMbps = it
            }
            if (hevcAvailable) {
                DropdownSetting("Video codec", listOf("H.264 · most compatible", "HEVC · better at low bitrate"), if (settings.hevc) 1 else 0) {
                    settings.hevc = it == 1
                }
            }
            SwitchSetting("Stream audio", "Send game audio to this phone", settings.sound) { settings.sound = it }
            SliderSetting("Displayed game area", settings.displayScalePercent, 50, 100, { "$it%" }) {
                settings.displayScalePercent = it
            }
            SwitchSetting("Fill the phone screen", "Stretch video to the display edges", settings.stretchToFill) { settings.stretchToFill = it }
        }

        SettingsSection("Controls", "Touch layout and camera movement") {
            SwitchSetting("Lock joystick in place", "Keep the left stick in one position", settings.stickLocked) { settings.stickLocked = it }
            Text("Set opacity separately for each button in Edit controller layout → Style.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SliderSetting("Camera left / right", settings.cameraSensitivityPercent,
                AppSettings.MIN_CAMERA_SENSITIVITY, AppSettings.MAX_CAMERA_SENSITIVITY, { "$it%" }) {
                settings.cameraSensitivityPercent = it
            }
            SliderSetting("Camera up / down", settings.cameraSensitivityYPercent,
                AppSettings.MIN_CAMERA_SENSITIVITY, AppSettings.MAX_CAMERA_SENSITIVITY, { "$it%" }) {
                settings.cameraSensitivityYPercent = it
            }
            Button(onClick = onEditLayout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Edit controller layout")
            }
        }

        SettingsSection("App", "Updates and trusted connections") {
            OutlinedButton(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Check for updates · $versionLabel")
            }
            OutlinedButton(onClick = onForgetHosts, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Forget saved PCs")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTabBar(
    selectedTab: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    includeMapping: Boolean = true,
) {
    val tabs = if (includeMapping) listOf("Layout", "Mapping", "Style", "Actions") else listOf("Layout", "Style", "Actions")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { title ->
            val selected = title == selectedTab
            val selectedContainer by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else MaterialTheme.colorScheme.surface,
                animationSpec = tween(180),
                label = "${title}TabColor",
            )
            val scale by animateFloatAsState(if (selected) 1f else .97f, animationSpec = tween(180), label = "${title}TabScale")
            val selectedTextColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(180),
                label = "${title}TabText",
            )
            Surface(
                onClick = { onSelect(title) },
                modifier = Modifier.weight(1f).height(52.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                shape = RoundedCornerShape(12.dp),
                color = selectedContainer,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .42f)) else null,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EditorTabIcon(title, selected)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (title == "Mapping") "Map" else title,
                        maxLines = 1,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = selectedTextColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorTabIcon(title: String, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round)
        when (title) {
            "Layout" -> {
                val gap = s * .12f
                val cell = (s - gap) / 2f
                listOf(0f to 0f, cell + gap to 0f, 0f to cell + gap, cell + gap to cell + gap).forEach { (x, y) ->
                    drawRect(color, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell), style = stroke)
                }
            }
            "Mapping" -> {
                val center = androidx.compose.ui.geometry.Offset(s * .5f, s * .5f)
                val points = listOf(.18f to .2f, .82f to .2f, .18f to .8f, .82f to .8f).map { androidx.compose.ui.geometry.Offset(s * it.first, s * it.second) }
                points.forEach { drawLine(color, center, it, strokeWidth = stroke.width, cap = StrokeCap.Round) }
                points.forEach { drawCircle(color, s * .09f, it) }
                drawCircle(color, s * .12f, center)
            }
            "Style" -> {
                listOf(.25f, .5f, .75f).forEachIndexed { index, y ->
                    drawLine(color, androidx.compose.ui.geometry.Offset(s * .12f, s * y), androidx.compose.ui.geometry.Offset(s * .88f, s * y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    val x = listOf(.36f, .68f, .46f)[index]
                    drawCircle(surfaceColor, s * .13f, androidx.compose.ui.geometry.Offset(s * x, s * y))
                    drawCircle(color, s * .13f, androidx.compose.ui.geometry.Offset(s * x, s * y), style = stroke)
                }
            }
            else -> {
                listOf(.2f, .5f, .8f).forEachIndexed { index, y ->
                    val left = s * (.18f + index * .05f)
                    drawLine(color, androidx.compose.ui.geometry.Offset(left, s * y), androidx.compose.ui.geometry.Offset(s * .82f, s * y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun DropdownSetting(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(selected.coerceIn(options.indices)) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(options[current], modifier = Modifier.weight(1f), maxLines = 1)
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        current = index
                        expanded = false
                        onSelect(index)
                    })
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(label: String, initial: Int, min: Int, max: Int, valueText: (Int) -> String, onChange: (Int) -> Unit) {
    var current by remember { mutableIntStateOf(initial.coerceIn(min, max)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText(current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = current.toFloat(), onValueChange = {
            current = it.toInt().coerceIn(min, max)
            onChange(current)
        }, valueRange = min.toFloat()..max.toFloat())
    }
}

@Composable
private fun SwitchSetting(label: String, description: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onChange(it)
        })
    }
}
