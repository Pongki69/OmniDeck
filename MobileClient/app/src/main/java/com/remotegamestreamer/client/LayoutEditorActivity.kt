package com.remotegamestreamer.client

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.remotegamestreamer.client.input.HudCatalog
import com.remotegamestreamer.client.input.HudLayout
import com.remotegamestreamer.client.input.HudPreviewScene
import com.remotegamestreamer.client.input.TouchHudOverlay
import java.io.ByteArrayOutputStream

/** Touch-first HUD editor with a live preview and a scrollable properties panel. */
class LayoutEditorActivity : ComponentActivity() {
    private lateinit var hud: TouchHudOverlay
    private lateinit var layoutModel: HudLayout
    private lateinit var controlsPicker: Spinner
    private lateinit var presetPicker: Spinner
    private lateinit var settings: AppSettings
    private lateinit var inspectorScroll: ScrollView
    private lateinit var editorPanel: LinearLayout
    private lateinit var controllerMappingBlock: LinearLayout
    private lateinit var touchControlsPanel: LinearLayout
    private lateinit var layoutTabContent: LinearLayout
    private lateinit var styleTabContent: LinearLayout
    private lateinit var actionsTabContent: LinearLayout
    private lateinit var workspaceBlock: LinearLayout
    private lateinit var editorTabBar: ComposeView
    private lateinit var inspectorToolbar: LinearLayout
    private lateinit var inspectorMenuButton: Button
    private lateinit var inspectorContainer: LinearLayout
    private lateinit var controllerInputPicker: Spinner
    private lateinit var controllerInputDescription: TextView
    private lateinit var controllerOutputLabel: TextView
    private lateinit var touchDragModeButton: Button
    private lateinit var rightStickModeButton: Button
    private lateinit var moreAnchorButton: Button
    private lateinit var visibilityButton: Button
    private lateinit var groupButton: Button
    private lateinit var labelInput: EditText
    private lateinit var sizeSlider: SeekBar
    private lateinit var opacitySlider: SeekBar
    private lateinit var colorPicker: Spinner
    private lateinit var shapePicker: Spinner
    private lateinit var iconPicker: Spinner
    private lateinit var styleEditingLabel: TextView
    private lateinit var controllerTab: Button
    private lateinit var screenTab: Button
    private lateinit var screenScaleBlock: LinearLayout
    private lateinit var screenScaleLabel: TextView
    private lateinit var screenSizeSlider: SeekBar
    private lateinit var screenFillButton: Button
    private lateinit var mappingPreview: ControllerMappingPreview
    private lateinit var editorComposeRoot: ComposeView
    private var toolbarScreenLabel: TextView? = null
    private var currentScene = HudPreviewScene.CONTROLLER
    private var showScreenTools = false
    private var showScreenControls = false
    private var inspectorCollapsed = false
    private var activeEditorTab by mutableStateOf("Layout")
    private var composeRevision by mutableIntStateOf(0)
    private var selectedPresetIndex = 0
    private var updatingFields = false
    private var selectedId: String? = null
    private var selectedDrawerId: String = "none"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        layoutModel = settings.layout
        hud = TouchHudOverlay(this, layoutModel, settings.stickLocked,
            settings.cameraSensitivityPercent, settings.cameraSensitivityYPercent, editMode = true, drawControllerPreview = true)
        hud.setTouchDragCameraEnabled(settings.touchDragCamera)
        hud.setPreviewScreenScale(settings.displayScalePercent)
        hud.setPreviewStretchToFill(settings.stretchToFill)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(6))
            setBackgroundColor(Color.rgb(23, 27, 35))
        }
        editorPanel = panel
        panel.addView(TextView(this).apply {
            text = "Control layout"
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = "Edit touch controls and presets."
            textSize = 11.5f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(2), 0, dp(2))
        })

        controllerTab = actionButton("Controller") {
            currentScene = HudPreviewScene.CONTROLLER
            showScreenTools = false
            inspectorCollapsed = false
            hud.setPreviewScene(currentScene)
            screenScaleBlock.visibility = View.GONE
            updateSceneTabs(currentScene)
            showEditor()
        }
        screenTab = actionButton("Game screen") {
            currentScene = HudPreviewScene.GAME_SCREEN
            showScreenTools = true
            hud.setPreviewScene(currentScene)
            screenScaleBlock.visibility = View.VISIBLE
            updateSceneTabs(currentScene)
            showEditor()
        }
        controllerMappingBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            AppUi.cardBackground(this, 16f)
        }
        controllerMappingBlock.addView(sectionLabel("Physical controller input"))
        controllerMappingBlock.addView(TextView(this).apply {
            text = "Choose a physical input to see its standard Xbox output. The right joystick sends both analog axes."
            textSize = 13f
            setTextColor(AppUi.textSecondary)
            setPadding(0, dp(2), 0, dp(10))
        })
        controllerInputPicker = Spinner(this).apply {
            adapter = AppUi.spinnerAdapter(this@LayoutEditorActivity, CONTROLLER_BINDINGS.map { it.input })
            AppUi.styleSpinner(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateControllerMapping(position)
                    if (::mappingPreview.isInitialized) mappingPreview.selectInput(CONTROLLER_BINDINGS.getOrNull(position)?.input.orEmpty())
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        controllerMappingBlock.addView(controllerInputPicker, matchWidth())
        val mappingBehaviorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            AppUi.cardBackground(this, 14f)
        }
        mappingBehaviorCard.addView(sectionLabel("Behavior profile"))
        controllerInputDescription = TextView(this).apply {
            textSize = 14f
            setTextColor(AppUi.textPrimary)
            setPadding(0, 0, 0, dp(12))
        }
        mappingBehaviorCard.addView(controllerInputDescription)
        controllerMappingBlock.addView(mappingBehaviorCard, matchWidth())
        val mappingOutputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
            AppUi.cardBackground(this, 14f)
        }
        mappingOutputCard.addView(sectionLabel("Mapped Xbox output"))
        controllerOutputLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(AppUi.textPrimary)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(AppUi.elevatedSurface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(54, 67, 84))
            }
        }
        mappingOutputCard.addView(controllerOutputLabel, matchWidth())
        mappingOutputCard.addView(TextView(this).apply {
            text = "Standard Xbox mapping · passed through while held"
            textSize = 12f
            setTextColor(AppUi.textSecondary)
            setPadding(0, dp(8), 0, dp(4))
        })
        controllerMappingBlock.addView(mappingOutputCard, matchWidth())
        val cameraModeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            AppUi.cardBackground(this, 14f)
        }
        cameraModeCard.addView(sectionLabel("Right camera control mode"))
        cameraModeCard.addView(TextView(this).apply {
            text = "Use touch swipes or the physical controller’s right joystick for camera movement."
            textSize = 13f
            setTextColor(AppUi.textSecondary)
            setPadding(0, dp(2), 0, dp(6))
        })
        touchDragModeButton = actionButton("Touch drag") { setCameraMode(useTouchDrag = true) }
        rightStickModeButton = actionButton("Right joystick") { setCameraMode(useTouchDrag = false) }
        cameraModeCard.addView(horizontalRow(touchDragModeButton, rightStickModeButton))
        controllerMappingBlock.addView(cameraModeCard, matchWidth())
        mappingPreview = ControllerMappingPreview(this, CONTROLLER_BINDINGS.map { it.input }, "Right stick") { input ->
            if (input == null) {
                selectedId = null
                hud.selectControl(null)
                updateControlsPicker()
                syncFields()
                refreshComposeUi()
            } else {
                val index = CONTROLLER_BINDINGS.indexOfFirst { it.input == input }
                val controlId = controllerInputToHudId(input)
                if (index >= 0 && controlId != null) {
                    controllerInputPicker.setSelection(index)
                    selectedId = controlId
                    hud.selectControl(controlId)
                    updateControlsPicker()
                    syncFields()
                    refreshComposeUi()
                }
            }
        }
        workspaceBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            AppUi.cardBackground(this, 14f)
            addView(sectionLabel("Workspace"))
            addView(horizontalRow(controllerTab, screenTab))
        }

        touchControlsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layoutTabContent = editorCard()
        styleTabContent = editorCard()
        actionsTabContent = editorCard()
        editorTabBar = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OmniDeckTheme {
                    EditorTabBar(activeEditorTab, ::selectEditorTab)
                }
            }
        }
        inspectorMenuButton = actionButton("☰") { toggleInspector() }.apply {
            contentDescription = "Collapse editor settings"
            minWidth = dp(44)
            minHeight = dp(48)
            textSize = 16f
        }
        inspectorToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(inspectorMenuButton, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(editorTabBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        touchControlsPanel.addView(layoutTabContent, matchWidth())
        touchControlsPanel.addView(controllerMappingBlock, matchWidth())
        touchControlsPanel.addView(styleTabContent, matchWidth())
        touchControlsPanel.addView(actionsTabContent, matchWidth())
        layoutTabContent.addView(workspaceBlock, matchWidth())

        screenScaleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        screenScaleLabel = TextView(this).apply {
            text = "Displayed game area · ${settings.displayScalePercent}%"
            textSize = 13f
            setTextColor(AppUi.textSecondary)
        }
        screenScaleBlock.addView(screenScaleLabel)
        screenSizeSlider = SeekBar(this).apply {
            max = 50
            progress = settings.displayScalePercent - 50
            AppUi.styleSlider(this)
            var lastScaleTick = progress / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scaleTick = progress / 5
                    if (scaleTick != lastScaleTick) {
                        seekBar?.playSoundEffect(SoundEffectConstants.CLICK)
                        lastScaleTick = scaleTick
                    }
                    updateScreenScale(progress + 50)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    lastScaleTick = (seekBar?.progress ?: progress) / 5
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        screenScaleBlock.addView(screenSizeSlider)

        layoutTabContent.addView(sectionLabel("Presets & configuration"))
        presetPicker = Spinner(this).apply {
            adapter = AppUi.spinnerAdapter(this@LayoutEditorActivity, presetLabels())
            AppUi.styleSpinner(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                private var first = true
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (first) { first = false; return }
                    when (position) {
                        HudLayout.PRESET_DEFAULT, HudLayout.PRESET_COMPACT, HudLayout.PRESET_LEFT_HANDED -> hud.applyPreset(position)
                        else -> settings.loadHudPreset(settings.savedHudPresetNames().getOrNull(position - BUILTIN_PRESET_COUNT).orEmpty())?.let {
                            layoutModel.copyFrom(it)
                            hud.refreshLayout()
                        }
                    }
                    selectedId = null
                    syncFields()
                    updateControlsPicker()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        layoutTabContent.addView(presetPicker, matchWidth())
        layoutTabContent.addView(actionButton("▣  Save current layout as preset") { promptSavePreset() }, matchWidth())

        // Keep the legacy inspector fields initialized for shared state sync; the Compose
        // Layout tab intentionally leaves control management to Actions and the drawer.
        controlsPicker = Spinner(this)
        AppUi.styleSpinner(controlsPicker)
        updateControlsPicker()
        controlsPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedId = HudCatalog.controls.getOrNull(position)?.id
                hud.selectControl(selectedId)
                syncFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        visibilityButton = actionButton("Hide selected") { hud.toggleSelectedVisible(); updateControlsPicker(); syncFields() }
        groupButton = actionButton("Move to More ▾") { hud.toggleSelectedGroup(); updateControlsPicker(); syncFields() }
        moreAnchorButton = actionButton("More button: anchored to screen edge") {
            val locked = hud.toggleMoreAnchorLock()
            moreAnchorButton.text = if (locked) "More button: anchored to screen edge" else "More button: free position"
        }

        styleEditingLabel = TextView(this).apply {
            text = "Editing appearance for:  ${selectedId ?: "Select a control"}"
            textSize = 13f
            setTextColor(AppUi.accent)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            AppUi.cardBackground(this, 10f)
        }
        styleTabContent.addView(styleEditingLabel, matchWidth())
        styleTabContent.addView(sectionLabel("Button text label"))
        labelInput = EditText(this).apply {
            hint = "Button text (blank uses default)"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingFields) hud.setSelectedLabel(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        styleTabContent.addView(labelInput, matchWidth())

        sizeSlider = propertySlider(styleTabContent, "Size scale", 0, 150) { progress ->
            if (!updatingFields) hud.setSelectedScale(.5f + progress / 100f)
        }
        opacitySlider = propertySlider(styleTabContent, "Button opacity", 0, 90) { progress ->
            if (!updatingFields) hud.setSelectedOpacity(progress + 10)
        }

        colorPicker = propertySpinner(styleTabContent, "Button color", listOf("White", "Sky blue", "Mint", "Amber", "Coral", "Violet")) { index ->
            if (!updatingFields) hud.setSelectedColor(COLORS[index])
        }
        shapePicker = propertySpinner(styleTabContent, "Button shape", listOf("Circle", "Square", "Rounded square")) { index ->
            if (!updatingFields) hud.setSelectedShape(index)
        }

        iconPicker = propertySpinner(styleTabContent, "Button icon", listOf("Default label", "More menu", "HUD drawer", "Folder", "Hide HUD", "Gamepad", "Keyboard", "Back", "Camera", "Target", "Star")) { index ->
            if (!updatingFields) hud.setSelectedIconPreset(ICON_PRESET_IDS[index])
        }
        styleTabContent.addView(sectionLabel("Custom icon or artwork"))
        styleTabContent.addView(TextView(this).apply {
            text = "Upload a PNG, JPEG, WebP, or simple SVG."
            textSize = 13f
            setTextColor(AppUi.textSecondary)
        })
        styleTabContent.addView(horizontalRow(
            actionButton("Upload file") { chooseImage() },
            actionButton("Clear artwork") { hud.setSelectedImage(null); refreshComposeUi() },
        ))

        actionsTabContent.addView(sectionLabel("Global layout management"))
        actionsTabContent.addView(TextView(this).apply {
            text = "Bulk control operations and layout reset."
            textSize = 13f
            setTextColor(AppUi.textSecondary)
            setPadding(dp(2), 0, dp(2), dp(10))
        })
        actionsTabContent.addView(horizontalRow(
            actionButton("◉  Hide all controls") { hud.setAllVisible(false); updateControlsPicker(); syncFields() },
            actionButton("◉  Show all controls") { hud.setAllVisible(true); updateControlsPicker(); syncFields() },
        ))
        actionsTabContent.addView(actionButton("↶  Reset layout to default") {
            hud.resetLayout(); selectedId = null; updateControlsPicker(); syncFields()
        })

        inspectorScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            panel.addView(touchControlsPanel, matchWidth())
            addView(panel)
        }
        inspectorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.rgb(15, 20, 30))
            addView(inspectorToolbar, matchWidth())
            addView(inspectorScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actionButton("✓  Save layout settings") {
                settings.layout = layoutModel
                finish()
            }, matchWidth())
        }
        selectEditorTab("Layout")
        hud.onSelectionChanged = { id ->
            if (id != selectedId) {
                selectedId = id
                updateControlsPicker()
                syncFields()
            }
        }
        hud.onPreviewScreenScaleChanged = { updateScreenScale(it) }

        showEditor()
        controllerInputPicker.setSelection(CONTROLLER_BINDINGS.indexOfFirst { it.input == "Right stick" }.coerceAtLeast(0))
        updateControllerMapping(controllerInputPicker.selectedItemPosition)
        updateCameraModeButtons()
        syncFields()
        updateSceneTabs(currentScene)
        hideSystemBars()
    }

    private fun updateSceneTabs(selected: HudPreviewScene) {
        AppUi.styleButton(controllerTab, selected == HudPreviewScene.CONTROLLER)
        AppUi.styleButton(screenTab, selected == HudPreviewScene.GAME_SCREEN)
    }

    private fun controllerInputToHudId(input: String): String? = when (input) {
        "Left trigger" -> "lt"
        "Left bumper" -> "lb"
        "Left stick" -> "stick"
        "D-pad Up" -> "dup"
        "D-pad Down" -> "ddown"
        "D-pad Left" -> "dleft"
        "D-pad Right" -> "dright"
        "View" -> "select"
        "Right trigger" -> "rt"
        "Right bumper" -> "rb"
        "Right stick" -> "rightStick"
        "Y" -> "y"
        "X" -> "x"
        "B" -> "b"
        "A" -> "a"
        "Menu" -> "start"
        "Guide" -> "guide"
        else -> null
    }

    private fun selectEditorTab(tab: String) {
        activeEditorTab = tab
        refreshComposeUi()
        if (!::layoutTabContent.isInitialized) return
        layoutTabContent.visibility = if (tab == "Layout") View.VISIBLE else View.GONE
        controllerMappingBlock.visibility = if (tab == "Mapping") View.VISIBLE else View.GONE
        styleTabContent.visibility = if (tab == "Style") View.VISIBLE else View.GONE
        actionsTabContent.visibility = if (tab == "Actions") View.VISIBLE else View.GONE
    }

    private fun editorCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(8))
        AppUi.cardBackground(this, 14f)
    }

    private fun updateScreenScale(percent: Int) {
        settings.displayScalePercent = percent.coerceIn(50, 100)
        screenScaleLabel.text = "Displayed game area · ${settings.displayScalePercent}%"
        screenSizeSlider.progress = settings.displayScalePercent - 50
        toolbarScreenLabel?.text = "Screen  ${settings.displayScalePercent}%"
        hud.setPreviewScreenScale(settings.displayScalePercent)
        hud.setPreviewStretchToFill(settings.stretchToFill)
        refreshComposeUi()
        hud.setPreviewStretchToFill(settings.stretchToFill)
    }

    private fun showEditor() {
        if (!::editorComposeRoot.isInitialized) {
            editorComposeRoot = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val state = remember(composeRevision) { editorUiState() }
                    OmniDeckTheme {
                        LayoutEditorScreen(state, editorActions(), mappingPreview, hud)
                    }
                }
            }
        }
        (editorComposeRoot.parent as? ViewGroup)?.removeView(editorComposeRoot)
        setContentView(editorComposeRoot)
        hideSystemBars()
    }

    private fun editorUiState(): LayoutEditorUiState {
        val placement = selectedId?.let(layoutModel::placement)
        val controls = HudCatalog.controls.filter { spec ->
            !spec.id.startsWith("drawer") || layoutModel.placement(spec.id).customLabel != null
        }.map { spec ->
            val place = layoutModel.placement(spec.id)
            val label = spec.label.ifBlank {
                when {
                    spec.id == "stick" -> "Left stick"
                    spec.id == "rightStick" -> "Right joystick"
                    spec.id == "more" -> "More menu"
                    spec.id == "hud" -> "Hide HUD"
                    spec.id.startsWith("drawer") -> layoutModel.drawerName(spec.id)
                    else -> spec.id
                }
            }
            val drawerId = place.drawerId ?: if (place.inMore) "more" else null
            val section = drawerId?.let(layoutModel::drawerName) ?: "Main"
            EditorOption(spec.id, "$section · $label${if (place.visible) "" else " · hidden"}", drawerId)
        }
        return LayoutEditorUiState(
            landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            gameScreen = currentScene == HudPreviewScene.GAME_SCREEN,
            showTools = showScreenTools,
            showControls = showScreenControls,
            collapsed = inspectorCollapsed,
            tab = activeEditorTab,
            controlOptions = controls,
            drawers = listOf(EditorOption("none", "None · Main HUD")) + layoutModel.drawerIds().map { EditorOption(it, layoutModel.drawerName(it)) },
            selectedDrawerId = selectedDrawerId.takeIf { it == "none" || it in layoutModel.drawerIds() } ?: "none",
            selectedControl = selectedId,
            presets = presetLabels(),
            selectedPreset = selectedPresetIndex.coerceIn(0, presetLabels().lastIndex),
            displayScale = settings.displayScalePercent,
            stretchToFill = settings.stretchToFill,
            selectedVisible = placement?.visible == true,
            selectedInMore = placement?.inMore == true,
            moreLocked = hud.moreAnchorLocked(),
            label = placement?.customLabel.orEmpty(),
            scale = placement?.scale ?: 1f,
            opacity = placement?.opacity ?: 100,
            colorIndex = COLORS.indexOf(placement?.color ?: COLORS[0]).coerceAtLeast(0),
            shapeIndex = (placement?.shape ?: 0).coerceIn(0, 2),
            iconIndex = ICON_PRESET_IDS.indexOf(hud.selectedIconPreset()).coerceAtLeast(0),
            inputOptions = CONTROLLER_BINDINGS.map { it.input },
            selectedInput = if (::controllerInputPicker.isInitialized) controllerInputPicker.selectedItemPosition.coerceAtLeast(0) else 0,
            inputDescription = if (::controllerInputDescription.isInitialized) controllerInputDescription.text.toString() else "Select a physical input to inspect its behavior.",
            mappedOutput = if (::controllerOutputLabel.isInitialized) controllerOutputLabel.text.toString() else "Standard Xbox output",
            touchDragCamera = settings.touchDragCamera,
        )
    }

    private fun editorActions() = LayoutEditorActions(
        onTab = ::selectEditorTab,
        onScene = { gameScreen ->
            currentScene = if (gameScreen) HudPreviewScene.GAME_SCREEN else HudPreviewScene.CONTROLLER
            hud.setPreviewScene(currentScene)
            if (gameScreen && activeEditorTab == "Mapping") activeEditorTab = "Layout"
            if (!gameScreen) inspectorCollapsed = false
            showScreenTools = gameScreen
            showScreenControls = false
            inspectorCollapsed = false
            refreshComposeUi()
        },
        onTools = {
            showScreenTools = it
            if (it) inspectorCollapsed = false else {
                showScreenControls = false
                inspectorCollapsed = true
            }
            refreshComposeUi()
        },
        onControls = { open ->
            showScreenControls = open
            showScreenTools = true
            if (open) selectEditorTab("Layout") else refreshComposeUi()
        },
        onCollapse = ::toggleInspector,
        onSave = { settings.layout = layoutModel; finish() },
        onPreset = { index ->
            selectedPresetIndex = index
            when (index) {
                HudLayout.PRESET_DEFAULT, HudLayout.PRESET_COMPACT, HudLayout.PRESET_LEFT_HANDED -> hud.applyPreset(index)
                else -> settings.loadHudPreset(settings.savedHudPresetNames().getOrNull(index - BUILTIN_PRESET_COUNT).orEmpty())?.let {
                    layoutModel.copyFrom(it)
                    hud.refreshLayout()
                }
            }
            selectedId = null
            updateControlsPicker()
            syncFields()
        },
        onSavePreset = ::promptSavePreset,
        onControl = { id ->
            selectedId = id
            hud.selectControl(id)
            updateControlsPicker()
            syncFields()
        },
        onAddControl = ::addHiddenControl,
        onCreateDrawer = {
            val id = layoutModel.createDrawer()
            if (id == null) {
                Toast.makeText(this, "You have reached the drawer limit", Toast.LENGTH_SHORT).show()
            } else {
                // Creating a folder does not silently make it the destination for new controls.
                selectedDrawerId = "none"
                selectedId = id
                hud.refreshLayout()
                hud.selectControl(id)
                refreshComposeUi()
                Toast.makeText(this, "Drawer created. Choose it under Add controls to when you want to use it.", Toast.LENGTH_SHORT).show()
            }
        },
        onDeleteDrawer = {
            val drawerId = selectedDrawerId
            if (layoutModel.deleteDrawer(drawerId)) {
                selectedDrawerId = "none"
                if (selectedId == drawerId) {
                    selectedId = null
                    hud.selectControl(null)
                }
                hud.refreshLayout()
                updateControlsPicker()
                syncFields()
                refreshComposeUi()
                Toast.makeText(this, "Drawer deleted; its controls are back on the main HUD", Toast.LENGTH_SHORT).show()
            }
        },
        onSelectDrawer = { index ->
            selectedDrawerId = if (index == 0) "none" else layoutModel.drawerIds().getOrNull(index - 1) ?: "none"
            refreshComposeUi()
        },
        onMoveToDrawer = { id ->
            val placement = layoutModel.placement(id)
            val currentDrawer = placement.drawerId ?: if (placement.inMore) "more" else null
            val destination = when {
                selectedDrawerId == "none" -> null
                currentDrawer == selectedDrawerId -> null
                else -> selectedDrawerId
            }
            selectedId = id
            hud.selectControl(id)
            hud.moveSelectedToDrawer(destination)
            updateControlsPicker()
            syncFields()
            refreshComposeUi()
        },
        onVisibility = { hud.toggleSelectedVisible(); updateControlsPicker(); syncFields(); refreshComposeUi() },
        onGroup = { hud.toggleSelectedGroup(); updateControlsPicker(); syncFields(); refreshComposeUi() },
        onAnchor = { hud.toggleMoreAnchorLock(); refreshComposeUi() },
        onLabel = { hud.setSelectedLabel(it); refreshComposeUi() },
        onScale = { hud.setSelectedScale(it); refreshComposeUi() },
        onOpacity = { hud.setSelectedOpacity(it); refreshComposeUi() },
        onColor = { COLORS.getOrNull(it)?.let(hud::setSelectedColor); refreshComposeUi() },
        onShape = { hud.setSelectedShape(it); refreshComposeUi() },
        onIcon = { ICON_PRESET_IDS.getOrNull(it)?.let(hud::setSelectedIconPreset); refreshComposeUi() },
        onChooseImage = ::chooseImage,
        onClearImage = { hud.setSelectedImage(null); refreshComposeUi() },
        onHideAll = { hud.setAllVisible(false); updateControlsPicker(); syncFields(); refreshComposeUi() },
        onShowAll = { hud.setAllVisible(true); updateControlsPicker(); syncFields(); refreshComposeUi() },
        onReset = { hud.resetLayout(); selectedId = null; selectedDrawerId = "none"; updateControlsPicker(); syncFields(); refreshComposeUi() },
        onInput = { index ->
            if (index in CONTROLLER_BINDINGS.indices) {
                controllerInputPicker.setSelection(index)
                updateControllerMapping(index)
                refreshComposeUi()
            }
        },
        onCameraMode = { setCameraMode(it) },
        onDisplayScale = ::updateScreenScale,
        onStretch = {
            settings.stretchToFill = !settings.stretchToFill
            hud.setPreviewStretchToFill(settings.stretchToFill)
            refreshComposeUi()
        },
    )

    private fun refreshComposeUi() {
        composeRevision++
    }

    private fun toggleInspector() {
        inspectorCollapsed = !inspectorCollapsed
        refreshComposeUi()
    }

    private fun setCameraMode(useTouchDrag: Boolean) {
        settings.touchDragCamera = useTouchDrag
        hud.setTouchDragCameraEnabled(useTouchDrag)
        updateCameraModeButtons()
        refreshComposeUi()
    }

    private fun updateCameraModeButtons() {
        if (!::touchDragModeButton.isInitialized || !::rightStickModeButton.isInitialized) return
        AppUi.styleButton(touchDragModeButton, settings.touchDragCamera)
        AppUi.styleButton(rightStickModeButton, !settings.touchDragCamera)
    }

    private fun updateControllerMapping(position: Int) {
        val mapping = CONTROLLER_BINDINGS.getOrNull(position) ?: return
        controllerInputDescription.text = mapping.behavior
        controllerOutputLabel.text = mapping.output
        if (::mappingPreview.isInitialized) mappingPreview.selectInput(mapping.input)
        refreshComposeUi()
    }

    private fun screenToolsDrawer(): View {
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            AppUi.cardBackground(this, 20f)
        }
        drawer.addView(horizontalRow(
            TextView(this).apply {
                text = "Game screen tools"
                textSize = 15f
                setTextColor(AppUi.textPrimary)
                gravity = Gravity.CENTER_VERTICAL
            },
            drawerButton("Close") { showScreenTools = false; showScreenControls = false; showEditor() },
        ))
        drawer.addView(horizontalRow(
            drawerButton("Controller") {
            currentScene = HudPreviewScene.CONTROLLER
            showScreenTools = false
            hud.setPreviewScene(currentScene)
            screenScaleBlock.visibility = View.GONE
            updateSceneTabs(currentScene)
            showEditor()
            },
            drawerButton("Game screen") { currentScene = HudPreviewScene.GAME_SCREEN; hud.setPreviewScene(currentScene); updateSceneTabs(currentScene); showEditor() },
        ))
        if (showScreenControls) {
            drawer.addView(drawerButton("← Screen options") {
                showScreenControls = false
                showEditor()
            }, matchWidth())
            (editorTabBar.parent as? ViewGroup)?.removeView(editorTabBar)
            drawer.addView(editorTabBar, matchWidth())
            (touchControlsPanel.parent as? ViewGroup)?.removeView(touchControlsPanel)
            val controlScroll = ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(touchControlsPanel)
            }
            drawer.addView(controlScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            (screenScaleBlock.parent as? ViewGroup)?.removeView(screenScaleBlock)
            drawer.addView(screenScaleBlock, matchWidth())
            screenFillButton = drawerButton(if (settings.stretchToFill) "Fill ✓" else "Fill") {
                settings.stretchToFill = !settings.stretchToFill
                hud.setPreviewStretchToFill(settings.stretchToFill)
                screenFillButton.text = if (settings.stretchToFill) "Fill ✓" else "Fill"
                AppUi.styleButton(screenFillButton, settings.stretchToFill)
            }
            drawer.addView(screenFillButton, matchWidth())
            drawer.addView(drawerButton("Controls") {
                showScreenControls = true
                selectEditorTab("Layout")
                showEditor()
            }, matchWidth())
            drawer.addView(TextView(this).apply {
                text = "Resize the game display here. Open Controls to arrange and customize touch buttons."
                textSize = 13f
                setTextColor(AppUi.textSecondary)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        drawer.addView(drawerButton("Done") {
            settings.layout = layoutModel
            finish()
        }, matchWidth())
        return drawer
    }

    /** Compact drawer styling reduces visual weight while preserving 44dp touch targets. */
    private fun drawerButton(label: String, action: () -> Unit) = actionButton(label, action).apply {
        textSize = 13f
        minHeight = dp(44)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshComposeUi()
        if (::editorComposeRoot.isInitialized) editorComposeRoot.requestLayout()
        hideSystemBars()
    }

    private fun presetLabels() = listOf("Preset: Full", "Preset: Compact", "Preset: Left-handed") +
        settings.savedHudPresetNames().map { "Saved: $it" }

    private fun addHiddenControl() {
        val hidden = HudCatalog.controls.filter { !it.id.startsWith("drawer") && !layoutModel.placement(it.id).visible && layoutModel.canHide(it.id) }
        if (hidden.isEmpty()) {
            Toast.makeText(this, "All available controls are already on the layout", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add a control")
            .setItems(hidden.map { it.label.ifBlank { "Left stick" } }.toTypedArray()) { _, index ->
                selectedId = hidden[index].id
                layoutModel.placement(selectedId!!).visible = true
                hud.selectControl(selectedId)
                updateControlsPicker()
                syncFields()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptSavePreset() {
        val input = EditText(this).apply {
            hint = "Preset name"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Save layout preset")
            .setMessage("Save the current button positions and styles for later.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(32)
                if (name.isEmpty()) return@setPositiveButton
                settings.saveHudPreset(name, layoutModel)
                selectedPresetIndex = BUILTIN_PRESET_COUNT + settings.savedHudPresetNames().indexOf(name).coerceAtLeast(0)
                presetPicker.adapter = AppUi.spinnerAdapter(this, presetLabels())
                presetPicker.setSelection(selectedPresetIndex)
                refreshComposeUi()
            }
            .show()
    }

    private fun updateControlsPicker() {
        if (!::controlsPicker.isInitialized) return
        val rows = HudCatalog.controls.map { spec ->
            val placement = layoutModel.placement(spec.id)
            val name = spec.label.ifBlank {
                when {
                    spec.id == "stick" -> "Left stick"
                    spec.id == "rightStick" -> "Right joystick"
                    spec.id == "more" -> "More menu"
                    spec.id == "hud" -> "Hide HUD"
                    spec.id.startsWith("drawer") -> layoutModel.drawerName(spec.id)
                    else -> spec.id
                }
            }
            val drawerId = placement.drawerId ?: if (placement.inMore) "more" else null
            "${drawerId?.let(layoutModel::drawerName) ?: "Main"}  ·  $name${if (placement.visible) "" else "  (hidden)"}"
        }
        controlsPicker.adapter = AppUi.spinnerAdapter(this, rows)
        val index = HudCatalog.controls.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        controlsPicker.setSelection(index)
    }

    private fun syncFields() {
        if (!::hud.isInitialized) return
        val id = selectedId
        val enabled = id != null
        val placement = id?.let(layoutModel::placement)
        updatingFields = true
        if (::styleEditingLabel.isInitialized) {
            styleEditingLabel.text = "Editing appearance for:  ${id ?: "Select a control"}"
        }
        visibilityButton.isEnabled = enabled
        visibilityButton.text = if (placement?.visible == false) "Show selected" else "Hide selected"
        groupButton.isEnabled = enabled && id != "stick" && id != "more" && id != "hud"
        groupButton.text = if (placement?.inMore == true) "Move to Main ↑" else "Move to More ▾"
        moreAnchorButton.isEnabled = id == "more"
        moreAnchorButton.text = if (hud.moreAnchorLocked()) "More button: anchored to screen edge" else "More button: free position"
        labelInput.isEnabled = enabled && id != "stick"
        labelInput.hint = if (id == "more") "Menu button name (optional)" else "Button text (blank uses icon/default)"
        labelInput.setText(placement?.customLabel.orEmpty())
        sizeSlider.isEnabled = enabled
        sizeSlider.progress = (((placement?.scale ?: 1f) - .5f) * 100).toInt().coerceIn(0, 150)
        opacitySlider.isEnabled = enabled
        opacitySlider.progress = ((placement?.opacity ?: 100) - 10).coerceIn(0, 90)
        colorPicker.isEnabled = enabled && id != "stick"
        colorPicker.setSelection(COLORS.indexOf(placement?.color ?: COLORS[0]).coerceAtLeast(0))
        shapePicker.isEnabled = enabled && id != "stick"
        shapePicker.setSelection((placement?.shape ?: 0).coerceIn(0, 2))
        iconPicker.isEnabled = enabled && id != "stick"
        iconPicker.setSelection(ICON_PRESET_IDS.indexOf(hud.selectedIconPreset()).coerceAtLeast(0))
        updatingFields = false
        refreshComposeUi()
    }

    private fun propertySlider(parent: LinearLayout, title: String, min: Int, max: Int, onChange: (Int) -> Unit): SeekBar {
        parent.addView(TextView(this).apply { text = title; setTextColor(Color.LTGRAY); textSize = 13f })
        return SeekBar(this).apply {
            this.max = max - min
            AppUi.styleSlider(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress + min)
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            parent.addView(this, matchWidth())
        }
    }

    private fun propertySpinner(parent: LinearLayout, title: String, labels: List<String>, onPick: (Int) -> Unit): Spinner {
        parent.addView(TextView(this).apply { text = title; setTextColor(Color.LTGRAY); textSize = 13f })
        return Spinner(this).apply {
            adapter = AppUi.spinnerAdapter(this@LayoutEditorActivity, labels)
            AppUi.styleSpinner(this)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            parent.addView(this, matchWidth())
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        AppUi.styleButton(this, label == "Save layout" || label == "Done")
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun sectionLabel(title: String) = TextView(this).apply {
        text = title.uppercase()
        textSize = 11f
        letterSpacing = .08f
        setTextColor(AppUi.accent)
        setPadding(dp(2), dp(8), dp(2), dp(2))
    }

    private fun horizontalRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { view ->
            addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4); bottomMargin = dp(4)
    }
    private fun toolbarChildParams(weight: Float = 0f) = LinearLayout.LayoutParams(
        if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        weight,
    ).apply { leftMargin = dp(3); rightMargin = dp(3) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun chooseImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/svg+xml"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_IMAGE)
    }

    @Deprecated("Uses the platform document picker for persistent image access")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(uri)
        val mime = contentResolver.getType(uri).orEmpty().lowercase()
        if (mime.contains("svg") || name.endsWith(".svg", ignoreCase = true)) {
            val svg = readSvg(uri)
            if (svg == null) {
                Toast.makeText(this, "SVG file is too large or could not be read (16 KB maximum).", Toast.LENGTH_LONG).show()
            } else if (hud.setSelectedCustomSvg(svg)) {
                Toast.makeText(this, "SVG icon applied", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "This SVG uses unsupported elements. Upload a simple path-based SVG.", Toast.LENGTH_LONG).show()
            }
        } else if (mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
            hud.setSelectedImage(uri.toString())
            Toast.makeText(this, "Image applied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Choose a PNG, JPEG, WebP, or SVG file.", Toast.LENGTH_LONG).show()
        }
        refreshComposeUi()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun readSvg(uri: Uri): String? = runCatching {
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(2048)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_SVG_BYTES) return null
                output.write(buffer, 0, count)
            }
        } ?: return null
        String(output.toByteArray(), Charsets.UTF_8)
    }.getOrNull()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private companion object {
        data class ControllerBinding(val input: String, val output: String, val behavior: String)
        val CONTROLLER_BINDINGS = listOf(
            ControllerBinding("Left trigger", "Xbox LT · analog trigger", "Trigger pressure is passed through."),
            ControllerBinding("Left bumper", "Xbox LB", "Button state remains active while pressed."),
            ControllerBinding("Left stick", "Left stick axes · X / Y", "Analog movement while the stick is held."),
            ControllerBinding("D-pad Up", "Xbox D-pad Up", "Direction is passed through while pressed."),
            ControllerBinding("D-pad Down", "Xbox D-pad Down", "Direction is passed through while pressed."),
            ControllerBinding("D-pad Left", "Xbox D-pad Left", "Direction is passed through while pressed."),
            ControllerBinding("D-pad Right", "Xbox D-pad Right", "Direction is passed through while pressed."),
            ControllerBinding("View", "Xbox View", "Button state remains active while pressed."),
            ControllerBinding("Right trigger", "Xbox RT · analog trigger", "Trigger pressure is passed through."),
            ControllerBinding("Right bumper", "Xbox RB", "Button state remains active while pressed."),
            ControllerBinding("Right stick", "Right stick axes · RX / RY", "Analog camera movement while the stick is held."),
            ControllerBinding("Y", "Xbox Y", "Button state remains active while pressed."),
            ControllerBinding("X", "Xbox X", "Button state remains active while pressed."),
            ControllerBinding("B", "Xbox B", "Button state remains active while pressed."),
            ControllerBinding("A", "Xbox A", "Button state remains active while pressed."),
            ControllerBinding("Menu", "Xbox Menu", "Button state remains active while pressed."),
            ControllerBinding("Guide", "Xbox Guide", "Button state remains active while pressed."),
        )
        val ICON_PRESET_IDS = listOf("default", "more", "drawer", "folder", "eye", "gamepad", "keyboard", "back", "camera", "target", "star")
        const val REQUEST_IMAGE = 41
        const val MAX_SVG_BYTES = 16 * 1024
        const val BUILTIN_PRESET_COUNT = 3
        val COLORS = listOf(Color.WHITE, Color.rgb(90, 190, 255), Color.rgb(90, 220, 170),
            Color.rgb(255, 190, 70), Color.rgb(255, 110, 100), Color.rgb(180, 130, 255))
    }
}
