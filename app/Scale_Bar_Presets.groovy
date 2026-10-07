#@Context context

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import ij.IJ
import ij.ImagePlus
import ij.WindowManager

import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.ListSelectionEvent
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.List
import java.util.Collections
import java.util.prefs.Preferences
import java.time.Instant

/**
 * Fiji script: a modeless, persistent preset manager for ImageJ scale bars.
 * Install under Fiji.app/scripts/Plugins/ and run Plugins > Scale Bar Presets.
 */

final String PREF_KEY = 'presets.v1'
final String PACK_FORMAT = 'FijiCal Lite Preset Pack'
final int PACK_VERSION = 1
def prefs = binding.hasVariable('fijicalPreferences') ? binding.getVariable('fijicalPreferences') :
    Preferences.userNodeForPackage(getClass()).node('scale-bar-presets')
final File portablePresetFile = binding.hasVariable('fijicalPresetFile') ? (binding.getVariable('fijicalPresetFile') as File) : null

final List<Map> defaults = [
    [name: 'Publication · 10 µm', pixelDistance: 1d, knownDistance: 1d, scaleUnit: 'µm', pixelAspect: 1d, globalScale: false, width: 10d, height: 4, font: 18, color: 'White', background: 'None', location: 'Lower Right', bold: true, serif: false, hideText: false, overlay: true],
    [name: 'Overview · 100 µm', pixelDistance: 1d, knownDistance: 1d, scaleUnit: 'µm', pixelAspect: 1d, globalScale: false, width: 100d, height: 6, font: 22, color: 'White', background: 'Black', location: 'Lower Right', bold: true, serif: false, hideText: false, overlay: true],
    [name: 'Minimal · 1 mm', pixelDistance: 1d, knownDistance: 1d, scaleUnit: 'mm', pixelAspect: 1d, globalScale: false, width: 1d, height: 5, font: 18, color: 'Black', background: 'White', location: 'Lower Left', bold: false, serif: false, hideText: false, overlay: true]
]

Closure<List<Map>> loadPresets = {
    try {
        String raw = portablePresetFile?.isFile() ? portablePresetFile.getText('UTF-8') : prefs.get(PREF_KEY, '')
        if (!raw) return defaults.collect { new LinkedHashMap(it) }
        def parsed = new JsonSlurper().parseText(raw)
        if (parsed instanceof Map) parsed = parsed.presets
        if (!(parsed instanceof List)) return defaults.collect { new LinkedHashMap(it) }
        return parsed.collect { entry ->
            Map p = new LinkedHashMap(entry as Map)
            if (!p.containsKey('pixelDistance')) p.pixelDistance = 1d
            if (!p.containsKey('knownDistance')) p.knownDistance = 1d
            if (!p.containsKey('scaleUnit')) p.scaleUnit = p.expectedUnit ?: 'pixel'
            if (!p.containsKey('calibrationUnit')) p.calibrationUnit = p.scaleUnit
            if (!p.containsKey('pixelAspect')) p.pixelAspect = 1d
            if (!p.containsKey('globalScale')) p.globalScale = false
            p.remove('expectedUnit')
            p
        }
    } catch (Exception ignored) {
        return defaults.collect { new LinkedHashMap(it) }
    }
}

List<Map> presets = loadPresets()
Closure savePresets = {
    if (portablePresetFile) {
        def document=[format:'FijiCal Lite Presets',version:1,presets:presets]
        File temporary=new File(portablePresetFile.parentFile,portablePresetFile.name+'.tmp')
        temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(document))+'\n','UTF-8')
        try {
            java.nio.file.Files.move(temporary.toPath(),portablePresetFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            java.nio.file.Files.move(temporary.toPath(),portablePresetFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } else prefs.put(PREF_KEY,JsonOutput.toJson(presets))
}

UIManager.put('Button.arc', 10)
UIManager.put('Component.arc', 10)
UIManager.put('TextComponent.arc', 8)

def externalOwner = binding.hasVariable('fijicalOwner') ? binding.getVariable('fijicalOwner') : null
def frame = externalOwner instanceof Frame ?
    new JDialog(externalOwner as Frame, 'Preset Manager — FijiCal Lite', Dialog.ModalityType.APPLICATION_MODAL) :
    new JFrame('Scale Bar Presets')
frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
frame.minimumSize = new Dimension(920, 720)

Color ink = new Color(232, 238, 240)
Color muted = new Color(166, 178, 184)
Color surface = new Color(27, 31, 34)
Color panel = new Color(34, 39, 43)
Color input = new Color(42, 48, 52)
Color border = new Color(72, 82, 88)
Color accent = new Color(20, 112, 103)

['Panel.background': surface, 'OptionPane.background': surface, 'OptionPane.messageForeground': ink,
 'FileChooser.background': surface, 'FileChooser.foreground': ink, 'List.background': input,
 'List.foreground': ink, 'TextField.background': input, 'TextField.foreground': ink,
 'ComboBox.background': input, 'ComboBox.foreground': ink, 'Spinner.background': input,
 'Button.background': panel, 'Button.foreground': ink].each { String key, Object value -> UIManager.put(key, value) }

Closure styleControl = { JComponent component ->
    component.background = input
    component.foreground = ink
    component.border = BorderFactory.createLineBorder(border, 1)
    if (component instanceof JSpinner) {
        def editor = (component as JSpinner).editor
        if (editor instanceof JSpinner.DefaultEditor) {
            def text = (editor as JSpinner.DefaultEditor).textField
            text.background = input; text.foreground = ink; text.caretColor = ink; text.border = null
        }
    }
}
Closure styleButton = { AbstractButton button, boolean primary=false ->
    button.background = primary ? accent : panel
    button.foreground = ink
    button.opaque = true; button.contentAreaFilled = true; button.focusPainted = false
    button.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? accent.darker() : border, 1), new EmptyBorder(6, 10, 6, 10))
}

JPanel root = new JPanel(new BorderLayout(18, 18))
root.border = new EmptyBorder(18, 18, 18, 18)
root.background = surface
frame.contentPane = root

JLabel title = new JLabel('Scale Bar Presets')
title.font = title.font.deriveFont(Font.BOLD, 24f)
title.foreground = ink
JLabel subtitle = new JLabel('Calibrated bars without the menu archaeology')
subtitle.foreground = muted
JPanel heading = new JPanel()
heading.opaque = false
heading.layout = new BoxLayout(heading, BoxLayout.Y_AXIS)
heading.add(title)
heading.add(Box.createVerticalStrut(3))
heading.add(subtitle)
root.add(heading, BorderLayout.NORTH)

DefaultListModel<String> presetModel = new DefaultListModel<>()
JList<String> presetList = new JList<>(presetModel)
presetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
presetList.fixedCellHeight = 36
presetList.border = new EmptyBorder(6, 6, 6, 6)
presetList.background = input
presetList.foreground = ink
presetList.selectionBackground = accent
presetList.selectionForeground = Color.WHITE
final String ALL_COLLECTIONS = 'All collections'
JComboBox<String> collectionFilter = new JComboBox<>([ALL_COLLECTIONS] as String[])
collectionFilter.toolTipText = 'Show every preset or only presets belonging to one microscope collection.'
List<Integer> visiblePresetIndices = []
boolean rebuildingPresetModel = false

JButton addButton = new JButton('+ New')
JButton renameButton = new JButton('Rename…')
JButton deleteButton = new JButton('Delete')
JButton moveUpButton = new JButton('Move up')
JButton moveDownButton = new JButton('Move down')
JButton detailsButton = new JButton('Details…')
JButton sortButton = new JButton('Sort presets…')
JButton renameCollectionButton = new JButton('Rename collection…')
JButton importButton = new JButton('Import pack…')
JButton exportButton = new JButton('Export pack…')
JPanel listButtons = new JPanel(new GridLayout(0, 2, 8, 8))
listButtons.opaque = false
listButtons.add(addButton)
listButtons.add(renameButton)
listButtons.add(deleteButton)
listButtons.add(detailsButton)
listButtons.add(moveUpButton)
listButtons.add(moveDownButton)
listButtons.add(sortButton)
listButtons.add(renameCollectionButton)
listButtons.add(importButton)
listButtons.add(exportButton)

JPanel left = new JPanel(new BorderLayout(0, 10))
left.background = panel
left.border = BorderFactory.createCompoundBorder(
    BorderFactory.createLineBorder(border),
    new EmptyBorder(12, 12, 12, 12)
)
JLabel presetHeading = new JLabel('SAVED PRESETS')
presetHeading.font = presetHeading.font.deriveFont(Font.BOLD, 11f)
presetHeading.foreground = muted
JPanel presetHeader = new JPanel()
presetHeader.opaque = false
presetHeader.layout = new BoxLayout(presetHeader, BoxLayout.Y_AXIS)
presetHeading.alignmentX = Component.LEFT_ALIGNMENT
collectionFilter.alignmentX = Component.LEFT_ALIGNMENT
collectionFilter.maximumSize = new Dimension(Integer.MAX_VALUE, 30)
presetHeader.add(presetHeading)
presetHeader.add(Box.createVerticalStrut(7))
presetHeader.add(collectionFilter)
left.add(presetHeader, BorderLayout.NORTH)
JScrollPane presetScroll = new JScrollPane(presetList)
presetScroll.background = panel; presetScroll.viewport.background = input; presetScroll.border = BorderFactory.createLineBorder(border)
left.add(presetScroll, BorderLayout.CENTER)
left.add(listButtons, BorderLayout.SOUTH)
left.preferredSize = new Dimension(285, 500)
root.add(left, BorderLayout.WEST)

JTextField nameField = new JTextField()
JSpinner widthField = new JSpinner(new SpinnerNumberModel(10d, 0.000001d, 1000000d, 1d))
JTextField expectedUnitField = new JTextField('µm')
JTextField scaleBarUnitField = new JTextField('µm')
JSpinner pixelDistanceField = new JSpinner(new SpinnerNumberModel(1d, 0.000001d, 1000000000d, 1d))
JSpinner knownDistanceField = new JSpinner(new SpinnerNumberModel(1d, 0.000001d, 1000000000d, 1d))
JSpinner pixelAspectField = new JSpinner(new SpinnerNumberModel(1d, 0.000001d, 1000000d, 0.1d))
JSpinner heightField = new JSpinner(new SpinnerNumberModel(4, 1, 100, 1))
JSpinner fontField = new JSpinner(new SpinnerNumberModel(18, 1, 200, 1))
JComboBox<String> colorField = new JComboBox<>(['White', 'Black', 'Light Gray', 'Gray', 'Dark Gray', 'Red', 'Green', 'Blue', 'Yellow', 'Cyan', 'Magenta'] as String[])
JComboBox<String> backgroundField = new JComboBox<>(['None', 'White', 'Black', 'Light Gray', 'Gray', 'Dark Gray', 'Red', 'Green', 'Blue', 'Yellow', 'Cyan', 'Magenta'] as String[])
JComboBox<String> locationField = new JComboBox<>(['Lower Right', 'Lower Left', 'Upper Right', 'Upper Left', 'At Selection'] as String[])
def dropdownBorder = BorderFactory.createLineBorder(border, 1)
[colorField, backgroundField, locationField].each {
    it.border = dropdownBorder
    int dropdownWidth = it.preferredSize.width as int
    int dropdownHeight = it.preferredSize.height < 30 ? 30 : (it.preferredSize.height as int)
    it.preferredSize = new Dimension(dropdownWidth, dropdownHeight)
}
JCheckBox boldField = new JCheckBox('Bold label')
JCheckBox serifField = new JCheckBox('Serif type')
JCheckBox hideTextField = new JCheckBox('Bar only — hide label')
JCheckBox overlayField = new JCheckBox('Keep as editable overlay', true)
JCheckBox globalScaleField = new JCheckBox('Global calibration (all open images)')

[nameField, expectedUnitField, scaleBarUnitField, pixelDistanceField, knownDistanceField, pixelAspectField, widthField, heightField, fontField,
 colorField, backgroundField, locationField, collectionFilter].each { styleControl(it as JComponent) }

JPanel form = new JPanel(new GridBagLayout())
form.background = panel
form.border = BorderFactory.createCompoundBorder(
    BorderFactory.createLineBorder(border),
    new EmptyBorder(16, 18, 16, 18)
)
GridBagConstraints gc = new GridBagConstraints()
gc.fill = GridBagConstraints.HORIZONTAL
gc.weightx = 1
gc.insets = new Insets(5, 5, 5, 5)

Closure addField = { int row, int col, String label, JComponent component ->
    gc.gridx = col
    gc.gridy = row * 2
    gc.gridwidth = 1
    gc.weighty = 0
    JLabel l = new JLabel(label)
    l.foreground = muted
    l.font = l.font.deriveFont(Font.BOLD, 11f)
    form.add(l, gc)
    gc.gridy = row * 2 + 1
    form.add(component, gc)
}

gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2
JLabel editorHeading = new JLabel('PRESET DETAILS')
editorHeading.font = editorHeading.font.deriveFont(Font.BOLD, 11f)
editorHeading.foreground = muted
form.add(editorHeading, gc)
gc.gridwidth = 1

addField(1, 0, 'PRESET NAME', nameField)
addField(1, 1, 'CALIBRATION UNIT', expectedUnitField)
addField(2, 0, 'DISTANCE IN PIXELS', pixelDistanceField)
addField(2, 1, 'KNOWN DISTANCE', knownDistanceField)
addField(3, 0, 'PIXEL ASPECT RATIO', pixelAspectField)
addField(3, 1, 'SCALE BAR WIDTH', widthField)
addField(4, 0, 'BAR HEIGHT (PIXELS)', heightField)
addField(4, 1, 'LABEL SIZE', fontField)
addField(5, 0, 'POSITION', locationField)
addField(5, 1, 'BAR & LABEL', colorField)
addField(6, 0, 'BACKGROUND', backgroundField)
addField(6, 1, 'SCALE BAR UNIT', scaleBarUnitField)

JPanel checks = new JPanel(new GridLayout(0, 2, 8, 6))
checks.opaque = false
checks.border = new EmptyBorder(4, 0, 4, 0)
Closure<Icon> makeCheckboxIcon = { boolean selected ->
    [
        getIconWidth: { 18 },
        getIconHeight: { 18 },
        paintIcon: { Component component, Graphics graphics, int x, int y ->
            Graphics2D g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = selected ? accent : input
                g.fillRoundRect(x + 1, y + 1, 16, 16, 4, 4)
                g.color = selected ? accent.darker() : border
                g.stroke = new BasicStroke(1f)
                g.drawRoundRect(x + 1, y + 1, 15, 15, 4, 4)
                if (selected) {
                    g.color = Color.WHITE
                    g.stroke = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.drawLine(x + 5, y + 9, x + 8, y + 12)
                    g.drawLine(x + 8, y + 12, x + 14, y + 5)
                }
            } finally {
                g.dispose()
            }
        }
    ] as Icon
}
Icon uncheckedIcon = makeCheckboxIcon(false)
Icon checkedIcon = makeCheckboxIcon(true)
def visibleChecks = [boldField, serifField, hideTextField, overlayField]
if (externalOwner == null) visibleChecks.add(globalScaleField)
visibleChecks.each {
    it.opaque = false
    it.foreground = ink
    it.border = new EmptyBorder(4, 6, 4, 6)
    it.icon = uncheckedIcon
    it.selectedIcon = checkedIcon
    it.iconTextGap = 7
    checks.add(it)
}
gc.gridx = 0; gc.gridy = 14; gc.gridwidth = 2; gc.insets = new Insets(15, 5, 8, 5)
form.add(checks, gc)

JLabel imageStatus = new JLabel('No image is open')
imageStatus.foreground = muted
imageStatus.border = new EmptyBorder(10, 12, 10, 12)
imageStatus.opaque = true
imageStatus.background = new Color(31, 55, 54)

JButton refreshButton = new JButton('Refresh image')
JButton saveButton = new JButton('Save preset')
JButton saveCopyButton = new JButton('Save as copy…')
JButton calibrationOnlyButton = new JButton('Set calibration')
JButton barOnlyButton = new JButton('Add bar only')
JButton applyButton = new JButton('Set scale + add bar')
JButton removeBarButton = new JButton('Remove bar')
JButton doneButton = new JButton('Done')
applyButton.background = accent
applyButton.foreground = Color.WHITE
applyButton.font = applyButton.font.deriveFont(Font.BOLD)

[addButton, renameButton, deleteButton, moveUpButton, moveDownButton, detailsButton, sortButton, renameCollectionButton,
 importButton, exportButton, refreshButton, saveButton, saveCopyButton, calibrationOnlyButton, barOnlyButton, applyButton,
 removeBarButton, doneButton].each { styleButton(it as AbstractButton, it == applyButton) }

JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0))
actions.opaque = false
actions.add(saveButton)
actions.add(saveCopyButton)
if (externalOwner instanceof Frame) {
    doneButton.font = doneButton.font.deriveFont(Font.BOLD)
    doneButton.toolTipText = 'Close the manager and refresh FijiCal Lite with the saved presets.'
    actions.add(doneButton)
} else {
    actions.add(refreshButton)
    actions.add(calibrationOnlyButton)
    actions.add(barOnlyButton)
    actions.add(applyButton)
    actions.add(removeBarButton)
}

JPanel right = new JPanel(new BorderLayout(0, 12))
right.opaque = false
right.add(form, BorderLayout.CENTER)
right.add(imageStatus, BorderLayout.NORTH)
right.add(actions, BorderLayout.SOUTH)
root.add(right, BorderLayout.CENTER)

pixelDistanceField.toolTipText = 'Pixel length measured across the known calibration distance.'
knownDistanceField.toolTipText = 'Real-world length represented by Distance in Pixels.'
expectedUnitField.toolTipText = 'Physical unit used by the known calibration distance, for example mm.'
scaleBarUnitField.toolTipText = 'Unit printed beside the scale bar. This may differ from the calibration unit, for example µm.'
pixelAspectField.toolTipText = 'Pixel width divided by pixel height. Usually 1 for square pixels.'
widthField.toolTipText = 'Physical length represented by the finished scale bar.'
overlayField.toolTipText = 'Recommended: keeps the scale bar editable and removable without changing image pixels.'
globalScaleField.toolTipText = 'Also applies this calibration globally to other open images in Fiji.'
calibrationOnlyButton.toolTipText = 'Run Analyze → Set Scale without adding a scale bar.'
barOnlyButton.toolTipText = 'Add the styled scale bar using the image’s existing calibration.'
applyButton.toolTipText = 'Apply this preset’s calibration, then add its scale bar.'
removeBarButton.toolTipText = 'Remove editable ImageJ scale-bar overlays from the active image.'
detailsButton.toolTipText = 'Record optional microscope, camera, adapter, objective, and calibration notes.'
sortButton.toolTipText = 'Sort the saved collection by microscope, objective magnification, or preset name.'
renameCollectionButton.toolTipText = 'Rename the active microscope collection across every preset that belongs to it.'
importButton.toolTipText = 'Import presets from a FijiCal Lite preset pack or backup.'
exportButton.toolTipText = 'Export the selected preset or the complete collection as a portable JSON pack.'

Closure<String> presetCollection = { Map p ->
    String value = (p.collection ?: '').toString().trim()
    value ?: 'Unfiled'
}
Closure<String> presetObjective = { Map p ->
    (p.objective ?: '').toString().trim()
}
Closure<Integer> selectedPresetIndex = {
    int visibleIndex = presetList.selectedIndex
    (visibleIndex >= 0 && visibleIndex < visiblePresetIndices.size()) ? visiblePresetIndices[visibleIndex] : -1
}
Closure showPreset = null
Closure refreshPresetModel = { int desiredPresetIndex, boolean refreshCollections ->
    if (rebuildingPresetModel) return
    rebuildingPresetModel = true
    try {
        int actualToRestore = desiredPresetIndex >= 0 ? desiredPresetIndex : selectedPresetIndex()
        String requestedCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
        if (refreshCollections) {
            List<String> collections = presets.collect { presetCollection(it as Map) }.unique(false)
            collections.sort { a, b -> a.compareToIgnoreCase(b) }
            collectionFilter.removeAllItems()
            collectionFilter.addItem(ALL_COLLECTIONS)
            collections.each { collectionFilter.addItem(it) }
            boolean stillExists = requestedCollection == ALL_COLLECTIONS || collections.any { it.equalsIgnoreCase(requestedCollection) }
            collectionFilter.selectedItem = stillExists ? requestedCollection : ALL_COLLECTIONS
        }
        String activeCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
        visiblePresetIndices.clear()
        presetModel.clear()
        presets.eachWithIndex { Map p, int actualIndex ->
            String collection = presetCollection(p)
            if (activeCollection == ALL_COLLECTIONS || collection.equalsIgnoreCase(activeCollection)) {
                visiblePresetIndices.add(actualIndex)
                String objective = presetObjective(p)
                String item = activeCollection == ALL_COLLECTIONS ? "${collection}  ›  ${p.name}" : (objective ? "${p.name}  ·  ${objective}" : (p.name as String))
                presetModel.addElement(item)
            }
        }
        int visibleToRestore = visiblePresetIndices.indexOf(actualToRestore)
        if (visibleToRestore >= 0) presetList.selectedIndex = visibleToRestore
        else if (!visiblePresetIndices.isEmpty()) presetList.selectedIndex = 0
        else presetList.clearSelection()
    } finally {
        rebuildingPresetModel = false
    }
    int selectedIndex = selectedPresetIndex()
    if (showPreset != null && selectedIndex >= 0) showPreset(presets[selectedIndex])
}
refreshPresetModel(-1, true)

Closure currentPreset = {
    Map result = [
        name: nameField.text.trim() ?: 'Untitled preset',
        pixelDistance: (pixelDistanceField.value as Number).doubleValue(),
        knownDistance: (knownDistanceField.value as Number).doubleValue(),
        calibrationUnit: expectedUnitField.text.trim() ?: 'pixel',
        scaleUnit: scaleBarUnitField.text.trim() ?: expectedUnitField.text.trim() ?: 'pixel',
        pixelAspect: (pixelAspectField.value as Number).doubleValue(),
        globalScale: globalScaleField.selected,
        width: (widthField.value as Number).doubleValue(),
        height: (heightField.value as Number).intValue(),
        font: (fontField.value as Number).intValue(),
        color: colorField.selectedItem as String,
        background: backgroundField.selectedItem as String,
        location: locationField.selectedItem as String,
        bold: boldField.selected,
        serif: serifField.selected,
        hideText: hideTextField.selected,
        overlay: overlayField.selected
    ]
    int selectedIndex = selectedPresetIndex()
    if (selectedIndex >= 0 && selectedIndex < presets.size()) {
        Map existing = presets[selectedIndex] as Map
        ['collection', 'microscope', 'camera', 'adapter', 'objective', 'notes', 'calibrationSource', 'calibrationDate'].each { key ->
            if (existing.containsKey(key) && existing[key] != null && existing[key].toString().trim()) result[key] = existing[key]
        }
    }
    result
}

Closure duplicateIndex = { String candidate, int excludedIndex ->
    for (int i = 0; i < presets.size(); i++) {
        if (i != excludedIndex && (presets[i].name ?: '').toString().equalsIgnoreCase(candidate)) return i
    }
    -1
}

Closure validatePreset = { Map p, int excludedIndex, boolean applying ->
    if (!(p.name as String)?.trim()) {
        JOptionPane.showMessageDialog(frame, 'Enter a preset name.', 'Preset name required', JOptionPane.WARNING_MESSAGE)
        return false
    }
    if (duplicateIndex(p.name as String, excludedIndex) >= 0) {
        JOptionPane.showMessageDialog(frame, "A preset named ‘${p.name}’ already exists.", 'Duplicate preset name', JOptionPane.WARNING_MESSAGE)
        return false
    }
    if ((p.calibrationUnit as String).trim().isEmpty() || (p.scaleUnit as String).trim().isEmpty()) {
        JOptionPane.showMessageDialog(frame, 'Enter both a calibration unit and a scale-bar unit, such as mm and µm.', 'Units required', JOptionPane.WARNING_MESSAGE)
        return false
    }
    if ((p.pixelDistance as double) <= 0d || (p.knownDistance as double) <= 0d || (p.pixelAspect as double) <= 0d || (p.width as double) <= 0d) {
        JOptionPane.showMessageDialog(frame, 'Calibration distances, pixel aspect ratio, and bar width must be greater than zero.', 'Invalid calibration', JOptionPane.WARNING_MESSAGE)
        return false
    }
    boolean looksPlaceholder = (p.pixelDistance as double) == 1d && (p.knownDistance as double) == 1d && !['pixel','pixels','px'].contains((p.calibrationUnit as String).toLowerCase())
    if (looksPlaceholder && applying) {
        int answer = JOptionPane.showConfirmDialog(frame,
            "This calibration is 1 pixel = 1 ${p.calibrationUnit}, which looks like a placeholder.\nApply it anyway?",
            'Check calibration', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (answer != JOptionPane.YES_OPTION) return false
    }
    true
}

showPreset = { Map p ->
    nameField.text = p.name ?: ''
    pixelDistanceField.value = (p.pixelDistance ?: 1d) as Double
    knownDistanceField.value = (p.knownDistance ?: 1d) as Double
    pixelAspectField.value = (p.pixelAspect ?: 1d) as Double
    globalScaleField.selected = p.globalScale == true
    widthField.value = (p.width ?: 10d) as Double
    heightField.value = (p.height ?: 4) as Integer
    fontField.value = (p.font ?: 18) as Integer
    colorField.selectedItem = p.color ?: 'White'
    backgroundField.selectedItem = p.background ?: 'None'
    locationField.selectedItem = p.location ?: 'Lower Right'
    boldField.selected = p.bold != false
    serifField.selected = p.serif == true
    hideTextField.selected = p.hideText == true
    overlayField.selected = p.overlay != false
    expectedUnitField.text = p.calibrationUnit ?: p.scaleUnit ?: p.expectedUnit ?: 'pixel'
    scaleBarUnitField.text = p.scaleUnit ?: p.calibrationUnit ?: p.expectedUnit ?: 'pixel'
}

Closure refreshImageStatus = {
    ImagePlus imp = WindowManager.currentImage
    if (imp == null) {
        imageStatus.text = 'No image is open — open or select an image in Fiji first.'
        imageStatus.foreground = new Color(151, 80, 36)
        [applyButton, calibrationOnlyButton, barOnlyButton, removeBarButton].each { it.enabled = false }
        return
    }
    def cal = imp.calibration
    String unit = cal.unit ?: 'pixel'
    String scale = (cal.scaled() && cal.pixelWidth > 0) ? String.format('1 px = %.5g %s', cal.pixelWidth, unit) : 'uncalibrated pixels'
    imageStatus.text = "Active: ${imp.title}   ·   ${imp.width} × ${imp.height} px   ·   ${scale}"
    imageStatus.foreground = ink
    [applyButton, calibrationOnlyButton, barOnlyButton, removeBarButton].each { it.enabled = true }
}

Closure optionToken = { String value -> value.contains(' ') ? "[${value}]" : value }
Closure buildSetScaleOptions = { Map p ->
    List<String> options = [
        "distance=${p.pixelDistance}",
        "known=${p.knownDistance}",
        "pixel=${p.pixelAspect}",
        "unit=${optionToken((p.calibrationUnit ?: p.scaleUnit) as String)}"
    ]
    if (p.globalScale) options << 'global'
    options.join(' ')
}
Closure buildOptions = { Map p ->
    List<String> options = [
        "width=${p.width}",
        "height=${p.height}",
        "font=${p.font}",
        "color=${optionToken(p.color as String)}",
        "background=${optionToken(p.background as String)}",
        "location=${optionToken(p.location as String)}"
    ]
    if (p.bold) options << 'bold'
    if (p.serif) options << 'serif'
    if (p.hideText) options << 'hide'
    if (p.overlay) options << 'overlay'
    options.join(' ')
}

Closure positiveNumber = { Map source, String key, Object fallback, boolean required ->
    Object raw = source.containsKey(key) ? source[key] : fallback
    if (raw == null && required) throw new IllegalArgumentException("Missing ${key}.")
    double number
    try {
        number = raw instanceof Number ? (raw as Number).doubleValue() : Double.parseDouble(raw.toString())
    } catch (Exception ignored) {
        throw new IllegalArgumentException("${key} must be a number.")
    }
    if (!Double.isFinite(number) || number <= 0d) throw new IllegalArgumentException("${key} must be greater than zero.")
    number
}

Closure<Map> normalizeImportedPreset = { Object value ->
    if (!(value instanceof Map)) throw new IllegalArgumentException('Each preset must be a JSON object.')
    Map source = new LinkedHashMap(value as Map)
    String importedName = (source.name ?: '').toString().trim()
    if (!importedName) throw new IllegalArgumentException('A preset is missing its name.')
    String unit = (source.scaleUnit ?: source.expectedUnit ?: '').toString().trim()
    if (!unit) throw new IllegalArgumentException("Preset ‘${importedName}’ is missing its scale unit.")

    Map normalized = new LinkedHashMap(source)
    normalized.name = importedName
    normalized.pixelDistance = positiveNumber(source, 'pixelDistance', null, true)
    normalized.knownDistance = positiveNumber(source, 'knownDistance', null, true)
    normalized.scaleUnit = unit
    normalized.pixelAspect = positiveNumber(source, 'pixelAspect', 1d, false)
    normalized.width = positiveNumber(source, 'width', 10d, false)
    normalized.height = Math.max(1, positiveNumber(source, 'height', 4, false).intValue())
    normalized.font = Math.max(1, positiveNumber(source, 'font', 18, false).intValue())
    normalized.color = (source.color ?: 'White').toString()
    normalized.background = (source.background ?: 'None').toString()
    normalized.location = (source.location ?: 'Lower Right').toString()
    normalized.bold = source.bold != false
    normalized.serif = source.serif == true
    normalized.hideText = source.hideText == true
    normalized.overlay = source.overlay != false
    normalized.globalScale = source.globalScale == true
    ['collection', 'microscope', 'camera', 'adapter', 'objective', 'notes', 'calibrationSource', 'calibrationDate'].each { key ->
        if (source.containsKey(key)) {
            String clean = source[key] == null ? '' : source[key].toString().trim()
            if (clean) normalized[key] = clean else normalized.remove(key)
        }
    }
    normalized.remove('expectedUnit')
    normalized
}

Closure<Map> readPresetPack = { File sourceFile ->
    Object parsed = new JsonSlurper().parse(sourceFile, 'UTF-8')
    Object rawPresets
    Map packInfo = [:]
    if (parsed instanceof List) {
        rawPresets = parsed
    } else if (parsed instanceof Map) {
        String format = (parsed.format ?: '').toString()
        if (format && ![PACK_FORMAT, 'FijiCal Lite Presets'].contains(format)) {
            throw new IllegalArgumentException("This is not a FijiCal Lite preset file (format: ${format}).")
        }
        int version = parsed.version instanceof Number ? (parsed.version as Number).intValue() : 1
        if (version > PACK_VERSION) throw new IllegalArgumentException("This preset pack uses version ${version}; this build supports version ${PACK_VERSION}.")
        rawPresets = parsed.presets
        if (parsed.pack instanceof Map) packInfo.putAll(parsed.pack as Map)
    } else {
        throw new IllegalArgumentException('The selected file does not contain a preset collection.')
    }
    if (!(rawPresets instanceof List) || (rawPresets as List).isEmpty()) {
        throw new IllegalArgumentException('The selected file contains no presets.')
    }
    List<Map> imported = []
    (rawPresets as List).eachWithIndex { entry, int index ->
        try {
            imported.add(normalizeImportedPreset(entry))
        } catch (Exception error) {
            throw new IllegalArgumentException("Preset ${index + 1}: ${error.message}")
        }
    }
    [pack: packInfo, presets: imported]
}

Closure writeJsonDocument = { File destination, Map document ->
    File parent = destination.absoluteFile.parentFile
    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create ${parent.absolutePath}")
    File temporary = new File(parent, destination.name + '.tmp')
    temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(document)) + '\n', 'UTF-8')
    try {
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

Closure<String> uniquePresetName = { String requested, List<Map> collection ->
    String base = requested.trim() ?: 'Imported preset'
    String candidate = base
    int suffix = 2
    while (collection.any { (it.name ?: '').toString().equalsIgnoreCase(candidate) }) {
        candidate = "${base} (${suffix++})"
    }
    candidate
}

Closure<JFileChooser> presetChooser = { String chooserTitle ->
    String remembered = prefs.get('pack.lastDirectory', portablePresetFile?.parentFile?.absolutePath ?: System.getProperty('user.home'))
    JFileChooser chooser = new JFileChooser(new File(remembered))
    chooser.dialogTitle = chooserTitle
    chooser.acceptAllFileFilterUsed = false
    chooser.fileFilter = new FileNameExtensionFilter('FijiCal Lite preset packs (*.json)', 'json')
    SwingUtilities.updateComponentTreeUI(chooser)
    chooser
}

presetList.addListSelectionListener { ListSelectionEvent e ->
    if (!rebuildingPresetModel && !e.valueIsAdjusting) {
        int actualIndex = selectedPresetIndex()
        if (actualIndex >= 0) {
            showPreset(presets[actualIndex])
            prefs.put('lastPreset', presets[actualIndex].name as String)
        }
    }
}
collectionFilter.addActionListener {
    if (!rebuildingPresetModel) {
        refreshPresetModel(-1, false)
        int actualIndex = selectedPresetIndex()
        if (actualIndex >= 0) showPreset(presets[actualIndex])
    }
}

addButton.addActionListener {
    Map p = new LinkedHashMap(defaults[0])
    p.name = 'New preset'
    String activeCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
    if (activeCollection != ALL_COLLECTIONS) p.collection = activeCollection
    presets.add(p)
    refreshPresetModel(presets.size() - 1, true)
    nameField.requestFocusInWindow()
    nameField.selectAll()
}

renameButton.addActionListener {
    int i = selectedPresetIndex()
    if (i < 0) return
    String oldName = presets[i].name as String
    String newName = JOptionPane.showInputDialog(frame, 'New preset name:', oldName)
    if (newName == null) return
    newName = newName.trim()
    if (!newName) {
        JOptionPane.showMessageDialog(frame, 'Enter a preset name.', 'Preset name required', JOptionPane.WARNING_MESSAGE)
        return
    }
    if (duplicateIndex(newName, i) >= 0) {
        JOptionPane.showMessageDialog(frame, "A preset named ‘${newName}’ already exists.", 'Duplicate preset name', JOptionPane.WARNING_MESSAGE)
        return
    }
    presets[i].name = newName
    refreshPresetModel(i, false)
    nameField.text = newName
    savePresets()
    prefs.put('lastPreset', newName)
    imageStatus.text = "Renamed ‘${oldName}’ to ‘${newName}’"
    imageStatus.foreground = accent
}

Closure movePreset = { int direction ->
    int visibleFrom = presetList.selectedIndex
    int visibleTo = visibleFrom + direction
    if (visibleFrom < 0 || visibleTo < 0 || visibleTo >= visiblePresetIndices.size()) return
    int actualFrom = visiblePresetIndices[visibleFrom]
    int actualTo = visiblePresetIndices[visibleTo]
    Collections.swap(presets, actualFrom, actualTo)
    savePresets()
    refreshPresetModel(actualTo, false)
    presetList.ensureIndexIsVisible(presetList.selectedIndex)
}
moveUpButton.addActionListener { movePreset(-1) }
moveDownButton.addActionListener { movePreset(1) }

sortButton.addActionListener {
    if (presets.size() < 2) return
    String activeCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
    String[] modes = [
        'Collection, then objective (low → high)',
        'Collection, then preset name (A → Z)',
        'Objective magnification (low → high)',
        'Preset name (A → Z)'
    ] as String[]
    JComboBox<String> modeField = new JComboBox<>(modes)
    JCheckBox reverseField = new JCheckBox('Reverse order')
    JCheckBox currentCollectionOnly = new JCheckBox("Only ‘${activeCollection}’", activeCollection != ALL_COLLECTIONS)
    currentCollectionOnly.enabled = activeCollection != ALL_COLLECTIONS
    JPanel sortForm = new JPanel()
    sortForm.layout = new BoxLayout(sortForm, BoxLayout.Y_AXIS)
    modeField.alignmentX = Component.LEFT_ALIGNMENT
    reverseField.alignmentX = Component.LEFT_ALIGNMENT
    currentCollectionOnly.alignmentX = Component.LEFT_ALIGNMENT
    sortForm.add(new JLabel('Sort saved presets by'))
    sortForm.add(Box.createVerticalStrut(5))
    sortForm.add(modeField)
    sortForm.add(Box.createVerticalStrut(8))
    sortForm.add(reverseField)
    sortForm.add(currentCollectionOnly)
    if (JOptionPane.showConfirmDialog(frame, sortForm, 'Sort presets', JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return

    List<Integer> targetIndices = currentCollectionOnly.selected ? new ArrayList<Integer>(visiblePresetIndices) : (0..<presets.size()).collect { it as Integer }
    if (targetIndices.size() < 2) return
    Map selectedBeforeSort = selectedPresetIndex() >= 0 ? presets[selectedPresetIndex()] as Map : null
    Closure<Double> magnification = { Map p ->
        String value = presetObjective(p) ?: (p.name ?: '').toString()
        def matcher = value =~ /(?i)(\d+(?:\.\d+)?)\s*x/
        matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.POSITIVE_INFINITY
    }
    Closure<Integer> textCompare = { Object leftValue, Object rightValue ->
        (leftValue ?: '').toString().compareToIgnoreCase((rightValue ?: '').toString())
    }
    List<Map> sorted = targetIndices.collect { presets[it] as Map }
    sorted.sort { Map a, Map b ->
        int comparison = 0
        switch (modeField.selectedIndex) {
            case 0:
                comparison = textCompare(presetCollection(a), presetCollection(b))
                if (comparison == 0) comparison = Double.compare(magnification(a), magnification(b))
                if (comparison == 0) comparison = textCompare(a.name, b.name)
                break
            case 1:
                comparison = textCompare(presetCollection(a), presetCollection(b))
                if (comparison == 0) comparison = textCompare(a.name, b.name)
                break
            case 2:
                comparison = Double.compare(magnification(a), magnification(b))
                if (comparison == 0) comparison = textCompare(a.name, b.name)
                break
            default:
                comparison = textCompare(a.name, b.name)
                break
        }
        reverseField.selected ? -comparison : comparison
    }
    List<Integer> destinationIndices = new ArrayList<Integer>(targetIndices)
    Collections.sort(destinationIndices)
    destinationIndices.eachWithIndex { Integer destination, int sortedIndex -> presets[destination] = sorted[sortedIndex] }
    savePresets()
    int selectedAfterSort = selectedBeforeSort == null ? -1 : presets.findIndexOf { it.is(selectedBeforeSort) }
    refreshPresetModel(selectedAfterSort, true)
    imageStatus.text = "Sorted ${sorted.size()} preset${sorted.size() == 1 ? '' : 's'}"
    imageStatus.foreground = accent
}

renameCollectionButton.addActionListener {
    List<String> collections = presets.collect { presetCollection(it as Map) }.unique(false)
    collections.sort { a, b -> a.compareToIgnoreCase(b) }
    if (collections.isEmpty()) return
    String activeCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
    String oldName = activeCollection
    if (oldName == ALL_COLLECTIONS) {
        Object selectedCollection = JOptionPane.showInputDialog(frame, 'Choose the collection to rename:', 'Rename collection',
            JOptionPane.PLAIN_MESSAGE, null, collections as Object[], collections[0])
        if (selectedCollection == null) return
        oldName = selectedCollection.toString()
    }
    String newName = JOptionPane.showInputDialog(frame,
        "Rename ‘${oldName}’ to:\n\nTip: use ‘Unfiled’ to remove these presets from a named collection.", oldName)
    if (newName == null) return
    newName = newName.trim()
    if (!newName) {
        JOptionPane.showMessageDialog(frame, 'Enter a collection name.', 'Collection name required', JOptionPane.WARNING_MESSAGE)
        return
    }
    if (newName.equalsIgnoreCase(oldName)) return
    boolean targetExists = collections.any { it.equalsIgnoreCase(newName) }
    if (targetExists && JOptionPane.showConfirmDialog(frame,
        "‘${newName}’ already exists. Merge every preset from ‘${oldName}’ into it?",
        'Merge collections', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return
    int selectedBeforeRename = selectedPresetIndex()
    int changed = 0
    presets.each { Map p ->
        if (presetCollection(p).equalsIgnoreCase(oldName)) {
            if (newName.equalsIgnoreCase('Unfiled')) p.remove('collection') else p.collection = newName
            changed++
        }
    }
    savePresets()
    refreshPresetModel(selectedBeforeRename, true)
    collectionFilter.selectedItem = newName.equalsIgnoreCase('Unfiled') ? 'Unfiled' : newName
    imageStatus.text = targetExists ? "Merged ${changed} preset${changed == 1 ? '' : 's'} into ‘${newName}’" : "Renamed ‘${oldName}’ to ‘${newName}’"
    imageStatus.foreground = accent
}

deleteButton.addActionListener {
    int i = selectedPresetIndex()
    if (i < 0) return
    if (JOptionPane.showConfirmDialog(frame, "Delete ‘${presets[i].name}’?", 'Delete preset', JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
    presets.remove(i)
    savePresets()
    if (!presets.isEmpty()) refreshPresetModel(Math.min(i, presets.size() - 1), true)
    else { refreshPresetModel(-1, true); prefs.remove('lastPreset') }
}

detailsButton.addActionListener {
    int i = selectedPresetIndex()
    if (i < 0) return
    Map selectedPreset = presets[i] as Map
    JTextField collectionField = new JTextField(presetCollection(selectedPreset), 28)
    JTextField microscopeField = new JTextField((selectedPreset.microscope ?: '').toString(), 28)
    JTextField cameraField = new JTextField((selectedPreset.camera ?: '').toString(), 28)
    JTextField adapterField = new JTextField((selectedPreset.adapter ?: '').toString(), 28)
    JTextField objectiveField = new JTextField((selectedPreset.objective ?: '').toString(), 28)
    JTextArea notesField = new JTextArea((selectedPreset.notes ?: '').toString(), 4, 28)
    notesField.lineWrap = true
    notesField.wrapStyleWord = true

    JPanel details = new JPanel(new GridBagLayout())
    GridBagConstraints dc = new GridBagConstraints()
    dc.insets = new Insets(4, 4, 4, 4)
    dc.anchor = GridBagConstraints.WEST
    dc.fill = GridBagConstraints.HORIZONTAL
    dc.weightx = 0
    [['Collection', collectionField], ['Microscope', microscopeField], ['Camera', cameraField], ['Adapter', adapterField], ['Objective', objectiveField]].eachWithIndex { row, int index ->
        dc.gridx = 0; dc.gridy = index; dc.weightx = 0
        details.add(new JLabel(row[0] as String), dc)
        dc.gridx = 1; dc.weightx = 1
        details.add(row[1] as JComponent, dc)
    }
    dc.gridx = 0; dc.gridy = 5; dc.weightx = 0; dc.anchor = GridBagConstraints.NORTHWEST
    details.add(new JLabel('Notes'), dc)
    dc.gridx = 1; dc.weightx = 1; dc.weighty = 1; dc.fill = GridBagConstraints.BOTH
    details.add(new JScrollPane(notesField), dc)

    if (JOptionPane.showConfirmDialog(frame, details, "Preset details — ${selectedPreset.name}", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
    [collection: collectionField.text, microscope: microscopeField.text, camera: cameraField.text, adapter: adapterField.text,
     objective: objectiveField.text, notes: notesField.text].each { String key, String value ->
        String clean = value.trim()
        if (clean) selectedPreset[key] = clean else selectedPreset.remove(key)
    }
    savePresets()
    refreshPresetModel(i, true)
    collectionFilter.selectedItem = presetCollection(selectedPreset)
    imageStatus.text = "Saved details for ‘${selectedPreset.name}’"
    imageStatus.foreground = accent
}

exportButton.addActionListener {
    if (presets.isEmpty()) {
        JOptionPane.showMessageDialog(frame, 'There are no saved presets to export.', 'Nothing to export', JOptionPane.INFORMATION_MESSAGE)
        return
    }
    int selectedIndex = selectedPresetIndex()
    String selectedName = selectedIndex >= 0 ? (presets[selectedIndex].name as String) : ''
    String activeCollection = (collectionFilter.selectedItem ?: ALL_COLLECTIONS).toString()
    List<String> scopeModes = ['selected']
    List<String> scopeLabels = ["Selected preset — ${selectedName}"]
    if (activeCollection != ALL_COLLECTIONS) {
        scopeModes.add('collection')
        scopeLabels.add("Current collection — ${activeCollection} (${visiblePresetIndices.size()} presets)")
    }
    scopeModes.add('all')
    scopeLabels.add("Complete library — ${presets.size()} presets")
    JComboBox<String> scopeField = new JComboBox<>(scopeLabels as String[])
    if (selectedIndex < 0) scopeField.selectedIndex = scopeModes.indexOf(activeCollection != ALL_COLLECTIONS ? 'collection' : 'all')
    JTextField packNameField = new JTextField(activeCollection != ALL_COLLECTIONS ? activeCollection : (selectedName ?: 'FijiCal Lite Presets'), 30)
    JTextArea descriptionField = new JTextArea(3, 30)
    descriptionField.lineWrap = true
    descriptionField.wrapStyleWord = true
    JLabel savedValuesNote = new JLabel('Exports saved values. Save any field edits first.')
    savedValuesNote.foreground = muted

    JPanel exportForm = new JPanel(new GridBagLayout())
    GridBagConstraints ec = new GridBagConstraints()
    ec.insets = new Insets(4, 4, 4, 4); ec.fill = GridBagConstraints.HORIZONTAL; ec.weightx = 1
    ec.gridx = 0; ec.gridy = 0; exportForm.add(new JLabel('Contents'), ec)
    ec.gridx = 1; exportForm.add(scopeField, ec)
    ec.gridx = 0; ec.gridy = 1; exportForm.add(new JLabel('Pack name'), ec)
    ec.gridx = 1; exportForm.add(packNameField, ec)
    ec.gridx = 0; ec.gridy = 2; ec.anchor = GridBagConstraints.NORTHWEST; exportForm.add(new JLabel('Description'), ec)
    ec.gridx = 1; ec.fill = GridBagConstraints.BOTH; exportForm.add(new JScrollPane(descriptionField), ec)
    ec.gridx = 0; ec.gridy = 3; ec.gridwidth = 2; ec.fill = GridBagConstraints.HORIZONTAL; exportForm.add(savedValuesNote, ec)

    if (JOptionPane.showConfirmDialog(frame, exportForm, 'Export preset pack', JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
    String packName = packNameField.text.trim() ?: 'FijiCal Lite Presets'
    String scopeMode = scopeModes[scopeField.selectedIndex]
    List<Map> exportedPresets
    if (scopeMode == 'all') exportedPresets = presets.collect { new LinkedHashMap(it as Map) }
    else if (scopeMode == 'collection') exportedPresets = visiblePresetIndices.collect { new LinkedHashMap(presets[it] as Map) }
    else exportedPresets = [new LinkedHashMap(presets[selectedIndex] as Map)]

    JFileChooser chooser = presetChooser('Export FijiCal Lite preset pack')
    String safeName = packName.replaceAll(/[\\\/:*?"<>|]/, '-').trim()
    chooser.selectedFile = new File(chooser.currentDirectory, "${safeName ?: 'FijiCal Lite Presets'}.json")
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return
    File destination = chooser.selectedFile
    if (!destination.name.toLowerCase().endsWith('.json')) destination = new File(destination.parentFile, destination.name + '.json')
    if (destination.exists() && JOptionPane.showConfirmDialog(frame, "Replace ${destination.name}?", 'File already exists', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return

    Map packInfo = [name: packName, description: descriptionField.text.trim(), createdAt: Instant.now().toString(), presetCount: exportedPresets.size()]
    packInfo = packInfo.findAll { key, value -> value != null && value.toString().trim() }
    Map document = [format: PACK_FORMAT, version: PACK_VERSION, pack: packInfo, presets: exportedPresets]
    try {
        writeJsonDocument(destination, document)
        prefs.put('pack.lastDirectory', destination.parentFile.absolutePath)
        imageStatus.text = "Exported ${exportedPresets.size()} preset${exportedPresets.size() == 1 ? '' : 's'} to ${destination.name}"
        imageStatus.foreground = accent
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame, "The preset pack could not be exported.\n${error.message}", 'Export failed', JOptionPane.ERROR_MESSAGE)
    }
}

importButton.addActionListener {
    JFileChooser chooser = presetChooser('Import FijiCal Lite preset pack')
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
    File sourceFile = chooser.selectedFile
    Map loaded
    try {
        loaded = readPresetPack(sourceFile)
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame, "The preset pack could not be read.\n${error.message}", 'Import failed', JOptionPane.ERROR_MESSAGE)
        return
    }
    List<Map> incoming = loaded.presets as List<Map>
    Map packInfo = loaded.pack as Map
    int conflicts = incoming.count { candidate -> presets.any { existing -> (existing.name ?: '').toString().equalsIgnoreCase((candidate.name ?: '').toString()) } }

    String[] policies = ['Keep both (automatically rename imports)', 'Replace existing presets', 'Skip duplicate names', 'Rename each duplicate…'] as String[]
    JComboBox<String> policyField = new JComboBox<>(policies)
    JTextArea summary = new JTextArea(7, 42)
    summary.editable = false; summary.opaque = false; summary.lineWrap = true; summary.wrapStyleWord = true
    summary.foreground = new Color(225, 230, 234)
    summary.caretColor = summary.foreground
    summary.text = "Pack: ${(packInfo.name ?: sourceFile.name)}\n" +
        "Presets: ${incoming.size()}\nName conflicts: ${conflicts}\n" +
        ((packInfo.description ?: '').toString().trim() ? "Description: ${packInfo.description}\n" : '') +
        '\nThe import is applied only after every conflict has been resolved.'
    JPanel importForm = new JPanel(new BorderLayout(0, 10))
    importForm.add(summary, BorderLayout.CENTER)
    JPanel policyRow = new JPanel(new BorderLayout(8, 0))
    policyRow.add(new JLabel('When a name already exists'), BorderLayout.WEST)
    policyRow.add(policyField, BorderLayout.CENTER)
    importForm.add(policyRow, BorderLayout.SOUTH)

    if (JOptionPane.showConfirmDialog(frame, importForm, 'Import preset pack', JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return

    List<Map> merged = presets.collect { new LinkedHashMap(it as Map) }
    int added = 0; int replaced = 0; int skipped = 0; int renamed = 0
    for (Map candidateValue : incoming) {
        Map candidate = new LinkedHashMap(candidateValue)
        int conflictIndex = merged.findIndexOf { existing -> (existing.name ?: '').toString().equalsIgnoreCase((candidate.name ?: '').toString()) }
        if (conflictIndex < 0) {
            merged.add(candidate); added++
            continue
        }
        switch (policyField.selectedIndex) {
            case 0:
                candidate.name = uniquePresetName(candidate.name as String, merged)
                merged.add(candidate); added++; renamed++
                break
            case 1:
                merged[conflictIndex] = candidate; replaced++
                break
            case 2:
                skipped++
                break
            case 3:
                String suggestion = uniquePresetName(candidate.name as String, merged)
                while (true) {
                    String requested = JOptionPane.showInputDialog(frame, "Rename imported preset ‘${candidate.name}’: ", suggestion)
                    if (requested == null) return
                    requested = requested.trim()
                    if (!requested) {
                        JOptionPane.showMessageDialog(frame, 'Enter a preset name, or choose Cancel to stop the entire import.', 'Preset name required', JOptionPane.WARNING_MESSAGE)
                        continue
                    }
                    if (merged.any { existing -> (existing.name ?: '').toString().equalsIgnoreCase(requested) }) {
                        JOptionPane.showMessageDialog(frame, "A preset named ‘${requested}’ already exists.", 'Duplicate preset name', JOptionPane.WARNING_MESSAGE)
                        continue
                    }
                    candidate.name = requested
                    break
                }
                merged.add(candidate); added++; renamed++
                break
        }
    }

    List<Map> previous = presets.collect { new LinkedHashMap(it as Map) }
    try {
        presets.clear(); presets.addAll(merged)
        savePresets()
        prefs.put('pack.lastDirectory', sourceFile.parentFile.absolutePath)
        int importedIndex = -1
        if (!incoming.isEmpty()) {
            String firstImportedName = incoming[0].name as String
            importedIndex = presets.findIndexOf { (it.name ?: '').toString().equalsIgnoreCase(firstImportedName) }
        }
        refreshPresetModel(importedIndex, true)
        String result = "Imported ${added} preset${added == 1 ? '' : 's'}"
        if (replaced) result += ", replaced ${replaced}"
        if (renamed) result += ", renamed ${renamed}"
        if (skipped) result += ", skipped ${skipped}"
        imageStatus.text = result
        imageStatus.foreground = accent
    } catch (Exception error) {
        presets.clear(); presets.addAll(previous)
        refreshPresetModel(presets.isEmpty() ? -1 : 0, true)
        JOptionPane.showMessageDialog(frame, "The imported presets could not be saved.\n${error.message}", 'Import failed', JOptionPane.ERROR_MESSAGE)
    }
}

saveButton.addActionListener {
    Map p = currentPreset()
    int i = selectedPresetIndex()
    if (!validatePreset(p, i, false)) return
    if (i < 0) {
        presets.add(p)
        i = presets.size() - 1
    } else {
        presets[i] = p
    }
    savePresets()
    refreshPresetModel(i, true)
    imageStatus.text = "Saved ‘${p.name}’"
    imageStatus.foreground = accent
}

saveCopyButton.addActionListener {
    Map copy = new LinkedHashMap(currentPreset())
    String suggestedName = "${copy.name} copy"
    String chosenName = JOptionPane.showInputDialog(
        frame,
        'Name the copied preset:',
        suggestedName
    )
    if (chosenName == null) return
    chosenName = chosenName.trim()
    if (!chosenName) {
        JOptionPane.showMessageDialog(frame, 'Enter a name for the copied preset.', 'Preset name required', JOptionPane.WARNING_MESSAGE)
        return
    }
    copy.name = chosenName
    if (!validatePreset(copy, -1, false)) return
    presets.add(copy)
    savePresets()
    refreshPresetModel(presets.size() - 1, true)
    presetList.ensureIndexIsVisible(presetList.selectedIndex)
    imageStatus.text = "Saved copy as ‘${copy.name}’"
    imageStatus.foreground = accent
}

refreshButton.addActionListener { refreshImageStatus() }

Closure applyCalibration = { ImagePlus imp, Map p ->
    IJ.run(imp, 'Set Scale...', buildSetScaleOptions(p))
}
Closure addScaleBar = { ImagePlus imp, Map p ->
    double physicalImageWidth = imp.width * imp.calibration.pixelWidth
    if ((p.width as double) > physicalImageWidth) {
        int answer = JOptionPane.showConfirmDialog(frame,
            "The ${p.width} ${p.scaleUnit} bar is wider than this image (${IJ.d2s(physicalImageWidth, 3)} ${imp.calibration.unit}).\nAdd it anyway?",
            'Scale bar exceeds image width', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (answer != JOptionPane.YES_OPTION) return false
    }
    IJ.run(imp, 'Scale Bar...', buildOptions(p))
    true
}

calibrationOnlyButton.addActionListener {
    ImagePlus imp = WindowManager.currentImage
    if (imp == null) { refreshImageStatus(); return }
    Map p = currentPreset()
    if (!validatePreset(p, selectedPresetIndex(), true)) return
    applyCalibration(imp, p)
    refreshImageStatus()
}

barOnlyButton.addActionListener {
    ImagePlus imp = WindowManager.currentImage
    if (imp == null) { refreshImageStatus(); return }
    Map p = currentPreset()
    if (!validatePreset(p, selectedPresetIndex(), false)) return
    if (!imp.calibration.scaled()) {
        JOptionPane.showMessageDialog(frame, 'The active image is not calibrated. Apply a calibration first.', 'Image is uncalibrated', JOptionPane.WARNING_MESSAGE)
        return
    }
    addScaleBar(imp, p)
    refreshImageStatus()
}

applyButton.addActionListener {
    ImagePlus imp = WindowManager.currentImage
    if (imp == null) { refreshImageStatus(); return }
    Map p = currentPreset()
    if (!validatePreset(p, selectedPresetIndex(), true)) return
    applyCalibration(imp, p)
    addScaleBar(imp, p)
    refreshImageStatus()
}

removeBarButton.addActionListener {
    ImagePlus imp = WindowManager.currentImage
    if (imp == null) { refreshImageStatus(); return }
    def overlay = imp.overlay
    int removed = 0
    if (overlay != null) {
        for (int i = overlay.size()-1; i >= 0; i--) {
            String roiName = overlay.get(i).name ?: ''
            if (roiName == '|SB|' || roiName.startsWith('FijiCal Scale Bar')) {
                overlay.remove(i)
                removed++
            }
        }
        imp.setOverlay(overlay)
        imp.updateAndDraw()
    }
    imageStatus.text = removed ? "Removed ${removed} editable scale-bar element${removed == 1 ? '' : 's'}" : 'No editable scale bar was found on the active image.'
    imageStatus.foreground = removed ? accent : new Color(151, 80, 36)
}

Closure persistWindowState = {
    Rectangle bounds = frame.bounds
    // Regression rule: Swing geometry may arrive as Double under Groovy/HiDPI.
    // Never pass geometry directly to Preferences.putInt; normalize explicitly.
    int windowX = (bounds.x as Number).intValue()
    int windowY = (bounds.y as Number).intValue()
    int windowWidth = (bounds.width as Number).intValue()
    int windowHeight = (bounds.height as Number).intValue()
    prefs.putInt('windowX', windowX)
    prefs.putInt('windowY', windowY)
    prefs.putInt('windowWidth', windowWidth)
    prefs.putInt('windowHeight', windowHeight)
    int actualIndex = selectedPresetIndex()
    if (actualIndex >= 0 && actualIndex < presets.size()) {
        prefs.put('lastPreset', presets[actualIndex].name as String)
    }
}

Closure closeManager = {
    try {
        persistWindowState()
        int selectedIndex = selectedPresetIndex()
        if (selectedIndex >= 0 && selectedIndex < presets.size()) {
            binding.setVariable('fijicalSelectedPresetName', (presets[selectedIndex].name ?: '').toString())
        }
    } finally {
        frame.dispose()
    }
}

doneButton.addActionListener { closeManager() }
frame.rootPane.registerKeyboardAction(
    { closeManager() } as ActionListener,
    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
    JComponent.WHEN_IN_FOCUSED_WINDOW
)

frame.addWindowListener(new WindowAdapter() {
    @Override void windowActivated(WindowEvent e) {
        if (externalOwner == null) refreshImageStatus()
    }
    @Override void windowClosing(WindowEvent e) { closeManager() }
})

if (!presets.isEmpty()) {
    String lastPreset = prefs.get('lastPreset', '')
    int rememberedIndex = presets.findIndexOf { (it.name ?: '') == lastPreset }
    refreshPresetModel(rememberedIndex >= 0 ? rememberedIndex : 0, false)
    int selectedIndex = selectedPresetIndex()
    if (selectedIndex >= 0) showPreset(presets[selectedIndex])
}
if (externalOwner instanceof Frame) {
    imageStatus.text = 'Edit and save presets here. FijiCal Lite refreshes when you click Done.'
    imageStatus.foreground = accent
} else {
    refreshImageStatus()
}
frame.pack()
int savedWidth = prefs.getInt('windowWidth', 0)
int savedHeight = prefs.getInt('windowHeight', 0)
if (externalOwner instanceof Frame) {
    int ownerWidth = (externalOwner as Frame).width
    int ownerHeight = (externalOwner as Frame).height
    int preferredWidth = savedWidth >= frame.minimumSize.width ? savedWidth : frame.width
    int preferredHeight = savedHeight >= frame.minimumSize.height ? savedHeight : frame.height
    int dialogWidth = Math.max(
        frame.minimumSize.width,
        Math.min(preferredWidth, Math.max(frame.minimumSize.width, ownerWidth - 80))
    ) as int
    int dialogHeight = Math.max(
        frame.minimumSize.height,
        Math.min(preferredHeight, Math.max(frame.minimumSize.height, ownerHeight - 80))
    ) as int
    frame.setSize(dialogWidth, dialogHeight)
    frame.setLocationRelativeTo(externalOwner as Frame)
} else if (savedWidth >= frame.minimumSize.width && savedHeight >= frame.minimumSize.height) {
    frame.setBounds(prefs.getInt('windowX', 80), prefs.getInt('windowY', 80), savedWidth, savedHeight)
} else {
    frame.setLocationByPlatform(true)
}
frame.visible = true
frame
