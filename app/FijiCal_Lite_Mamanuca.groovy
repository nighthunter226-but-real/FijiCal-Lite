#@Context context
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
// Distributed without warranty; see LICENSE and COPYRIGHT.md.

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import ij.IJ
import ij.ImagePlus
import ij.CompositeImage
import ij.ImageStack
import ij.gui.Overlay
import ij.gui.Roi
import ij.gui.TextRoi
import ij.io.FileSaver
import ij.plugin.Colors
import ij.plugin.Duplicator
import ij.process.ImageProcessor
import ij.process.ColorProcessor
import ij.measure.Calibration

import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.imageio.ImageIO
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.awt.image.WritableRaster
import java.awt.geom.Point2D
import java.awt.dnd.DropTarget
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.IdentityHashMap
import java.util.List
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences

/* FijiCal Lite 0.9 — Mamanuca microscopy calibration and scale-bar workspace. */

@CompileStatic
class AwtGeometry {
    static int integer(Number value) {
        value == null ? 0 : (Math.round(value.doubleValue()) as int)
    }

    static Rectangle rectangle(Number x, Number y, Number width, Number height) {
        new Rectangle(integer(x),integer(y),integer(width),integer(height))
    }

    static Dimension dimension(Number width, Number height) {
        new Dimension(integer(width),integer(height))
    }

    static Point point(Number x, Number y) {
        new Point(integer(x),integer(y))
    }
}

@CompileStatic
class WindowGeometry {
    static Rectangle usableBounds() {
        Rectangle bounds = GraphicsEnvironment.localGraphicsEnvironment.maximumWindowBounds
        bounds ?: new Rectangle(0, 0, 1280, 720)
    }

    static void fitToScreen(Window window, int wantedWidth, int wantedHeight, int minimumWidth, int minimumHeight) {
        Rectangle usable = usableBounds()
        int width = AwtGeometry.integer(Math.min(wantedWidth, usable.width))
        int height = AwtGeometry.integer(Math.min(wantedHeight, usable.height))
        window.minimumSize = AwtGeometry.dimension(Math.min(minimumWidth, width), Math.min(minimumHeight, height))
        window.size = AwtGeometry.dimension(width, height)
        window.location = AwtGeometry.point(
            usable.x + Math.max(0, (usable.width - width) / 2),
            usable.y + Math.max(0, (usable.height - height) / 2))
    }
}

@CompileStatic
class PhysicalUnits {
    static Double metresPerUnit(String rawUnit) {
        String unit = (rawUnit ?: '').trim().toLowerCase(Locale.ROOT).replace('μ','µ')
        Map<String,Double> factors = ['nm':1e-9d,'um':1e-6d,'µm':1e-6d,'mm':1e-3d,'cm':1e-2d,'m':1d]
        factors[unit]
    }

    static double convert(double value, String fromUnit, String toUnit) {
        if ((fromUnit ?: '').trim().equalsIgnoreCase((toUnit ?: '').trim())) return value
        Double from = metresPerUnit(fromUnit)
        Double to = metresPerUnit(toUnit)
        if (from == null || to == null)
            throw new IllegalArgumentException("Cannot convert scale-bar unit '${fromUnit}' to calibration unit '${toUnit}'.")
        value * from / to
    }
}

class ProcessingManifest {
    static final String SCHEMA = 'org.fijical.processing-manifest'
    static final int SCHEMA_VERSION = 1

    static String pathOf(File file) {
        if (file == null) return null
        try { file.canonicalPath }
        catch (IOException ignored) { file.absolutePath }
    }

    static Map calibrationOf(Calibration calibration) {
        if (calibration == null) return null
        String unit = (calibration.unit ?: 'pixel') as String
        double pixelWidth = calibration.pixelWidth
        double pixelHeight = calibration.pixelHeight
        [
            unit: unit,
            pixelWidth: pixelWidth,
            pixelHeight: pixelHeight,
            pixelDepth: calibration.pixelDepth,
            pixelsPerUnitX: pixelWidth == 0d ? null : 1d / pixelWidth,
            pixelsPerUnitY: pixelHeight == 0d ? null : 1d / pixelHeight,
            xOrigin: calibration.xOrigin,
            yOrigin: calibration.yOrigin
        ]
    }

    static Map presetOf(Map preset, String appliedName) {
        if (preset == null && !appliedName) return null
        Map value = preset ?: Collections.emptyMap()
        [
            name: appliedName ?: value.name,
            collection: value.collection,
            pixelDistance: value.pixelDistance,
            knownDistance: value.knownDistance,
            scaleUnit: value.scaleUnit,
            pixelAspect: value.pixelAspect,
            barWidth: value.width,
            barHeightPixels: value.height,
            labelSize: value.font,
            position: value.location,
            color: value.color,
            background: value.background,
            boldLabel: value.bold,
            serifLabel: value.serif,
            hideLabel: value.hideText,
            editableOverlay: value.overlay
        ].findAll { Object key, Object entry -> entry != null }
    }

    static Map imageRecord(Map values) {
        File source = values.source as File
        File output = values.output as File
        Point customPosition = values.customScaleBarPosition as Point
        Map record = [
            status: (values.status ?: 'UNKNOWN') as String,
            source: [
                fileName: source?.name,
                path: pathOf(source),
                width: values.sourceWidth,
                height: values.sourceHeight,
                bitDepth: values.sourceBitDepth
            ].findAll { Object key, Object entry -> entry != null },
            output: output == null ? null : [
                fileName: output.name,
                path: pathOf(output),
                format: values.format,
                width: values.outputWidth,
                height: values.outputHeight,
                bitDepth: values.outputBitDepth
            ].findAll { Object key, Object entry -> entry != null },
            appliedPreset: presetOf(values.preset as Map, values.presetName as String),
            calibration: calibrationOf(values.calibration as Calibration),
            edits: [
                summary: new ArrayList((values.displayEdits ?: Collections.emptyList()) as Collection),
                nativeReplay: ((values.nativeEdits ?: Collections.emptyList()) as Collection).collect { Object edit ->
                    edit instanceof Map ? new LinkedHashMap(edit as Map) : edit
                }
            ],
            scaleBar: [
                includedInOutput: values.includeScaleBar == true,
                applied: values.preset != null || values.presetName,
                customPositionPixels: customPosition == null ? null : [
                    x: AwtGeometry.integer(customPosition.getX()),
                    y: AwtGeometry.integer(customPosition.getY())
                ]
            ].findAll { Object key, Object entry -> entry != null }
        ]
        if (values.note) record.note = values.note as String
        if (values.error) record.error = values.error as String
        record
    }

    static File write(File manifestFile, String mode, Map exportOptions, List<Map> records, Map summary) {
        File parent = manifestFile.parentFile
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("The manifest folder could not be created: ${parent.absolutePath}")
        Map document = [
            schema: SCHEMA,
            schemaVersion: SCHEMA_VERSION,
            createdUtc: Instant.now().toString(),
            application: [name:'FijiCal Lite', version:'0.9', codename:'Mamanuca'],
            export: [mode:mode, options:new LinkedHashMap(exportOptions ?: Collections.emptyMap())],
            summary: new LinkedHashMap(summary ?: Collections.emptyMap()),
            images: new ArrayList(records ?: Collections.emptyList())
        ]
        manifestFile.withWriter('UTF-8') { Writer writer ->
            writer.write(JsonOutput.prettyPrint(JsonOutput.toJson(document)))
            writer.write(System.lineSeparator())
        }
        manifestFile
    }
}

@CompileStatic
class NativeImageTransforms {
    static BufferedImage copyOf(BufferedImage source) {
        WritableRaster raster = source.copyData(null)
        new BufferedImage(source.colorModel, raster, source.alphaPremultiplied, null)
    }

    private static BufferedImage crop(BufferedImage source, Map operation) {
        int x = Math.max(0,Math.min(source.width - 1,((operation.x ?: 0) as Number).intValue()))
        int y = Math.max(0,Math.min(source.height - 1,((operation.y ?: 0) as Number).intValue()))
        int width = Math.max(1,Math.min(source.width - x,((operation.width ?: source.width) as Number).intValue()))
        int height = Math.max(1,Math.min(source.height - y,((operation.height ?: source.height) as Number).intValue()))
        WritableRaster targetRaster = source.colorModel.createCompatibleWritableRaster(width,height)
        targetRaster.setRect(source.raster.createChild(x,y,width,height,0,0,null))
        new BufferedImage(source.colorModel,targetRaster,source.alphaPremultiplied,null)
    }

    private static void brightnessContrast(BufferedImage image, Map operation) {
        WritableRaster raster = image.raster
        double brightness = ((operation.brightness ?: 0d) as Number).doubleValue() / 100d
        double contrast = Math.max(0d,((operation.contrast ?: 100d) as Number).doubleValue()) / 100d
        int[] sampleSizes = raster.sampleModel.sampleSize
        int colourBands = Math.min(raster.numBands,image.colorModel.numColorComponents)
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                for (int band = 0; band < colourBands; band++) {
                    int bits = Math.max(1,Math.min(30,sampleSizes[Math.min(band,sampleSizes.length - 1)]))
                    double maximum = (1L << bits) - 1L
                    double adjusted = raster.getSampleDouble(x,y,band) * contrast + brightness * maximum
                    raster.setSample(x,y,band,Math.max(0d,Math.min(maximum,adjusted)))
                }
            }
        }
    }

    private static void channelBalance(BufferedImage image, Map operation) {
        WritableRaster raster = image.raster
        double[] factors = [
            Math.max(0d,((operation.red ?: 100d) as Number).doubleValue()) / 100d,
            Math.max(0d,((operation.green ?: 100d) as Number).doubleValue()) / 100d,
            Math.max(0d,((operation.blue ?: 100d) as Number).doubleValue()) / 100d
        ] as double[]
        int[] sampleSizes = raster.sampleModel.sampleSize
        int colourBands = Math.min(3,Math.min(raster.numBands,image.colorModel.numColorComponents))
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                for (int band = 0; band < colourBands; band++) {
                    int bits = Math.max(1,Math.min(30,sampleSizes[Math.min(band,sampleSizes.length - 1)]))
                    double maximum = (1L << bits) - 1L
                    double adjusted = raster.getSampleDouble(x,y,band) * factors[band]
                    raster.setSample(x,y,band,Math.max(0d,Math.min(maximum,adjusted)))
                }
            }
        }
    }

    static BufferedImage apply(BufferedImage source, List operations) {
        BufferedImage current = copyOf(source)
        for (Object entry : operations ?: Collections.emptyList()) {
            Map parameters = entry instanceof Map ? (Map) entry : Collections.emptyMap()
            String operation = entry instanceof Map ? (parameters.type ?: '') as String : entry as String
            if (operation == 'crop') {
                current = crop(current,parameters)
                continue
            }
            if (operation == 'brightnessContrast') {
                brightnessContrast(current,parameters)
                continue
            }
            if (operation == 'channelBalance') {
                channelBalance(current,parameters)
                continue
            }
            boolean quarterTurn = operation == 'rotateRight' || operation == 'rotateLeft'
            int targetWidth = quarterTurn ? current.height : current.width
            int targetHeight = quarterTurn ? current.width : current.height
            WritableRaster targetRaster = current.colorModel.createCompatibleWritableRaster(targetWidth, targetHeight)
            BufferedImage transformed = new BufferedImage(current.colorModel, targetRaster, current.alphaPremultiplied, null)
            WritableRaster sourceRaster = current.raster
            Object pixelData = null
            for (int sourceY = 0; sourceY < current.height; sourceY++) {
                for (int sourceX = 0; sourceX < current.width; sourceX++) {
                    int targetX
                    int targetY
                    switch (operation) {
                        case 'rotateRight':
                            targetX = current.height - 1 - sourceY
                            targetY = sourceX
                            break
                        case 'rotateLeft':
                            targetX = sourceY
                            targetY = current.width - 1 - sourceX
                            break
                        case 'flipHorizontal':
                            targetX = current.width - 1 - sourceX
                            targetY = sourceY
                            break
                        case 'flipVertical':
                            targetX = sourceX
                            targetY = current.height - 1 - sourceY
                            break
                        default:
                            throw new IllegalArgumentException("Unknown native image edit: ${operation}")
                    }
                    pixelData = sourceRaster.getDataElements(sourceX, sourceY, pixelData)
                    targetRaster.setDataElements(targetX, targetY, pixelData)
                }
            }
            current = transformed
        }
        current
    }
}

@CompileStatic
class BasicImageEdits {
    private static void brightnessContrastProcessor(ImageProcessor processor, double brightnessPercent, double contrastPercent) {
        double contrast = Math.max(0d, contrastPercent) / 100d
        double range = processor.bitDepth == 16 ? 65535d : processor.bitDepth == 24 ? 255d : processor.bitDepth == 32 ? 1d : 255d
        processor.multiply(contrast)
        processor.add((brightnessPercent / 100d) * range)
    }

    static void brightnessContrast(ImagePlus image, double brightnessPercent, double contrastPercent) {
        if (image == null || image.getStack() == null) return
        for (int index = 1; index <= image.getStackSize(); index++) {
            brightnessContrastProcessor(image.getStack().getProcessor(index),brightnessPercent,contrastPercent)
        }
    }

    private static void packedChannelBalance(ColorProcessor processor, double redPercent, double greenPercent, double bluePercent) {
        int[] pixels = (int[]) processor.pixels
        double red = Math.max(0d, redPercent) / 100d
        double green = Math.max(0d, greenPercent) / 100d
        double blue = Math.max(0d, bluePercent) / 100d
        for (int index = 0; index < pixels.length; index++) {
            int value = pixels[index]
            int r = Math.min(255, Math.round(((value >> 16) & 0xff) * red) as int)
            int g = Math.min(255, Math.round(((value >> 8) & 0xff) * green) as int)
            int b = Math.min(255, Math.round((value & 0xff) * blue) as int)
            pixels[index] = ((value & 0xff000000) | (r << 16) | (g << 8) | b) as int
        }
    }

    static boolean supportsChannelBalance(ImagePlus image) {
        image != null && ((image.getProcessor() instanceof ColorProcessor) || image.getNChannels() >= 3)
    }

    static void channelBalance(ImagePlus image, double redPercent, double greenPercent, double bluePercent) {
        if (image == null) return
        if (image.getProcessor() instanceof ColorProcessor) {
            packedChannelBalance(image.getProcessor() as ColorProcessor,redPercent,greenPercent,bluePercent)
            return
        }
        if (image.getNChannels() < 3) throw new IllegalArgumentException('This image does not contain three colour channels')
        double[] factors = [Math.max(0d,redPercent)/100d, Math.max(0d,greenPercent)/100d, Math.max(0d,bluePercent)/100d] as double[]
        for (int frame = 1; frame <= image.getNFrames(); frame++) {
            for (int slice = 1; slice <= image.getNSlices(); slice++) {
                for (int channel = 1; channel <= 3; channel++) {
                    int stackIndex = image.getStackIndex(channel,slice,frame)
                    image.getStack().getProcessor(stackIndex).multiply(factors[channel-1])
                }
            }
        }
    }

    static void restoreImageState(ImagePlus target, ImagePlus source, String originalTitle,
                                  Calibration calibration, int channel, int slice, int frame) {
        if (target == null || source == null) throw new IllegalArgumentException('The image snapshot is missing')
        int compositeMode = source instanceof CompositeImage ? (source as CompositeImage).getMode() : -1
        double displayMin = source.getDisplayRangeMin()
        double displayMax = source.getDisplayRangeMax()
        Overlay restoredOverlay = source.getOverlay() == null ? null : source.getOverlay().duplicate()

        target.setImage(source)
        target.setTitle(originalTitle ?: source.getTitle())
        if (calibration != null) target.setCalibration(calibration.copy())
        target.setOverlay(restoredOverlay)
        target.setPosition(
            Math.max(1,Math.min(target.getNChannels(),channel)),
            Math.max(1,Math.min(target.getNSlices(),slice)),
            Math.max(1,Math.min(target.getNFrames(),frame)))

        if (target instanceof CompositeImage && source instanceof CompositeImage) {
            CompositeImage compositeTarget = target as CompositeImage
            compositeTarget.setMode(compositeMode)
            compositeTarget.copyLuts(source)
            compositeTarget.setChannelsUpdated()
            compositeTarget.updateImage()
            compositeTarget.updateAllChannelsAndDraw()
        } else {
            target.setDisplayRange(displayMin,displayMax)
            target.updateAndDraw()
        }
    }

    static void transformStack(ImagePlus image, String operation) {
        if (image == null || image.getStack() == null) return
        if (!(operation in ['rotateRight','rotateLeft','flipHorizontal','flipVertical']))
            throw new IllegalArgumentException("Unknown image edit: ${operation}")

        int oldWidth = image.getWidth()
        int oldHeight = image.getHeight()
        int channels = image.getNChannels()
        int slices = image.getNSlices()
        int frames = image.getNFrames()
        int currentChannel = image.getC()
        int currentSlice = image.getZ()
        int currentFrame = image.getT()
        String title = image.getTitle()
        Calibration calibration = image.getCalibration().copy()
        boolean quarterTurn = operation == 'rotateRight' || operation == 'rotateLeft'
        int targetWidth = quarterTurn ? oldHeight : oldWidth
        int targetHeight = quarterTurn ? oldWidth : oldHeight
        ImageStack transformedStack = new ImageStack(targetWidth,targetHeight)

        for (int index = 1; index <= image.getStackSize(); index++) {
            ImageProcessor source = image.getStack().getProcessor(index)
            ImageProcessor transformed
            if (operation == 'rotateRight') transformed = source.rotateRight()
            else if (operation == 'rotateLeft') transformed = source.rotateLeft()
            else {
                transformed = source.duplicate()
                if (operation == 'flipHorizontal') transformed.flipHorizontal()
                else transformed.flipVertical()
            }
            transformedStack.addSlice(image.getStack().getSliceLabel(index),transformed)
        }

        int compositeMode = image instanceof CompositeImage ? (image as CompositeImage).getMode() : -1
        def compositeLuts = image instanceof CompositeImage ? (image as CompositeImage).getLuts() : null
        double displayMin = image.getDisplayRangeMin()
        double displayMax = image.getDisplayRangeMax()
        image.setStack(transformedStack,channels,slices,frames)
        image.setTitle(title)

        double oldPixelWidth = calibration.pixelWidth
        double oldPixelHeight = calibration.pixelHeight
        double oldXOrigin = calibration.xOrigin
        double oldYOrigin = calibration.yOrigin
        if (operation == 'rotateRight') {
            calibration.pixelWidth = oldPixelHeight
            calibration.pixelHeight = oldPixelWidth
            calibration.xOrigin = oldHeight - 1d - oldYOrigin
            calibration.yOrigin = oldXOrigin
        } else if (operation == 'rotateLeft') {
            calibration.pixelWidth = oldPixelHeight
            calibration.pixelHeight = oldPixelWidth
            calibration.xOrigin = oldYOrigin
            calibration.yOrigin = oldWidth - 1d - oldXOrigin
        } else if (operation == 'flipHorizontal') {
            calibration.xOrigin = oldWidth - 1d - oldXOrigin
        } else if (operation == 'flipVertical') {
            calibration.yOrigin = oldHeight - 1d - oldYOrigin
        }
        image.setCalibration(calibration)
        image.setPosition(currentChannel,currentSlice,currentFrame)

        if (image instanceof CompositeImage) {
            CompositeImage composite = image as CompositeImage
            if (compositeLuts != null) composite.setLuts(compositeLuts)
            composite.setMode(compositeMode)
            composite.setChannelsUpdated()
            composite.updateImage()
            composite.updateAllChannelsAndDraw()
        } else {
            image.setDisplayRange(displayMin,displayMax)
            image.updateAndDraw()
        }
    }
}

@CompileStatic
class ScaleBarOverlayTools {
    static boolean isScaleBar(Roi roi) {
        roi != null && (roi.name ?: '').startsWith('FijiCal Scale Bar')
    }

    static Rectangle bounds(Overlay overlay) {
        if (overlay == null) return null
        Rectangle combined = null
        for (int index = 0; index < overlay.size(); index++) {
            Roi roi = overlay.get(index)
            if (!isScaleBar(roi)) continue
            Rectangle roiBounds = roi.getBounds()
            combined = combined == null ? new Rectangle(roiBounds) : combined.union(roiBounds)
        }
        combined
    }

    static void translate(Overlay overlay, int deltaX, int deltaY) {
        if (overlay == null || (deltaX == 0 && deltaY == 0)) return
        for (int index = 0; index < overlay.size(); index++) {
            Roi roi = overlay.get(index)
            if (!isScaleBar(roi)) continue
            Rectangle roiBounds = roi.getBounds()
            roi.setLocation(roiBounds.x + deltaX,roiBounds.y + deltaY)
        }
    }

    static void draw(Graphics2D graphics, Overlay overlay, int deltaX=0, int deltaY=0) {
        if (graphics == null || overlay == null) return
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        for (int index = 0; index < overlay.size(); index++) {
            Roi roi = overlay.get(index)
            if (!isScaleBar(roi)) continue
            Rectangle roiBounds = roi.getBounds()
            int roiX = (Math.round(roiBounds.getX()) as int) + deltaX
            int roiY = (Math.round(roiBounds.getY()) as int) + deltaY
            int roiWidth = Math.round(roiBounds.getWidth()) as int
            int roiHeight = Math.round(roiBounds.getHeight()) as int
            Color roiColor = roi.fillColor ?: roi.strokeColor ?: Color.WHITE
            graphics.color = roiColor
            if (roi instanceof TextRoi) {
                TextRoi textRoi = roi as TextRoi
                Font font = textRoi.currentFont
                graphics.font = font
                FontMetrics metrics = graphics.getFontMetrics(font)
                int baseline = roiY + metrics.ascent
                (textRoi.text ?: '').split(/\n/, -1).eachWithIndex { String line, int lineIndex ->
                    graphics.drawString(line,roiX,baseline + lineIndex * metrics.height)
                }
            } else {
                graphics.fillRect(roiX,roiY,roiWidth,roiHeight)
            }
        }
    }
}

class ZoomCanvas extends JPanel implements MouseWheelListener, MouseListener, MouseMotionListener {
    private static final int CROP_NONE = 0
    private static final int CROP_MOVE = 1
    private static final int CROP_CREATE = 2
    private static final int CROP_NW = 3
    private static final int CROP_N = 4
    private static final int CROP_NE = 5
    private static final int CROP_E = 6
    private static final int CROP_SE = 7
    private static final int CROP_S = 8
    private static final int CROP_SW = 9
    private static final int CROP_W = 10
    private static final int CROP_HANDLE_SIZE = 12
    private static final int CROP_HANDLE_HIT_RADIUS = 12

    BufferedImage image
    double zoom = 1d
    int offsetX = 0
    int offsetY = 0
    boolean fitMode = true
    Point dragOrigin
    Point cropAnchor
    Rectangle cropStartSelection
    Rectangle cropSelection
    int cropDragMode = CROP_NONE
    boolean cropMode = false
    Overlay scaleBarOverlay
    boolean scaleBarDraggable = false
    boolean scaleBarDragging = false
    boolean scaleBarHovered = false
    Point scaleBarDragAnchor
    Rectangle scaleBarStartBounds
    int scaleBarPreviewDeltaX = 0
    int scaleBarPreviewDeltaY = 0
    Color emptyTextColor
    Closure viewChanged
    Closure cropChanged
    Closure scaleBarMoved

    ZoomCanvas(Color backgroundColor, Color textColor) {
        background = backgroundColor
        emptyTextColor = textColor
        addMouseWheelListener(this)
        addMouseListener(this)
        addMouseMotionListener(this)
    }

    void setImage(BufferedImage nextImage, boolean resetView) {
        image = nextImage
        if (resetView) fitMode = true
        repaint()
    }

    void setScaleBarOverlay(Overlay nextOverlay, boolean draggable) {
        scaleBarOverlay = nextOverlay
        scaleBarDraggable = draggable && ScaleBarOverlayTools.bounds(nextOverlay) != null
        scaleBarDragging = false
        scaleBarHovered = false
        scaleBarDragAnchor = null
        scaleBarStartBounds = null
        scaleBarPreviewDeltaX = 0
        scaleBarPreviewDeltaY = 0
        repaint()
    }

    void fitImage() {
        fitMode = true
        repaint()
        viewChanged?.call()
    }

    void actualSize() {
        if (image == null) return
        fitMode = false
        zoom = 1d
        centreImage()
        repaint()
        viewChanged?.call()
    }

    void beginCrop() {
        if (image == null) return
        cropMode = true
        cropAnchor = null
        cropStartSelection = null
        cropDragMode = CROP_NONE
        cropSelection = AwtGeometry.rectangle(0,0,image.width,image.height)
        cursor = Cursor.getDefaultCursor()
        repaint()
        cropChanged?.call(cropSelection)
    }

    void cancelCrop() {
        cropMode = false
        cropAnchor = null
        cropStartSelection = null
        cropSelection = null
        cropDragMode = CROP_NONE
        cursor = Cursor.getDefaultCursor()
        repaint()
        cropChanged?.call(cropSelection)
    }

    Rectangle takeCropSelection() {
        Rectangle selected = cropSelection == null ? null : new Rectangle(cropSelection)
        cancelCrop()
        selected
    }

    private Point imageEdgePoint(Point point) {
        if (image == null) return AwtGeometry.point(0,0)
        int x = Math.max(0, Math.min(image.width, Math.round((point.x - offsetX) / zoom) as int))
        int y = Math.max(0, Math.min(image.height, Math.round((point.y - offsetY) / zoom) as int))
        AwtGeometry.point(x,y)
    }

    private Rectangle cropScreenBounds() {
        if (cropSelection == null) return null
        int x = offsetX + (Math.round(cropSelection.x * zoom) as int)
        int y = offsetY + (Math.round(cropSelection.y * zoom) as int)
        int w = Math.max(1, Math.round(cropSelection.width * zoom) as int)
        int h = Math.max(1, Math.round(cropSelection.height * zoom) as int)
        AwtGeometry.rectangle(x,y,w,h)
    }

    private Rectangle scaleBarScreenBounds() {
        Rectangle bounds = ScaleBarOverlayTools.bounds(scaleBarOverlay)
        if (bounds == null) return null
        int x = offsetX + Math.round((bounds.x + scaleBarPreviewDeltaX) * zoom) as int
        int y = offsetY + Math.round((bounds.y + scaleBarPreviewDeltaY) * zoom) as int
        int width = Math.max(1,Math.round(bounds.width * zoom) as int)
        int height = Math.max(1,Math.round(bounds.height * zoom) as int)
        AwtGeometry.rectangle(x,y,width,height)
    }

    private boolean scaleBarHit(Point point) {
        Rectangle bounds = scaleBarScreenBounds()
        if (bounds == null) return false
        int expansion = Math.max(6,Math.round(6d / Math.max(0.25d,zoom)) as int)
        Rectangle hit = new Rectangle(bounds)
        hit.grow(expansion,expansion)
        hit.contains(point)
    }

    private void updateScaleBarDrag(Point end) {
        if (!scaleBarDragging || scaleBarDragAnchor == null || scaleBarStartBounds == null || image == null) return
        int desiredX = scaleBarStartBounds.x + end.x - scaleBarDragAnchor.x
        int desiredY = scaleBarStartBounds.y + end.y - scaleBarDragAnchor.y
        int clampedX = Math.max(0,Math.min(image.width - scaleBarStartBounds.width,desiredX))
        int clampedY = Math.max(0,Math.min(image.height - scaleBarStartBounds.height,desiredY))
        scaleBarPreviewDeltaX = clampedX - scaleBarStartBounds.x
        scaleBarPreviewDeltaY = clampedY - scaleBarStartBounds.y
        repaint()
    }

    private List<Point> cropHandleCentres(Rectangle bounds) {
        int left = bounds.x
        int top = bounds.y
        int right = bounds.x + bounds.width
        int bottom = bounds.y + bounds.height
        int middleX = left + (bounds.width / 2 as int)
        int middleY = top + (bounds.height / 2 as int)
        [AwtGeometry.point(left,top), AwtGeometry.point(middleX,top), AwtGeometry.point(right,top),
         AwtGeometry.point(right,middleY), AwtGeometry.point(right,bottom), AwtGeometry.point(middleX,bottom),
         AwtGeometry.point(left,bottom), AwtGeometry.point(left,middleY)] as List<Point>
    }

    private int cropHandleAt(Point point) {
        Rectangle bounds = cropScreenBounds()
        if (bounds == null) return CROP_NONE
        List<Point> centres = cropHandleCentres(bounds)
        int[] modes = [CROP_NW,CROP_N,CROP_NE,CROP_E,CROP_SE,CROP_S,CROP_SW,CROP_W] as int[]
        for (int index = 0; index < centres.size(); index++) {
            Point centre = centres[index]
            if (Math.abs(point.x - centre.x) <= CROP_HANDLE_HIT_RADIUS &&
                Math.abs(point.y - centre.y) <= CROP_HANDLE_HIT_RADIUS) return modes[index]
        }
        CROP_NONE
    }

    private Cursor cropCursor(int mode) {
        switch (mode) {
            case CROP_MOVE: return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            case CROP_NW:
            case CROP_SE: return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
            case CROP_N:
            case CROP_S: return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            case CROP_NE:
            case CROP_SW: return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
            case CROP_E:
            case CROP_W: return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            default: return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        }
    }

    private void setCropSelection(Rectangle selection) {
        cropSelection = selection
        cropChanged?.call(cropSelection)
        repaint()
    }

    private void updateCropDrag(Point end) {
        if (cropAnchor == null || cropStartSelection == null || image == null) return
        int minSize = Math.max(2, Math.ceil(12d / Math.max(0.02d,zoom)) as int)
        int left = cropStartSelection.x
        int top = cropStartSelection.y
        int right = cropStartSelection.x + cropStartSelection.width
        int bottom = cropStartSelection.y + cropStartSelection.height

        if (cropDragMode == CROP_CREATE) {
            left = Math.min(cropAnchor.x,end.x)
            top = Math.min(cropAnchor.y,end.y)
            right = Math.max(cropAnchor.x,end.x)
            bottom = Math.max(cropAnchor.y,end.y)
        } else if (cropDragMode == CROP_MOVE) {
            int nextX = Math.max(0,Math.min(image.width - cropStartSelection.width,
                cropStartSelection.x + end.x - cropAnchor.x))
            int nextY = Math.max(0,Math.min(image.height - cropStartSelection.height,
                cropStartSelection.y + end.y - cropAnchor.y))
            setCropSelection(AwtGeometry.rectangle(nextX,nextY,cropStartSelection.getWidth(),cropStartSelection.getHeight()))
            return
        } else {
            if (cropDragMode in [CROP_NW,CROP_W,CROP_SW]) left = Math.max(0,Math.min(end.x,right-minSize))
            if (cropDragMode in [CROP_NE,CROP_E,CROP_SE]) right = Math.min(image.width,Math.max(end.x,left+minSize))
            if (cropDragMode in [CROP_NW,CROP_N,CROP_NE]) top = Math.max(0,Math.min(end.y,bottom-minSize))
            if (cropDragMode in [CROP_SW,CROP_S,CROP_SE]) bottom = Math.min(image.height,Math.max(end.y,top+minSize))
        }
        if (right - left < minSize) right = Math.min(image.width,left+minSize)
        if (bottom - top < minSize) bottom = Math.min(image.height,top+minSize)
        setCropSelection(AwtGeometry.rectangle(left,top,Math.max(1,right-left),Math.max(1,bottom-top)))
    }

    void centreImage() {
        if (image == null) return
        offsetX = (width - Math.round(image.width * zoom) as int) / 2
        offsetY = (height - Math.round(image.height * zoom) as int) / 2
    }

    void calculateFit() {
        if (image == null || width <= 0 || height <= 0) return
        zoom = Math.min((width - 30d) / image.width, (height - 30d) / image.height)
        zoom = Math.max(0.01d, Math.min(1d, zoom))
        centreImage()
    }

    void zoomAt(double factor, Point pivot) {
        if (image == null) return
        if (fitMode) { calculateFit(); fitMode = false }
        double oldZoom = zoom
        double nextZoom = Math.max(0.02d, Math.min(16d, oldZoom * factor))
        double imageX = (pivot.x - offsetX) / oldZoom
        double imageY = (pivot.y - offsetY) / oldZoom
        zoom = nextZoom
        offsetX = Math.round(pivot.x - imageX * zoom) as int
        offsetY = Math.round(pivot.y - imageY * zoom) as int
        repaint()
        viewChanged?.call()
    }

    int zoomPercent() {
        if (fitMode) calculateFit()
        Math.round(zoom * 100d) as int
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics)
        def g = graphics as Graphics2D
        if (image == null) {
            g.color = emptyTextColor
            String message = 'Drop microscopy images here or use File → Open Images…'
            def metrics = g.fontMetrics
            g.drawString(message, (width - metrics.stringWidth(message)) / 2, height / 2)
            return
        }
        if (fitMode) calculateFit()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            zoom < 1d ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        int drawWidth = Math.round(image.width * zoom) as int
        int drawHeight = Math.round(image.height * zoom) as int
        g.drawImage(image, offsetX, offsetY, drawWidth, drawHeight, null)
        if (scaleBarOverlay != null) {
            Graphics2D overlayGraphics = g.create() as Graphics2D
            try {
                overlayGraphics.translate(offsetX,offsetY)
                overlayGraphics.scale(zoom,zoom)
                ScaleBarOverlayTools.draw(overlayGraphics,scaleBarOverlay,scaleBarPreviewDeltaX,scaleBarPreviewDeltaY)
            } finally {
                overlayGraphics.dispose()
            }
            if (scaleBarDraggable && (scaleBarHovered || scaleBarDragging)) {
                Rectangle scaleBounds = scaleBarScreenBounds()
                if (scaleBounds != null) {
                    int outlineX = Math.round(scaleBounds.getX()) as int
                    int outlineY = Math.round(scaleBounds.getY()) as int
                    int outlineWidth = Math.round(scaleBounds.getWidth()) as int
                    int outlineHeight = Math.round(scaleBounds.getHeight()) as int
                    g.color = new Color(34,185,169,220)
                    g.stroke = new BasicStroke(1.5f)
                    g.drawRect(outlineX-3,outlineY-3,outlineWidth+6,outlineHeight+6)
                }
            }
        }
        if (cropMode && cropSelection != null) {
            Rectangle bounds = cropScreenBounds()
            int x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height
            int imageRight = offsetX + drawWidth
            int imageBottom = offsetY + drawHeight
            g.color = new Color(0,0,0,145)
            g.fillRect(offsetX,offsetY,drawWidth,Math.max(0,y-offsetY))
            g.fillRect(offsetX,y+h,drawWidth,Math.max(0,imageBottom-(y+h)))
            g.fillRect(offsetX,y,Math.max(0,x-offsetX),h)
            g.fillRect(x+w,y,Math.max(0,imageRight-(x+w)),h)
            g.color = new Color(34, 185, 169, 220)
            g.stroke = new BasicStroke(2f)
            g.drawRect(x,y,w,h)
            g.color = new Color(255,255,255,80)
            g.stroke = new BasicStroke(1f)
            g.drawLine(x + (w / 3 as int),y,x + (w / 3 as int),y+h)
            g.drawLine(x + ((w * 2) / 3 as int),y,x + ((w * 2) / 3 as int),y+h)
            g.drawLine(x,y + (h / 3 as int),x+w,y + (h / 3 as int))
            g.drawLine(x,y + ((h * 2) / 3 as int),x+w,y + ((h * 2) / 3 as int))
            int halfHandle = CROP_HANDLE_SIZE / 2 as int
            for (Point centre : cropHandleCentres(bounds)) {
                int handleX = centre.x - halfHandle
                int handleY = centre.y - halfHandle
                g.color = new Color(245,248,248)
                g.fillRect(handleX,handleY,CROP_HANDLE_SIZE,CROP_HANDLE_SIZE)
                g.color = new Color(34,185,169)
                g.drawRect(handleX,handleY,CROP_HANDLE_SIZE,CROP_HANDLE_SIZE)
            }
        }
    }

    @Override void mouseWheelMoved(MouseWheelEvent event) {
        zoomAt(event.preciseWheelRotation < 0 ? 1.25d : 0.8d, event.point)
    }
    @Override void mousePressed(MouseEvent event) {
        if (image == null) return
        if (cropMode && SwingUtilities.isLeftMouseButton(event)) {
            cropAnchor = imageEdgePoint(event.point)
            cropStartSelection = cropSelection == null ? AwtGeometry.rectangle(cropAnchor.x,cropAnchor.y,1,1) : new Rectangle(cropSelection)
            cropDragMode = cropHandleAt(event.point)
            Rectangle bounds = cropScreenBounds()
            if (cropDragMode == CROP_NONE && bounds != null && bounds.contains(event.point)) cropDragMode = CROP_MOVE
            if (cropDragMode == CROP_NONE) {
                cropDragMode = CROP_CREATE
                cropStartSelection = AwtGeometry.rectangle(cropAnchor.x,cropAnchor.y,1,1)
                setCropSelection(AwtGeometry.rectangle(cropAnchor.x,cropAnchor.y,1,1))
            }
            cursor = cropCursor(cropDragMode)
            return
        }
        if (SwingUtilities.isLeftMouseButton(event) && scaleBarDraggable && scaleBarHit(event.point)) {
            scaleBarDragAnchor = imageEdgePoint(event.point)
            scaleBarStartBounds = ScaleBarOverlayTools.bounds(scaleBarOverlay)
            scaleBarDragging = scaleBarStartBounds != null
            scaleBarPreviewDeltaX = 0
            scaleBarPreviewDeltaY = 0
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            repaint()
            return
        }
        dragOrigin = event.point
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }
    @Override void mouseDragged(MouseEvent event) {
        if (image == null) return
        if (cropMode) {
            updateCropDrag(imageEdgePoint(event.point))
            return
        }
        if (scaleBarDragging) {
            updateScaleBarDrag(imageEdgePoint(event.point))
            return
        }
        if (dragOrigin == null) return
        fitMode = false
        offsetX += event.x - dragOrigin.x
        offsetY += event.y - dragOrigin.y
        dragOrigin = event.point
        repaint()
    }
    @Override void mouseReleased(MouseEvent event) {
        if (cropMode) {
            cropAnchor = null
            cropStartSelection = null
            cropDragMode = CROP_NONE
            mouseMoved(event)
            return
        }
        if (scaleBarDragging) {
            int deltaX = scaleBarPreviewDeltaX
            int deltaY = scaleBarPreviewDeltaY
            scaleBarDragging = false
            scaleBarDragAnchor = null
            scaleBarStartBounds = null
            scaleBarPreviewDeltaX = 0
            scaleBarPreviewDeltaY = 0
            scaleBarMoved?.call(deltaX,deltaY)
            scaleBarHovered = scaleBarHit(event.point)
            cursor = scaleBarHovered ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor()
            repaint()
            return
        }
        dragOrigin = null
        cursor = Cursor.getDefaultCursor()
        viewChanged?.call()
    }
    @Override void mouseClicked(MouseEvent event) {}
    @Override void mouseEntered(MouseEvent event) {}
    @Override void mouseExited(MouseEvent event) {}
    @Override void mouseMoved(MouseEvent event) {
        if (image == null) return
        if (cropMode) {
            int mode = cropHandleAt(event.point)
            Rectangle bounds = cropScreenBounds()
            if (mode == CROP_NONE && bounds != null && bounds.contains(event.point)) mode = CROP_MOVE
            cursor = cropCursor(mode)
            return
        }
        scaleBarHovered = scaleBarDraggable && scaleBarHit(event.point)
        cursor = scaleBarHovered ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor()
        repaint()
    }
}

@CompileStatic
class FloatingCanvasLayer extends JLayeredPane {
    final JComponent canvas
    final JComponent palette

    FloatingCanvasLayer(JComponent canvas, JComponent palette) {
        this.canvas = canvas
        this.palette = palette
        setLayout(null)
        setOpaque(false)
        add(canvas)
        setLayer(canvas,JLayeredPane.DEFAULT_LAYER.intValue())
        add(palette)
        setLayer(palette,JLayeredPane.PALETTE_LAYER.intValue())
        moveToFront(palette)
    }

    @Override void doLayout() {
        canvas.setBounds(0,0,getWidth(),getHeight())
        Dimension preferred = palette.getPreferredSize()
        int paletteWidth = AwtGeometry.integer(preferred.getWidth())
        int paletteHeight = AwtGeometry.integer(preferred.getHeight())
        palette.setBounds(16,18,paletteWidth,paletteHeight)
        palette.setVisible(true)
        moveToFront(palette)
    }
}

@CompileStatic
class FloatingToolPalette extends JPanel {
    final Color fillColor
    final Color borderColor

    FloatingToolPalette(Color fillColor, Color borderColor) {
        this.fillColor = fillColor
        this.borderColor = borderColor
        setOpaque(false)
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS))
        setBorder(new EmptyBorder(7,6,11,10))
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            int panelWidth = Math.max(1,getWidth()-5)
            int panelHeight = Math.max(1,getHeight()-6)
            g.color = new Color(0,0,0,105)
            g.fillRoundRect(4,5,panelWidth,panelHeight,12,12)
            g.color = fillColor
            g.fillRoundRect(0,0,panelWidth,panelHeight,12,12)
            g.color = borderColor
            g.drawRoundRect(0,0,panelWidth-1,panelHeight-1,12,12)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}

@CompileStatic
class PaletteToolIcon implements Icon {
    final String kind

    PaletteToolIcon(String kind) { this.kind = kind }
    @Override int getIconWidth() { 24 }
    @Override int getIconHeight() { 24 }

    @Override void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = graphics.create() as Graphics2D
        try {
            g.translate(x,y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = component.foreground
            g.stroke = new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
            if (kind == 'pointer') {
                Polygon arrow = new Polygon(
                    [4,4,9,12,15,12,20] as int[],
                    [2,20,15,22,20,14,14] as int[],7)
                g.fillPolygon(arrow)
            } else if (kind == 'crop') {
                g.drawLine(5,3,5,18); g.drawLine(5,18,20,18)
                g.drawLine(3,7,16,7); g.drawLine(16,7,16,21)
            } else if (kind == 'rotate') {
                g.drawArc(4,4,16,16,35,285)
                Polygon head = new Polygon([17,22,21] as int[],[3,5,9] as int[],3)
                g.fillPolygon(head)
            } else if (kind == 'measure') {
                g.drawLine(5,18,19,4)
                g.drawLine(3,16,7,20); g.drawLine(17,2,21,6)
                g.drawLine(9,13,12,16); g.drawLine(13,9,16,12)
            }
        } finally {
            g.dispose()
        }
    }
}

class GridThumbnail extends JPanel {
    BufferedImage image
    double cellFill = 0.8d

    GridThumbnail(BufferedImage source, Color backgroundColor) {
        image = source
        background = backgroundColor
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics)
        if (image == null || width <= 0 || height <= 0) return
        Graphics2D g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            int availableWidth = Math.max(1, Math.floor(width * cellFill) as int)
            int availableHeight = Math.max(1, Math.floor(height * cellFill) as int)
            double scale = Math.min(availableWidth / (double) image.width, availableHeight / (double) image.height)
            int drawWidth = Math.max(1, Math.round(image.width * scale) as int)
            int drawHeight = Math.max(1, Math.round(image.height * scale) as int)
            int drawX = (width - drawWidth) / 2
            int drawY = (height - drawHeight) / 2
            g.drawImage(image, drawX, drawY, drawWidth, drawHeight, null)
        } finally {
            g.dispose()
        }
    }
}

class SelectionBadge extends JComponent {
    Color accent
    SelectionBadge(Color accentColor) { accent=accentColor; preferredSize=AwtGeometry.dimension(22,22); minimumSize=preferredSize }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g=graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
            g.color=accent; g.fillRect(0,0,width,height)
            g.color=Color.WHITE; g.stroke=new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)
            int midY=(height/2) as int
            g.drawLine(5,midY,9,midY+4); g.drawLine(9,midY+4,width-5,6)
        } finally { g.dispose() }
    }
}

class CalibrationCanvas extends JPanel implements MouseWheelListener, MouseListener, MouseMotionListener {
    BufferedImage image
    double zoom = 1d
    int offsetX = 0
    int offsetY = 0
    boolean fitMode = true
    Point dragOrigin
    Point2D.Double lineStart
    Point2D.Double lineEnd
    int activeHandle = -1
    boolean drawingLine = false
    boolean panning = false
    Closure measurementChanged

    CalibrationCanvas(Color backgroundColor) {
        background = backgroundColor
        addMouseWheelListener(this); addMouseListener(this); addMouseMotionListener(this)
    }
    void setImage(BufferedImage nextImage) { image=nextImage; clearLine(); fitMode=true; repaint() }
    void clearLine() { lineStart=null; lineEnd=null; activeHandle=-1; measurementChanged?.call(); repaint() }
    void fitImage() { fitMode=true; repaint() }
    void calculateFit() {
        if (!image || width<=0 || height<=0) return
        zoom=Math.max(0.01d,Math.min(1d,Math.min((width-40d)/image.width,(height-40d)/image.height)))
        offsetX=((width-Math.round(image.width*zoom) as int)/2) as int
        offsetY=((height-Math.round(image.height*zoom) as int)/2) as int
    }
    Point2D.Double screenToImage(Point p) {
        if (!image) return null
        new Point2D.Double(Math.max(0d,Math.min(image.width-1d,(p.x-offsetX)/zoom)),
            Math.max(0d,Math.min(image.height-1d,(p.y-offsetY)/zoom)))
    }
    Point2D.Double constrainToAxis(Point2D.Double anchor, Point2D.Double candidate) {
        if (anchor == null || candidate == null) return candidate
        double dx = candidate.x - anchor.x
        double dy = candidate.y - anchor.y
        Math.abs(dx) >= Math.abs(dy) ? new Point2D.Double(candidate.x, anchor.y) : new Point2D.Double(anchor.x, candidate.y)
    }
    Point imageToScreen(Point2D.Double p) { AwtGeometry.point(offsetX+p.x*zoom,offsetY+p.y*zoom) }
    double measuredPixels() { lineStart && lineEnd ? lineStart.distance(lineEnd) : 0d }
    void zoomAt(double factor, Point pivot) {
        if (!image) return
        if (fitMode) { calculateFit(); fitMode=false }
        double old=zoom, next=Math.max(0.02d,Math.min(24d,old*factor))
        double ix=(pivot.x-offsetX)/old, iy=(pivot.y-offsetY)/old
        zoom=next; offsetX=Math.round(pivot.x-ix*zoom) as int; offsetY=Math.round(pivot.y-iy*zoom) as int
        repaint()
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics)
        Graphics2D g=graphics.create() as Graphics2D
        try {
            if (!image) { g.color=Color.LIGHT_GRAY; g.drawString('Choose a calibration image to begin.',20,30); return }
            if (fitMode) calculateFit()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom<1d ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(image,offsetX,offsetY,Math.round(image.width*zoom) as int,Math.round(image.height*zoom) as int,null)
            if (lineStart && lineEnd) {
                Point a=imageToScreen(lineStart), b=imageToScreen(lineEnd)
                int ax=(a.x as Number).intValue(), ay=(a.y as Number).intValue()
                int bx=(b.x as Number).intValue(), by=(b.y as Number).intValue()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON)
                g.stroke=new BasicStroke(3f); g.color=new Color(0,0,0,180); g.drawLine(ax+1,ay+1,bx+1,by+1)
                g.stroke=new BasicStroke(2f); g.color=new Color(38,176,156); g.drawLine(ax,ay,bx,by)
                [[ax,ay],[bx,by]].each { List<Integer> p -> int px=p[0], py=p[1]; g.fillOval(px-6,py-6,12,12); g.color=Color.WHITE; g.drawOval(px-6,py-6,12,12); g.color=new Color(38,176,156) }
                String label=String.format('%.2f px',measuredPixels()); FontMetrics fm=g.fontMetrics
                int labelWidth=fm.stringWidth(label), lx=((ax+bx)/2) as int, ly=(((ay+by)/2)-10) as int
                int labelX=lx-(labelWidth/2 as int)
                g.color=new Color(0,0,0,190); g.fillRoundRect(labelX-5,ly-fm.ascent,labelWidth+10,fm.height+2,8,8)
                g.color=Color.WHITE; g.drawString(label,labelX,ly)
            }
        } finally { g.dispose() }
    }
    @Override void mouseWheelMoved(MouseWheelEvent e) { zoomAt(e.preciseWheelRotation<0?1.25d:0.8d,e.point) }
    @Override void mousePressed(MouseEvent e) {
        if (!image) return
        dragOrigin=e.point
        if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) { panning=true; cursor=Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR); return }
        Point2D.Double point=screenToImage(e.point)
        if (lineStart && imageToScreen(lineStart).distance(e.point)<=12d) activeHandle=0
        else if (lineEnd && imageToScreen(lineEnd).distance(e.point)<=12d) activeHandle=1
        else { lineStart=point; lineEnd=new Point2D.Double(point.x,point.y); activeHandle=1; drawingLine=true }
        repaint()
    }
    @Override void mouseDragged(MouseEvent e) {
        if (!image) return
        if (panning && dragOrigin) { fitMode=false; offsetX+=e.x-dragOrigin.x; offsetY+=e.y-dragOrigin.y; dragOrigin=e.point; repaint(); return }
        Point2D.Double p=screenToImage(e.point)
        if (e.shiftDown) {
            if (activeHandle==0) p=constrainToAxis(lineEnd,p)
            else if (activeHandle==1) p=constrainToAxis(lineStart,p)
        }
        if (activeHandle==0) lineStart=p else if (activeHandle==1) lineEnd=p
        measurementChanged?.call(); repaint()
    }
    @Override void mouseReleased(MouseEvent e) { panning=false; drawingLine=false; activeHandle=-1; dragOrigin=null; cursor=Cursor.defaultCursor; measurementChanged?.call() }
    @Override void mouseClicked(MouseEvent e) {} ; @Override void mouseEntered(MouseEvent e) {} ; @Override void mouseExited(MouseEvent e) {} ; @Override void mouseMoved(MouseEvent e) {}
}

class FijiCalLocalPreferences {
    private final File file
    private final Properties values = new Properties()

    FijiCalLocalPreferences(File file) {
        this.file = file
        if (file.isFile()) file.withInputStream { values.load(it) }
    }

    synchronized String get(String key, String fallback) { values.getProperty(key, fallback) }
    synchronized int getInt(String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key)) } catch (ignored) { return fallback }
    }
    synchronized double getDouble(String key, double fallback) {
        try { return Double.parseDouble(values.getProperty(key)) } catch (ignored) { return fallback }
    }
    synchronized boolean getBoolean(String key, boolean fallback) {
        String value = values.getProperty(key)
        value == null ? fallback : Boolean.parseBoolean(value)
    }
    synchronized void put(String key, String value) { values.setProperty(key, value ?: '') }
    synchronized void putInt(String key, int value) { put(key, String.valueOf(value)) }
    synchronized void putDouble(String key, double value) { put(key, String.valueOf(value)) }
    synchronized void putBoolean(String key, boolean value) { put(key, String.valueOf(value)) }
    synchronized void remove(String key) { values.remove(key) }
    synchronized void flush() {
        if (!file.parentFile.isDirectory() && !file.parentFile.mkdirs())
            throw new IOException("FijiCal Lite could not create its settings folder: ${file.parentFile.absolutePath}")
        File temporary = new File(file.parentFile, file.name + '.tmp')
        temporary.withOutputStream { values.store(it, 'FijiCal Lite settings') }
        try {
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())

def BG = new Color(18, 20, 22)
def PANEL = new Color(29, 32, 35)
def PANEL_2 = new Color(37, 40, 43)
def TEXT = new Color(232, 234, 235)
def MUTED = new Color(159, 164, 168)
def ACCENT = new Color(43, 158, 140)
def ACCENT_DARK = new Color(31, 125, 112)
def BORDER = new Color(57, 61, 64)
def INPUT = new Color(39, 42, 45)
def FONT_UI = new Font('Segoe UI',Font.PLAIN,14)

[
    'Panel.background':PANEL, 'Viewport.background':BG, 'ScrollPane.background':BG,
    'OptionPane.background':PANEL, 'OptionPane.messageForeground':TEXT,
    'FileChooser.background':PANEL, 'FileChooser.foreground':TEXT,
    'List.background':INPUT, 'List.foreground':TEXT, 'List.selectionBackground':ACCENT_DARK, 'List.selectionForeground':TEXT,
    'Label.foreground':TEXT, 'Label.font':FONT_UI, 'Button.font':FONT_UI,
    'ToggleButton.font':FONT_UI, 'CheckBox.font':FONT_UI,
    'Button.background':PANEL_2, 'Button.foreground':TEXT,
    'ToggleButton.background':PANEL_2, 'ToggleButton.foreground':TEXT,
    'ToggleButton.select':ACCENT_DARK,
    'Button.disabledText':new Color(105,110,114), 'ToggleButton.disabledText':new Color(105,110,114),
    'TextField.background':INPUT, 'TextField.foreground':TEXT, 'TextField.caretForeground':TEXT,
    'TextField.inactiveForeground':new Color(115,120,124),
    'FormattedTextField.background':INPUT, 'FormattedTextField.foreground':TEXT,
    'ComboBox.background':INPUT, 'ComboBox.foreground':TEXT,
    'ComboBox.selectionBackground':ACCENT_DARK, 'ComboBox.selectionForeground':TEXT,
    'ComboBox.disabledBackground':PANEL_2, 'ComboBox.disabledForeground':new Color(115,120,124),
    'Spinner.background':INPUT, 'Spinner.foreground':TEXT,
    'CheckBox.background':PANEL, 'CheckBox.foreground':TEXT,
    'MenuBar.background':PANEL, 'MenuBar.foreground':TEXT,
    'Menu.background':PANEL, 'Menu.foreground':TEXT,
    'MenuItem.background':PANEL, 'MenuItem.foreground':TEXT,
    'Separator.foreground':BORDER, 'ToolTip.background':PANEL_2, 'ToolTip.foreground':TEXT
].each { key,value -> UIManager.put(key,value) }

File appDirectory = binding.hasVariable('fijicalAppDir') ?
    (binding.getVariable('fijicalAppDir') as File).canonicalFile : new File('.').canonicalFile
Closure<File> bundledFile = { String filename ->
    List<File> candidates = [
        new File(appDirectory, filename),
        new File(appDirectory, "scripts/Plugins/${filename}"),
        new File("scripts/Plugins/${filename}")
    ]
    candidates.find { it.isFile() } ?: candidates[0]
}

File portableRoot = appDirectory.name.equalsIgnoreCase('app') && appDirectory.parentFile ? appDirectory.parentFile : appDirectory
File dataDirectory = binding.hasVariable('fijicalDataDir') ?
    (binding.getVariable('fijicalDataDir') as File).canonicalFile : portableRoot
if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs())
    throw new IOException("FijiCal Lite could not create its data folder: ${dataDirectory.absolutePath}")
def prefs = new FijiCalLocalPreferences(new File(dataDirectory, 'FijiCal Lite Settings.properties'))
File presetFile = new File(dataDirectory,'FijiCal Lite Presets.json')
Closure<String> readLegacyPresetJson = {
    if (!System.getProperty('os.name', '').toLowerCase().contains('win')) return ''
    def result = new java.util.concurrent.atomic.AtomicReference<String>('')
    Thread migration = new Thread({
        try {
            result.set(Preferences.userNodeForPackage(getClass()).node('scale-bar-presets').get('presets.v1', ''))
        } catch (ignored) {}
    } as Runnable, 'FijiCal preset migration')
    migration.daemon = true
    migration.start()
    migration.join(750L)
    result.get()
}
def fallbackPresets = [[name:'Default 10 µm', pixelDistance:1d, knownDistance:1d, calibrationUnit:'µm', scaleUnit:'µm', pixelAspect:1d,
        width:10d, height:4, font:18, color:'White', background:'None', location:'Lower Right',
        bold:true, serif:false, hideText:false, overlay:true, globalScale:false]]
def writePresetFile = { List values ->
    def document=[format:'FijiCal Lite Presets',version:1,presets:values]
    File temporary=new File(presetFile.parentFile,presetFile.name+'.tmp')
    temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(document))+'\n','UTF-8')
    try {
        java.nio.file.Files.move(temporary.toPath(),presetFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        java.nio.file.Files.move(temporary.toPath(),presetFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
def readPresets = {
    try {
        if (presetFile.isFile()) {
            def parsed=new JsonSlurper().parse(presetFile,'UTF-8')
            def loaded=parsed instanceof Map ? parsed.presets : parsed
            return loaded instanceof List && loaded ? loaded.collect {
                Map preset = new LinkedHashMap(it as Map)
                if (!preset.calibrationUnit) preset.calibrationUnit = preset.scaleUnit ?: preset.expectedUnit ?: 'pixel'
                if (!preset.scaleUnit) preset.scaleUnit = preset.calibrationUnit
                preset
            } : fallbackPresets.collect { new LinkedHashMap(it) }
        }
        String legacyRaw=readLegacyPresetJson()
        List migrated=legacyRaw ? (new JsonSlurper().parseText(legacyRaw) as List).collect {
            Map preset = new LinkedHashMap(it as Map)
            if (!preset.calibrationUnit) preset.calibrationUnit = preset.scaleUnit ?: preset.expectedUnit ?: 'pixel'
            if (!preset.scaleUnit) preset.scaleUnit = preset.calibrationUnit
            preset
        } : fallbackPresets.collect { new LinkedHashMap(it) }
        if (!migrated) migrated=fallbackPresets.collect { new LinkedHashMap(it) }
        writePresetFile(migrated)
        migrated
    } catch (ignored) {
        fallbackPresets.collect { new LinkedHashMap(it) }
    }
}
def presets = readPresets()
def presetDisplayName = { Map p ->
    String collection = (p.collection ?: '').toString().trim()
    collection ? "${collection}  ›  ${p.name}" : (p.name as String)
}

def images = []
def sourceFiles = []
def appliedPresetNames = new IdentityHashMap<ImagePlus, String>()
def appliedPresetData = new IdentityHashMap<ImagePlus, Map>()
def sourceImageDetails = new IdentityHashMap<ImagePlus, Map>()
def editHistories = new IdentityHashMap<ImagePlus, List<String>>()
def editSnapshots = new IdentityHashMap<ImagePlus, List<Map>>()
def nativeEditHistories = new IdentityHashMap<ImagePlus, List<Map>>()
def scaleBarPositions = new IdentityHashMap<ImagePlus, Point>()
def selected = new LinkedHashSet<Integer>()
int currentIndex = -1
int anchorIndex = -1
int displayedIndex = -1
boolean gridMode = false

def frame = new JFrame('FijiCal Lite 0.9 — Mamanuca')
frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
try {
    File appIconFile=bundledFile('fijical-lite-logo-256.png')
    if (appIconFile.isFile()) frame.iconImage=ImageIO.read(appIconFile)
} catch (ignored) {}
Rectangle usableScreen = WindowGeometry.usableBounds()
frame.minimumSize = AwtGeometry.dimension(Math.min(980, usableScreen.width), Math.min(640, usableScreen.height))
frame.preferredSize = AwtGeometry.dimension(Math.min(1500, usableScreen.width), Math.min(900, usableScreen.height))
frame.background = BG

def root = new JPanel(new BorderLayout())
root.background = BG
root.border = BorderFactory.createLineBorder(new Color(9,10,11),1)
frame.contentPane = root

def styleButton = { AbstractButton button, boolean primary=false ->
    button.font=button.font.deriveFont(primary ? Font.BOLD : Font.PLAIN,14f)
    button.foreground=TEXT
    button.background=primary ? ACCENT : PANEL_2
    button.opaque=true; button.contentAreaFilled=true; button.focusPainted=true
    button.border=BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(primary ? ACCENT_DARK : BORDER,1),
        new EmptyBorder(7,12,7,12))
}
def styleInput = { JComponent component ->
    component.background=INPUT; component.foreground=TEXT
    component.border=BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER,1),new EmptyBorder(3,8,3,8))
    if (component instanceof JSpinner) {
        def editor=(component as JSpinner).editor
        if (editor instanceof JSpinner.DefaultEditor) {
            def text=(editor as JSpinner.DefaultEditor).textField
            text.background=INPUT; text.foreground=TEXT; text.caretColor=TEXT; text.border=null
        }
    }
}

def menuBar = new JMenuBar()
menuBar.background=PANEL; menuBar.foreground=TEXT; menuBar.border=BorderFactory.createMatteBorder(0,0,1,0,BORDER)
['File','Edit','Image','Calibration','Batch','Help'].each { label ->
    def menu = new JMenu(label)
    menuBar.add(menu)
}
['F','E','I','C','B','H'].eachWithIndex { String key, int index ->
    menuBar.getMenu(index).mnemonic = key.charAt(0) as int
}
frame.setJMenuBar(menuBar)

def fileMenu = menuBar.getMenu(0)
def openItem = new JMenuItem('Open Images…')
def saveAsItem = new JMenuItem('Save As…')
def closeBatchItem = new JMenuItem('Close Batch')
def exitItem = new JMenuItem('Exit')
openItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK)
saveAsItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)
closeBatchItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK)
saveAsItem.enabled = false
fileMenu.add(openItem)
fileMenu.add(saveAsItem)
fileMenu.add(closeBatchItem)
fileMenu.addSeparator()
fileMenu.add(exitItem)

def editMenu = menuBar.getMenu(1)
def undoEditItem = new JMenuItem('Undo Image Edit')
def resetEditsItem = new JMenuItem('Reset Image Edits')
undoEditItem.enabled = false
resetEditsItem.enabled = false
editMenu.add(undoEditItem)
editMenu.add(resetEditsItem)

def imageMenu = menuBar.getMenu(2)
def cropItem = new JMenuItem('Crop')
def applyCropItem = new JMenuItem('Apply Crop')
def cancelCropItem = new JMenuItem('Cancel Crop')
applyCropItem.enabled = false
cancelCropItem.enabled = false
def brightnessContrastItem = new JMenuItem('Brightness & Contrast…')
def channelBalanceItem = new JMenuItem('RGB Channel Balance…')
def rotateRightItem = new JMenuItem('Rotate Right 90°')
def rotateLeftItem = new JMenuItem('Rotate Left 90°')
def flipHorizontalItem = new JMenuItem('Flip Horizontal')
def flipVerticalItem = new JMenuItem('Flip Vertical')
[cropItem].each {
    it.enabled = false
    imageMenu.add(it)
}
imageMenu.addSeparator()
[brightnessContrastItem, channelBalanceItem, rotateRightItem, rotateLeftItem, flipHorizontalItem, flipVerticalItem].each {
    it.enabled = false
    imageMenu.add(it)
}
imageMenu.addSeparator()
def imageInfoItem = new JMenuItem('Metadata & Calibration…')
imageInfoItem.enabled = false
imageMenu.add(imageInfoItem)

def calibrationMenu = menuBar.getMenu(3)
def presetManagerItem = new JMenuItem('Preset Manager…')
def calibrateItem = new JMenuItem('Calibrate from Image…')
calibrationMenu.add(presetManagerItem)
calibrationMenu.add(calibrateItem)

def helpMenu = menuBar.getMenu(5)
def instructionsItem = new JMenuItem('Instructions')
def troubleshootingItem = new JMenuItem('Troubleshooting')
def aboutItem = new JMenuItem('About FijiCal Lite')
instructionsItem.accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0)
helpMenu.add(instructionsItem)
helpMenu.add(troubleshootingItem)
helpMenu.addSeparator()
helpMenu.add(aboutItem)

instructionsItem.addActionListener {
    def helpText = new JTextArea('''QUICK START

1. Open one or more microscopy images.
2. Choose the objective preset for each image.
3. Use Apply to Selected or Apply to All.
4. With Editable overlay enabled, use the Pointer tool to drag a scale bar to an
   exact per-image position. Apply again at any time to reset it to the preset.
5. Use File → Save As for the active image, or Save All for the batch.
   Originals are never changed.

KEYBOARD

Tab / Shift+Tab     Move between controls
Enter / Space       Activate the focused control
Ctrl+O              Open images
Ctrl+Shift+S        Save the active image as a copy
Ctrl+W              Close the current batch
Ctrl+Z              Undo the last image edit
F1                  Open these instructions
Escape              Cancel crop mode or close the calibration window

IMAGE EDITS

1. Select an image in Loupe or Grid view.
2. Use Image → Crop, Brightness & Contrast, RGB Channel Balance, Rotate, or Flip.
3. Use Edit → Undo Image Edit (Ctrl+Z) to reverse the last operation.
4. Use Edit → Reset Image Edits to restore the image's original orientation.

Edits remain non-destructive and are written only to exported copies. Each image
in the batch keeps its own edit history.

CALIBRATE FROM IMAGE — OVALAU

1. Open Calibration → Calibrate from Image.
2. Choose a calibration image, or use the active image.
3. Draw one line across a known distance.
4. If needed, use Pixel Distance → Edit to correct the measured pixel length.
5. Enter the known distance and its unit.
6. Save as a new preset or update the selected preset.

PRESET PACKS — OVALAU

1. Open Calibration → Preset Manager.
2. Use Details to assign a named collection and record optional microscope,
   camera, adapter, objective, and calibration notes for a preset. Typing a
   new collection name creates it.
3. Export either the selected preset or the complete collection to a
   human-readable JSON pack. A complete export also acts as a backup.
4. Import a pack and choose how duplicate names are handled: keep both,
   replace, skip, or rename each duplicate.
5. Filter the Preset Manager by collection. Use Sort presets to group by
   collection and order objectives by magnification, or keep manual order
   with Move up and Move down. Rename collection updates every member.

Imports are validated and applied only after all conflicts are resolved.
The ordinary FijiCal Lite Presets.json file can also be imported as a backup.

Canvas controls: left-drag draws a line; hold Shift while drawing or adjusting
to constrain it horizontally or vertically; drag either endpoint to adjust it;
mouse wheel zooms; right- or middle-drag pans. Touch and MPP stylus input
use the same controls.

Tip: calibration units are case-sensitive labels. Use µm, mm, nm, or another
unit that accurately describes the known distance.''')
    helpText.editable=false; helpText.opaque=false; helpText.foreground=TEXT
    helpText.font=new Font(Font.SANS_SERIF,Font.PLAIN,13); helpText.rows=22; helpText.columns=64
    JOptionPane.showMessageDialog(frame,new JScrollPane(helpText),'FijiCal Lite Instructions',JOptionPane.PLAIN_MESSAGE)
}

troubleshootingItem.addActionListener {
    def text = new JTextArea('''TROUBLESHOOTING

No image opens
• Use TIFF, PNG, JPEG, or an ImageJ-supported format. Try File → Open Images.

Scale bar looks wrong
• Confirm the selected preset’s scale unit, distance in pixels, and known distance.
• Calibrate from Image with a ruler or stage micrometer if unsure.

Export looks different
• Save All creates new copies; the originals are never changed.
• Leave “Minify PNG” off when preserving high-bit-depth PNG data matters.

Preset changes are missing
• Portable builds store presets beside FijiCal Lite. Keep that folder together.

Something broke
• Restart FijiCal Lite and keep the startup log shown by any error dialog.
• Include a sample image and the exact steps when reporting an issue.''')
    text.editable=false; text.opaque=false; text.foreground=TEXT
    text.font=new Font(Font.SANS_SERIF,Font.PLAIN,13); text.rows=18; text.columns=62
    JOptionPane.showMessageDialog(frame,new JScrollPane(text),'FijiCal Lite Troubleshooting',JOptionPane.PLAIN_MESSAGE)
}

aboutItem.addActionListener {
    def aboutText = new JTextArea('''FijiCal Lite 0.9 — Mamanuca

Microscopy calibration and scale bars without the menu archaeology.

Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
FijiCal Lite is free software under GNU GPL version 3 or later.
You may modify and redistribute it under those terms. There is NO WARRANTY.
Read LICENSE beside the executable for the complete terms.
Bundled components retain their own terms; see THIRD_PARTY_NOTICES.md.

If you reuse, modify, teach with, or redistribute FijiCal Lite, please give
the project a nod. Ideas and improvements are warmly welcome.

Source and support: github.com/nighthunter226-but-real/FijiCal-Lite''')
    aboutText.editable=false; aboutText.opaque=false; aboutText.foreground=TEXT
    aboutText.font=new Font(Font.SANS_SERIF,Font.PLAIN,13); aboutText.rows=14; aboutText.columns=60
    Object[] aboutActions=['Close','Totally Serious User Licence…'] as Object[]
    int aboutChoice=JOptionPane.showOptionDialog(frame,new JScrollPane(aboutText),'About FijiCal Lite',
        JOptionPane.DEFAULT_OPTION,JOptionPane.INFORMATION_MESSAGE,null,aboutActions,aboutActions[0])
    if (aboutChoice==1) {
        JOptionPane.showMessageDialog(frame,'''THE TOTALLY SERIOUS USER LICENCE

By using FijiCal Lite, you swear a blood oath designating the developer as
guardian of your first-born child — or, at the very least, provide a picture
drawn by them.

This oath is ceremonial, legally meaningless, and fully satisfied by a nice
doodle. This joke adds no legal conditions. Actual terms: GNU GPL version 3
or later in LICENSE; third-party terms in THIRD_PARTY_NOTICES.md.''',
            'Totally Serious User Licence',JOptionPane.PLAIN_MESSAGE)
    }
}

def top = new JPanel(new BorderLayout(8,0))
top.background = PANEL
top.border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER),new EmptyBorder(7,12,7,10))
def fileLabel = new JLabel('Open images to begin')
fileLabel.foreground = TEXT
fileLabel.font = fileLabel.font.deriveFont(Font.BOLD, 14f)
def viewButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0))
viewButtons.opaque = false
def loupeButton = new JToggleButton('Loupe', true)
def gridButton = new JToggleButton('Grid')
def fitButton = new JButton('Fit')
def actualButton = new JButton('100%')
def applyCropButton = new JButton('Apply Crop')
def cancelCropButton = new JButton('Cancel')
applyCropButton.visible = false; applyCropButton.enabled = false
cancelCropButton.visible = false; cancelCropButton.enabled = false
def views = new ButtonGroup(); views.add(loupeButton); views.add(gridButton)
[fitButton,actualButton,loupeButton,gridButton,applyCropButton,cancelCropButton].each { styleButton(it,false) }
loupeButton.background=ACCENT_DARK
viewButtons.add(fitButton); viewButtons.add(actualButton)
viewButtons.add(applyCropButton); viewButtons.add(cancelCropButton)
viewButtons.add(Box.createHorizontalStrut(8)); viewButtons.add(loupeButton); viewButtons.add(gridButton)
top.add(fileLabel, BorderLayout.WEST)
top.add(viewButtons, BorderLayout.EAST)

def canvasHost = new JPanel(new BorderLayout())
canvasHost.background = BG
canvasHost.border = new EmptyBorder(0,0,0,0)
def zoomCanvas = new ZoomCanvas(BG, MUTED)
def pointerTool = new JToggleButton(new PaletteToolIcon('pointer'),true)
def cropTool = new JToggleButton(new PaletteToolIcon('crop'))
def rotateTool = new JButton(new PaletteToolIcon('rotate'))
def measureTool = new JButton(new PaletteToolIcon('measure'))
def navigationTools = new ButtonGroup(); navigationTools.add(pointerTool); navigationTools.add(cropTool)
def stylePaletteTool = { AbstractButton button, String tooltip ->
    Dimension toolSize = AwtGeometry.dimension(42,42)
    button.preferredSize = toolSize; button.minimumSize = toolSize; button.maximumSize = toolSize
    button.alignmentX = Component.CENTER_ALIGNMENT
    button.background = PANEL_2; button.foreground = TEXT
    button.border = BorderFactory.createEmptyBorder(7,7,7,7)
    button.focusPainted = true; button.opaque = true; button.rolloverEnabled = true
    button.toolTipText = tooltip
    button.accessibleContext.accessibleName = tooltip
    button.model.addChangeListener {
        button.background = button.model.selected ? ACCENT_DARK : (button.model.rollover ? INPUT : PANEL_2)
        button.foreground = button.enabled ? TEXT : MUTED
    }
}
stylePaletteTool(pointerTool,'Pointer / navigate')
stylePaletteTool(cropTool,'Crop')
stylePaletteTool(rotateTool,'Rotate right 90°')
stylePaletteTool(measureTool,'Measure / calibrate from image')
def floatingTools = new FloatingToolPalette(PANEL_2,BORDER)
[pointerTool,cropTool,rotateTool,measureTool].eachWithIndex { AbstractButton button, int index ->
    if (index > 0) floatingTools.add(Box.createVerticalStrut(2))
    floatingTools.add(button)
}
floatingTools.preferredSize = AwtGeometry.dimension(58,192)
floatingTools.minimumSize = floatingTools.preferredSize
def canvasLayer = new FloatingCanvasLayer(zoomCanvas,floatingTools)
canvasHost.add(canvasLayer, BorderLayout.CENTER)

def filmstrip = new JPanel(new FlowLayout(FlowLayout.LEFT,8,8))
filmstrip.background = PANEL
def filmScroll = new JScrollPane(filmstrip)
filmScroll.border = BorderFactory.createMatteBorder(1,0,0,0,BORDER)
filmScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
filmScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
filmScroll.preferredSize = AwtGeometry.dimension(500,142)
filmScroll.background=PANEL; filmScroll.viewport.background=PANEL

def gridPanel = new JPanel(new GridLayout(0,3,10,10))
gridPanel.background = BG
gridPanel.border = new EmptyBorder(12,12,12,12)
def gridScroll = new JScrollPane(gridPanel)
gridScroll.border = null

def workspace = new JPanel(new BorderLayout())
workspace.background = BG
workspace.border=BorderFactory.createMatteBorder(0,0,0,1,BORDER)
workspace.add(top, BorderLayout.NORTH)
workspace.add(canvasHost, BorderLayout.CENTER)
workspace.add(filmScroll, BorderLayout.SOUTH)

def inspector = new JPanel()
inspector.layout = new BoxLayout(inspector, BoxLayout.Y_AXIS)
inspector.background = PANEL
inspector.border = new EmptyBorder(14,14,12,14)
inspector.preferredSize = AwtGeometry.dimension(310,700)

def heading = new JLabel('Calibration & Scale Bar')
heading.foreground = TEXT
heading.font = heading.font.deriveFont(Font.BOLD, 17f)
heading.alignmentX = Component.LEFT_ALIGNMENT
def headingRow=new JPanel(new BorderLayout()); headingRow.background=PANEL; headingRow.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,32); headingRow.alignmentX=Component.LEFT_ALIGNMENT
headingRow.add(heading,BorderLayout.WEST)
inspector.add(headingRow); inspector.add(Box.createVerticalStrut(12))

def presetCombo = new JComboBox(presets.collect { presetDisplayName(it as Map) } as String[])
presetCombo.maximumSize = AwtGeometry.dimension(Integer.MAX_VALUE,34)
presetCombo.alignmentX = Component.LEFT_ALIGNMENT
styleInput(presetCombo)
def presetSettingsButton=new JButton('Edit'); styleButton(presetSettingsButton,false); presetSettingsButton.preferredSize=AwtGeometry.dimension(54,34)
def presetRow=new JPanel(new BorderLayout(7,0)); presetRow.background=PANEL; presetRow.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,36); presetRow.alignmentX=Component.LEFT_ALIGNMENT
presetRow.add(presetCombo,BorderLayout.CENTER); presetRow.add(presetSettingsButton,BorderLayout.EAST)
inspector.add(presetRow); inspector.add(Box.createVerticalStrut(10))

def statusLabel = new JLabel('No image loaded')
statusLabel.foreground = ACCENT
statusLabel.alignmentX = Component.LEFT_ALIGNMENT
zoomCanvas.cropChanged = { Rectangle selection ->
    applyCropItem.enabled = selection != null
    cancelCropItem.enabled = zoomCanvas.cropMode
    applyCropButton.visible = zoomCanvas.cropMode
    applyCropButton.enabled = selection != null
    cancelCropButton.visible = zoomCanvas.cropMode
    cancelCropButton.enabled = zoomCanvas.cropMode
    cropTool.selected = zoomCanvas.cropMode
    pointerTool.selected = !zoomCanvas.cropMode
    if (selection != null) statusLabel.text = "Crop selection · ${selection.width} × ${selection.height} px"
}
inspector.add(statusLabel); inspector.add(Box.createVerticalStrut(18))

def addField = { String title, JComponent field ->
    def label = new JLabel(title); label.foreground = TEXT; label.alignmentX = Component.LEFT_ALIGNMENT
    field.maximumSize = AwtGeometry.dimension(Integer.MAX_VALUE,32); field.alignmentX = Component.LEFT_ALIGNMENT
    styleInput(field)
    inspector.add(label); inspector.add(Box.createVerticalStrut(4)); inspector.add(field); inspector.add(Box.createVerticalStrut(10))
}

def unitField = new JTextField()
def widthField = new JSpinner(new SpinnerNumberModel(10d,0.000001d,1000000d,1d))
def heightField = new JSpinner(new SpinnerNumberModel(4,1,500,1))
def fontField = new JSpinner(new SpinnerNumberModel(18,1,500,1))
def positionField = new JComboBox(['Lower Right','Lower Left','Upper Right','Upper Left','At Selection'] as String[])
def colorField = new JComboBox(['White','Black','Light Gray','Gray','Dark Gray','Red','Green','Blue','Yellow','Cyan','Magenta'] as String[])
def overlayField = new JCheckBox('Editable overlay', true); overlayField.opaque=false; overlayField.foreground=TEXT
addField('Scale unit', unitField)
addField('Bar width', widthField)
addField('Bar height', heightField)
addField('Label size', fontField)
addField('Position', positionField)
addField('Color', colorField)
overlayField.alignmentX = Component.LEFT_ALIGNMENT
inspector.add(overlayField); inspector.add(Box.createVerticalStrut(12))
def sectionRule=new JSeparator(); sectionRule.foreground=BORDER; sectionRule.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,1); sectionRule.alignmentX=Component.LEFT_ALIGNMENT
inspector.add(sectionRule); inspector.add(Box.createVerticalStrut(14))
def calibrateButton=new JButton('Calibrate from Image'); styleButton(calibrateButton,false); calibrateButton.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,42); calibrateButton.alignmentX=Component.LEFT_ALIGNMENT
inspector.add(calibrateButton); inspector.add(Box.createVerticalGlue())

def selectionLabel = new JLabel('0 images selected')
selectionLabel.foreground = MUTED; selectionLabel.alignmentX = Component.LEFT_ALIGNMENT
inspector.add(selectionLabel); inspector.add(Box.createVerticalStrut(10))
def applySelectedButton = new JButton('Apply to Selected')
def applyAllButton = new JButton('Apply to All')
def saveAllButton = new JButton('Save All…')
[applySelectedButton,applyAllButton,saveAllButton].each { b ->
    b.maximumSize = AwtGeometry.dimension(Integer.MAX_VALUE,42); b.alignmentX=Component.LEFT_ALIGNMENT
    styleButton(b,b==applyAllButton)
    inspector.add(b); inspector.add(Box.createVerticalStrut(8))
}

def inspectorScroll = new JScrollPane(inspector)
inspectorScroll.border = null
inspectorScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
inspectorScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
inspectorScroll.viewport.background = PANEL
inspectorScroll.preferredSize = AwtGeometry.dimension(330,700)
inspectorScroll.minimumSize = AwtGeometry.dimension(280,0)
root.add(workspace, BorderLayout.CENTER)
root.add(inspectorScroll, BorderLayout.EAST)

def bottom = new JLabel('  No batch loaded')
bottom.opaque=true; bottom.background=PANEL; bottom.foreground=MUTED
bottom.border = BorderFactory.createMatteBorder(1,0,0,0,BORDER)
bottom.preferredSize = AwtGeometry.dimension(100,30)
bottom.font=bottom.font.deriveFont(12f)
root.add(bottom, BorderLayout.SOUTH)

zoomCanvas.viewChanged = {
    if (!gridMode && currentIndex >= 0)
        bottom.text="  ${images.size()} images     |     ${selected.size()} selected     |     ${zoomCanvas.zoomPercent()}%     |     Preset: ${presetCombo.selectedItem ?: 'None'}"
}

def render = null

def currentPreset = {
    if (presets.isEmpty() || presetCombo.selectedIndex < 0) return new LinkedHashMap(fallbackPresets[0])
    int i = Math.max(0,presetCombo.selectedIndex)
    def base = new LinkedHashMap(presets[i] as Map)
    base.scaleUnit = unitField.text.trim() ?: 'pixel'
    if (!base.calibrationUnit) base.calibrationUnit = base.scaleUnit
    base.width = (widthField.value as Number).doubleValue()
    base.height = (heightField.value as Number).intValue()
    base.font = (fontField.value as Number).intValue()
    base.location = positionField.selectedItem as String
    base.color = colorField.selectedItem as String
    base.overlay = overlayField.selected
    base
}

def showPreset = {
    if (presets.isEmpty() || presetCombo.selectedIndex < 0) return
    def p = presets[Math.max(0,presetCombo.selectedIndex)] as Map
    unitField.text = (p.scaleUnit ?: p.expectedUnit ?: 'pixel') as String
    widthField.value = ((p.width ?: 10d) as Number).doubleValue()
    heightField.value = ((p.height ?: 4) as Number).intValue()
    fontField.value = ((p.font ?: 18) as Number).intValue()
    positionField.selectedItem = p.location ?: 'Lower Right'
    colorField.selectedItem = p.color ?: 'White'
    overlayField.selected = p.overlay != false
}

def presetCalibrationSummary = { Map preset ->
    double pixels = ((preset?.pixelDistance ?: 0d) as Number).doubleValue()
    double known = ((preset?.knownDistance ?: 0d) as Number).doubleValue()
    String unit = ((preset?.calibrationUnit ?: preset?.scaleUnit ?: preset?.expectedUnit ?: '') as String).trim()
    if (!Double.isFinite(pixels) || !Double.isFinite(known) || pixels <= 0d || known <= 0d || !unit)
        return 'Preset scale unavailable'
    double pixelsPerUnit = pixels / known
    double unitsPerPixel = known / pixels
    String.format(Locale.ROOT, 'Preset scale · %.6g px/%s · %.6g %s/px',
        pixelsPerUnit, unit, unitsPerPixel, unit)
}

def reloadPresets = { String desiredPresetName = null ->
    int previousIndex = presetCombo.selectedIndex
    String previouslySelected = previousIndex >= 0 && previousIndex < presets.size() ? (presets[previousIndex].name as String) : ''
    String nameToRestore = desiredPresetName ?: previouslySelected
    List refreshed = readPresets() as List
    presets.clear()
    presets.addAll(refreshed)
    presetCombo.removeAllItems()
    presets.each { presetCombo.addItem(presetDisplayName(it as Map)) }
    int restoredIndex = presets.findIndexOf { (it.name ?: '') == nameToRestore }
    presetCombo.selectedIndex = restoredIndex >= 0 ? restoredIndex : 0
    showPreset()
    render?.call()
}

def applyPreset = { ImagePlus imp, Map p, boolean axesSwapped=false ->
    double pixelDistance = ((p.pixelDistance ?: 1d) as Number).doubleValue()
    double knownDistance = ((p.knownDistance ?: 1d) as Number).doubleValue()
    double aspect = ((p.pixelAspect ?: 1d) as Number).doubleValue()
    def cal = imp.getCalibration()
    double basePixelWidth = knownDistance / pixelDistance
    double basePixelHeight = basePixelWidth / aspect
    cal.pixelWidth = axesSwapped ? basePixelHeight : basePixelWidth
    cal.pixelHeight = axesSwapped ? basePixelWidth : basePixelHeight
    cal.pixelDepth = 1d
    String calibrationUnit = (p.calibrationUnit ?: p.scaleUnit ?: 'pixel') as String
    String scaleBarUnit = (p.scaleUnit ?: calibrationUnit) as String
    cal.setUnit(calibrationUnit)
    imp.setCalibration(cal)

    Overlay overlay = imp.overlay ?: new Overlay()
    for (int i=overlay.size()-1; i>=0; i--) {
        if ((overlay.get(i).name ?: '').startsWith('FijiCal Scale Bar')) overlay.remove(i)
    }

    double calibratedWidth = ((p.width ?: 10d) as Number).doubleValue()
    double widthInCalibrationUnits = PhysicalUnits.convert(calibratedWidth, scaleBarUnit, calibrationUnit)
    int barWidth = Math.max(1, Math.round(widthInCalibrationUnits / cal.pixelWidth) as int)
    int barHeight = Math.max(1, ((p.height ?: 4) as Number).intValue())
    int fontSize = Math.max(1, ((p.font ?: 18) as Number).intValue())
    int fontStyle = p.bold != false ? Font.BOLD : Font.PLAIN
    Font font = new Font(p.serif == true ? 'Serif' : 'SansSerif', fontStyle, fontSize)
    Color foreground = Colors.decode((p.color ?: 'White') as String, Color.WHITE)
    String unit = scaleBarUnit
    String valueText = calibratedWidth == Math.rint(calibratedWidth) ?
        String.valueOf(calibratedWidth as long) : IJ.d2s(calibratedWidth, 3)
    String labelText = "${valueText} ${unit}"
    boolean showText = p.hideText != true
    TextRoi measuringText = showText ? new TextRoi(0d, 0d, labelText, font) : null
    int textWidth = showText ? measuringText.bounds.width : 0
    int textHeight = showText ? measuringText.bounds.height : 0
    int gap = showText ? Math.max(2, Math.round(fontSize * 0.18d) as int) : 0
    int groupWidth = Math.max(barWidth, textWidth)
    int groupHeight = barHeight + gap + textHeight
    int margin = Math.max(8, Math.round(fontSize * 0.5d) as int)

    String location = (p.location ?: 'Lower Right') as String
    int x = location.contains('Right') ? imp.width-groupWidth-margin : margin
    int y = location.startsWith('Lower') ? imp.height-groupHeight-margin : margin
    if (location == 'At Selection' && imp.roi != null) {
        x = imp.roi.bounds.x
        y = imp.roi.bounds.y
    }
    Point customPosition = scaleBarPositions.get(imp)
    if (customPosition != null) {
        x = customPosition.x
        y = customPosition.y
    }
    x = Math.max(0, Math.min(imp.width-groupWidth, x))
    y = Math.max(0, Math.min(imp.height-groupHeight, y))

    String backgroundName = (p.background ?: 'None') as String
    if (!backgroundName.equalsIgnoreCase('None')) {
        int padding = Math.max(3, Math.round(fontSize * 0.25d) as int)
        Roi background = new Roi(Math.max(0,x-padding), Math.max(0,y-padding),
            Math.min(imp.width-x+padding,groupWidth+padding*2),
            Math.min(imp.height-y+padding,groupHeight+padding*2))
        background.fillColor = Colors.decode(backgroundName, Color.BLACK)
        background.strokeColor = background.fillColor
        background.name = 'FijiCal Scale Bar Background'
        overlay.add(background)
    }

    if (showText) {
        int textX = x + ((groupWidth-textWidth)/2 as int)
        TextRoi textRoi = new TextRoi(textX as double, y as double, labelText, font)
        textRoi.strokeColor = foreground
        textRoi.name = 'FijiCal Scale Bar Label'
        overlay.add(textRoi)
    }
    int barX = x + ((groupWidth-barWidth)/2 as int)
    int barY = y + textHeight + gap
    Roi bar = new Roi(barX, barY, barWidth, barHeight)
    bar.fillColor = foreground
    bar.strokeColor = foreground
    bar.name = 'FijiCal Scale Bar Bar'
    overlay.add(bar)
    imp.setOverlay(overlay)
    if (customPosition != null) {
        Rectangle appliedBounds = ScaleBarOverlayTools.bounds(overlay)
        if (appliedBounds != null) {
            int desiredX = Math.max(0,Math.min(imp.width-appliedBounds.width,customPosition.x))
            int desiredY = Math.max(0,Math.min(imp.height-appliedBounds.height,customPosition.y))
            ScaleBarOverlayTools.translate(overlay,desiredX-appliedBounds.x,desiredY-appliedBounds.y)
            Rectangle positionedBounds = ScaleBarOverlayTools.bounds(overlay)
            if (positionedBounds != null) scaleBarPositions.put(imp,AwtGeometry.point(positionedBounds.getX(),positionedBounds.getY()))
        }
    }
    imp.updateAndDraw()
}

def imageEditHistory = { ImagePlus imp ->
    List<String> history = editHistories.get(imp)
    if (history == null) {
        history = []
        editHistories.put(imp, history)
    }
    history
}

def imageEditSnapshots = { ImagePlus imp ->
    List<Map> snapshots = editSnapshots.get(imp)
    if (snapshots == null) {
        snapshots = []
        editSnapshots.put(imp, snapshots)
    }
    snapshots
}

def nativeImageEditHistory = { ImagePlus imp ->
    List<Map> history = nativeEditHistories.get(imp)
    if (history == null) {
        history = []
        nativeEditHistories.put(imp,history)
    }
    history
}

def refreshUndoControls = { ImagePlus imp ->
    List<String> history = imp == null ? [] : imageEditHistory(imp)
    List<Map> snapshots = imp == null ? [] : imageEditSnapshots(imp)
    boolean canUndo = !history.isEmpty() && !snapshots.isEmpty()
    undoEditItem.enabled = canUndo
    resetEditsItem.enabled = canUndo
    undoEditItem.text = canUndo ? "Undo ${history[history.size()-1]}" : 'Undo Image Edit'
}

def snapshotImageEdit = { ImagePlus imp ->
    Point scaleBarPosition = scaleBarPositions.get(imp)
    imageEditSnapshots(imp).add([
        image: new Duplicator().run(imp),
        title: imp.getTitle(),
        calibration: imp.getCalibration().copy(),
        channel: imp.getC(),
        slice: imp.getZ(),
        frame: imp.getT(),
        displayHistory: new ArrayList<String>(imageEditHistory(imp)),
        nativeHistory: nativeImageEditHistory(imp).collect { Map edit -> new LinkedHashMap(edit) },
        scaleBarPosition: scaleBarPosition == null ? null : new Point(scaleBarPosition)
    ])
}

def axesSwappedFor = { ImagePlus imp ->
    int quarterTurns = (imageEditHistory(imp) as List<String>).count { it == 'rotateRight' || it == 'rotateLeft' }
    (quarterTurns % 2) != 0
}

def refreshAppliedScaleBar = { ImagePlus imp ->
    Map applied = appliedPresetData.get(imp)
    if (applied != null) applyPreset(imp, new LinkedHashMap(applied), axesSwappedFor(imp))
}

def restoreImageEditSnapshot = { ImagePlus imp, Map snapshot ->
    BasicImageEdits.restoreImageState(
        imp,
        snapshot.image as ImagePlus,
        snapshot.title as String,
        snapshot.calibration as Calibration,
        (snapshot.channel as Number).intValue(),
        (snapshot.slice as Number).intValue(),
        (snapshot.frame as Number).intValue())
    editHistories.put(imp,new ArrayList<String>((snapshot.displayHistory ?: []) as List<String>))
    nativeEditHistories.put(imp,((snapshot.nativeHistory ?: []) as List<Map>).collect { Map edit -> new LinkedHashMap(edit) })
    Point restoredPosition = snapshot.scaleBarPosition as Point
    if (restoredPosition == null) scaleBarPositions.remove(imp)
    else scaleBarPositions.put(imp,new Point(restoredPosition))
    imp.updateAndDraw()
}

def performImageEdit = { ImagePlus imp, String operation, boolean recordHistory=true ->
    if (imp == null) return
    if (recordHistory) snapshotImageEdit(imp)
    try {
        int oldWidth = imp.width
        int oldHeight = imp.height
        Rectangle previousScaleBarBounds = ScaleBarOverlayTools.bounds(imp.overlay)
        Point customPosition = scaleBarPositions.get(imp)
        BasicImageEdits.transformStack(imp,operation)
        if (customPosition != null && previousScaleBarBounds != null) {
            double oldCentreX = previousScaleBarBounds.getCenterX()
            double oldCentreY = previousScaleBarBounds.getCenterY()
            double nextCentreX = oldCentreX
            double nextCentreY = oldCentreY
            if (operation == 'rotateRight') {
                nextCentreX = oldHeight - oldCentreY
                nextCentreY = oldCentreX
            } else if (operation == 'rotateLeft') {
                nextCentreX = oldCentreY
                nextCentreY = oldWidth - oldCentreX
            } else if (operation == 'flipHorizontal') {
                nextCentreX = oldWidth - oldCentreX
            } else if (operation == 'flipVertical') {
                nextCentreY = oldHeight - oldCentreY
            }
            scaleBarPositions.put(imp,AwtGeometry.point(
                nextCentreX - previousScaleBarBounds.getWidth() / 2d,
                nextCentreY - previousScaleBarBounds.getHeight() / 2d))
        }
        if (recordHistory) {
            imageEditHistory(imp).add(operation)
            nativeImageEditHistory(imp).add([type: operation])
        }
        refreshAppliedScaleBar(imp)
    } catch (Exception error) {
        if (recordHistory && !imageEditSnapshots(imp).isEmpty()) {
            Map snapshot = imageEditSnapshots(imp).remove(imageEditSnapshots(imp).size()-1)
            restoreImageEditSnapshot(imp,snapshot)
        }
        throw error
    }
}

zoomCanvas.scaleBarMoved = { int deltaX, int deltaY ->
    if ((deltaX == 0 && deltaY == 0) || currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    if (ScaleBarOverlayTools.bounds(imp.overlay) == null) return
    snapshotImageEdit(imp)
    try {
        ScaleBarOverlayTools.translate(imp.overlay,deltaX,deltaY)
        Rectangle movedBounds = ScaleBarOverlayTools.bounds(imp.overlay)
        if (movedBounds == null) throw new IllegalStateException('The scale bar overlay is no longer available')
        scaleBarPositions.put(imp,AwtGeometry.point(movedBounds.getX(),movedBounds.getY()))
        imageEditHistory(imp).add('scale bar position')
        imp.updateAndDraw()
        displayedIndex = -1
        render()
        statusLabel.text = "Moved scale bar to ${movedBounds.x}, ${movedBounds.y} px · Ctrl+Z to undo"
    } catch (Exception error) {
        List<Map> snapshots = imageEditSnapshots(imp)
        if (!snapshots.isEmpty()) {
            Map snapshot = snapshots.remove(snapshots.size()-1)
            restoreImageEditSnapshot(imp,snapshot)
        }
        JOptionPane.showMessageDialog(frame,
            "The scale bar could not be moved:\n${error.message ?: error.class.simpleName}",
            'Scale bar position error',JOptionPane.ERROR_MESSAGE)
    }
}

def inverseImageEdit = { String operation ->
    operation == 'rotateRight' ? 'rotateLeft' :
        operation == 'rotateLeft' ? 'rotateRight' : operation
}

def presetCompatibilityError = { ImagePlus imp, Map p ->
    if (imp == null || imp.width < 1 || imp.height < 1) return 'the image has no usable pixel dimensions'
    try {
        double pixelDistance = ((p.pixelDistance ?: 0d) as Number).doubleValue()
        double knownDistance = ((p.knownDistance ?: 0d) as Number).doubleValue()
        double aspect = ((p.pixelAspect ?: 1d) as Number).doubleValue()
        double calibratedWidth = ((p.width ?: 0d) as Number).doubleValue()
        if (!Double.isFinite(pixelDistance) || pixelDistance <= 0d) return 'the preset distance in pixels is not positive'
        if (!Double.isFinite(knownDistance) || knownDistance <= 0d) return 'the preset known distance is not positive'
        if (!Double.isFinite(aspect) || aspect <= 0d) return 'the preset pixel aspect ratio is not positive'
        if (!Double.isFinite(calibratedWidth) || calibratedWidth <= 0d) return 'the scale-bar width is not positive'
        if (!((p.scaleUnit ?: '') as String).trim()) return 'the preset has no scale unit'
        String calibrationUnit = ((p.calibrationUnit ?: p.scaleUnit ?: '') as String).trim()
        String scaleBarUnit = ((p.scaleUnit ?: calibrationUnit) as String).trim()
        double widthInCalibrationUnits = PhysicalUnits.convert(calibratedWidth, scaleBarUnit, calibrationUnit)
        double barPixels = widthInCalibrationUnits * pixelDistance / knownDistance
        if (!Double.isFinite(barPixels) || barPixels > imp.width) {
            return "the scale bar would be ${IJ.d2s(barPixels, 1)} px wide on a ${imp.width} px image"
        }
    } catch (Exception error) {
        return "the preset contains an invalid calibration value (${error.message ?: error.class.simpleName})"
    }
    null
}

def createBatchProgressDialog = { String title, String leadText, int total ->
    JDialog dialog = new JDialog(frame, title, true)
    dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    JLabel lead = new JLabel(leadText)
    JLabel current = new JLabel('Preparing…')
    current.foreground = MUTED
    JProgressBar progress = new JProgressBar(0, Math.max(1, total))
    progress.stringPainted = true
    progress.string = "0 of ${total}"
    JButton cancel = new JButton('Cancel')
    JPanel body = new JPanel(new BorderLayout(0, 12))
    body.border = new EmptyBorder(18, 20, 16, 20)
    JPanel labels = new JPanel(new GridLayout(0, 1, 0, 5))
    labels.add(lead)
    labels.add(current)
    body.add(labels, BorderLayout.NORTH)
    body.add(progress, BorderLayout.CENTER)
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    buttons.add(cancel)
    body.add(buttons, BorderLayout.SOUTH)
    dialog.contentPane = body
    dialog.minimumSize = AwtGeometry.dimension(520, 175)
    dialog.pack()
    dialog.setLocationRelativeTo(frame)
    dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), 'cancelBatch')
    dialog.rootPane.actionMap.put('cancelBatch', new AbstractAction() {
        @Override void actionPerformed(ActionEvent event) { cancel.doClick() }
    })
    [dialog: dialog, current: current, progress: progress, cancel: cancel]
}

def updateBatchProgress = { Map controls, int completed, int total, String detail ->
    SwingUtilities.invokeLater {
        if (!(controls.dialog as JDialog).displayable) return
        (controls.current as JLabel).text = detail
        (controls.progress as JProgressBar).value = completed
        (controls.progress as JProgressBar).string = "${completed} of ${total}"
    }
}

def showBatchSummary = { String title, String headline, List<String> details, boolean warning ->
    JTextArea ledger = new JTextArea(details.join('\n'), Math.min(16, Math.max(5, details.size())), 76)
    ledger.editable = false
    ledger.lineWrap = false
    ledger.caretPosition = 0
    ledger.font = new Font(Font.MONOSPACED, Font.PLAIN, 12)
    JScrollPane scroll = new JScrollPane(ledger)
    int ledgerHeight = Math.min(330, Math.max(130, ledger.preferredSize.height + 12)) as int
    scroll.preferredSize = AwtGeometry.dimension(760, ledgerHeight)
    JPanel panel = new JPanel(new BorderLayout(0, 10))
    panel.add(new JLabel("<html>${headline.replace('\n', '<br>')}</html>"), BorderLayout.NORTH)
    panel.add(scroll, BorderLayout.CENTER)
    JOptionPane.showMessageDialog(frame, panel, title,
        warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE)
}

def previewImage = { ImagePlus imp, int maxW, int maxH ->
    if (!imp) return null
    BufferedImage src
    try { src = imp.overlay ? imp.flatten().bufferedImage : imp.bufferedImage }
    catch (ignored) { src = imp.bufferedImage }
    double scale = Math.min(maxW/(double)src.width, maxH/(double)src.height)
    scale = Math.min(1d,scale)
    int w = Math.max(1,(int)(src.width*scale)); int h=Math.max(1,(int)(src.height*scale))
    def out = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB)
    def g = out.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src,0,0,w,h,null); g.dispose(); out
}

def copyNativeImage = { BufferedImage source ->
    NativeImageTransforms.copyOf(source)
}

def applyNativeImageEdits = { BufferedImage source, List<String> operations ->
    NativeImageTransforms.apply(source, operations ?: [])
}

def drawScaleBarOnNativeImage = { BufferedImage target, Overlay overlay ->
    if (target == null || overlay == null) return
    Graphics2D g = target.createGraphics()
    try {
        ScaleBarOverlayTools.draw(g,overlay)
    } finally {
        g.dispose()
    }
}

def resizeNativeImage = { BufferedImage source, int targetWidth, int targetHeight ->
    if (source.width == targetWidth && source.height == targetHeight) return source
    def raster = source.colorModel.createCompatibleWritableRaster(targetWidth, targetHeight)
    BufferedImage resized = new BufferedImage(source.colorModel, raster, source.alphaPremultiplied, null)
    Graphics2D g = resized.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
        g.dispose()
    }
    resized
}

def nativeChannelDepth = { BufferedImage image ->
    if (image == null) return 0
    int[] componentSizes = image.colorModel.componentSize
    componentSizes ? componentSizes.max() as int : 0
}

def sourceFormat = { File file ->
    String name = file.name.toLowerCase(Locale.ROOT)
    if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'JPEG'
    if (name.endsWith('.tif') || name.endsWith('.tiff')) return 'TIFF'
    if (name.endsWith('.png')) return 'PNG'
    return null
}

def selectIndex = null
def closeImageIndices = null

selectIndex = { int idx, MouseEvent e ->
    if (idx < 0 || idx >= images.size()) return
    if (e?.shiftDown && anchorIndex >= 0) {
        selected.clear(); int a=Math.min(anchorIndex,idx), b=Math.max(anchorIndex,idx); (a..b).each { selected.add(it) }
    } else if (e?.controlDown) {
        if (selected.contains(idx)) selected.remove(idx) else selected.add(idx)
        anchorIndex=idx
    } else {
        selected.clear(); selected.add(idx); anchorIndex=idx
    }
    currentIndex=idx
    render()
}

closeImageIndices = { Collection<Integer> requestedIndices ->
    List<Integer> closing = (requestedIndices ?: [])
        .findAll { Integer idx -> idx != null && idx >= 0 && idx < images.size() }
        .unique()
        .sort()
    if (closing.isEmpty()) return

    Set<Integer> closingSet = new LinkedHashSet<Integer>(closing)
    Set<Integer> previousSelection = new LinkedHashSet<Integer>(selected)
    int previousCurrent = currentIndex
    int previousAnchor = anchorIndex

    closing.reverseEach { Integer idx ->
        ImagePlus image = images.remove(idx as int) as ImagePlus
        sourceFiles.remove(idx as int)
        appliedPresetNames.remove(image)
        appliedPresetData.remove(image)
        sourceImageDetails.remove(image)
        editHistories.remove(image)
        editSnapshots.remove(image)
        nativeEditHistories.remove(image)
        scaleBarPositions.remove(image)
        try { image.close() } catch (ignored) {}
    }

    def shiftedIndex = { int oldIndex ->
        if (oldIndex < 0) return -1
        oldIndex - closing.count { Integer removedIndex -> removedIndex < oldIndex }
    }

    selected.clear()
    previousSelection.each { Integer oldIndex ->
        if (!closingSet.contains(oldIndex)) selected.add(shiftedIndex(oldIndex as int) as int)
    }

    if (images.isEmpty()) {
        currentIndex = -1
        anchorIndex = -1
    } else {
        if (previousCurrent >= 0 && !closingSet.contains(previousCurrent)) {
            currentIndex = shiftedIndex(previousCurrent) as int
        } else {
            int removedBeforeCurrent = closing.count { Integer removedIndex -> removedIndex < previousCurrent }
            currentIndex = Math.max(0, Math.min(images.size() - 1, previousCurrent - removedBeforeCurrent))
        }
        if (selected.isEmpty()) selected.add(currentIndex)
        anchorIndex = previousAnchor >= 0 && !closingSet.contains(previousAnchor) ?
            shiftedIndex(previousAnchor) as int : currentIndex
    }
    displayedIndex = -1
    render()
}

def makeTile = { int idx, boolean large ->
    def imp = images[idx] as ImagePlus
    boolean isSelected=selected.contains(idx)
    def tile = new JPanel(new BorderLayout(0,0))
    tile.background = PANEL_2
    tile.border = BorderFactory.createLineBorder(isSelected ? ACCENT : BORDER,isSelected ? 2 : 1)
    int w = large ? 340 : 140, h = large ? 220 : 90
    JComponent imageLabel
    if (large) {
        imageLabel = new GridThumbnail(previewImage(imp, 1400, 1400), BG)
    } else {
        def icon = new ImageIcon(previewImage(imp,w,h))
        imageLabel = new JLabel(icon,SwingConstants.CENTER)
        imageLabel.background=BG
        imageLabel.opaque=true
    }
    String displayName=imp.title ?: "Image ${idx+1}"
    if (!imageEditHistory(imp).isEmpty()) displayName += '  • Edited'
    if (!large && displayName.length()>19) displayName=displayName.take(16)+'…'
    def name = new JLabel('  '+displayName); name.foreground=TEXT; name.font=name.font.deriveFont(12f)
    def footer=new JPanel(new BorderLayout()); footer.background=PANEL_2; footer.preferredSize=AwtGeometry.dimension(w,28)
    footer.add(name,BorderLayout.CENTER)
    if (isSelected) {
        def selectedMark=new SelectionBadge(ACCENT)
        def markWrap=new JPanel(new GridBagLayout()); markWrap.background=PANEL_2; markWrap.border=new EmptyBorder(3,3,3,3); markWrap.add(selectedMark); footer.add(markWrap,BorderLayout.EAST)
    }
    tile.add(imageLabel,BorderLayout.CENTER); tile.add(footer,BorderLayout.SOUTH)
    tile.preferredSize = AwtGeometry.dimension(w+4,h+30)
    def showTileMenu = { MouseEvent e ->
        if (!e.popupTrigger) return
        if (!selected.contains(idx)) {
            selected.clear()
            selected.add(idx)
        }
        currentIndex = idx
        anchorIndex = idx

        def popup = new JPopupMenu()
        def openLoupeItem = new JMenuItem('Open in Loupe')
        openLoupeItem.addActionListener {
            loupeButton.selected = true
            gridMode = false
            displayedIndex = -1
            render()
        }
        def closeImageItem = new JMenuItem('Close image')
        closeImageItem.addActionListener { closeImageIndices([idx]) }
        def closeSelectedItem = new JMenuItem('Close selected')
        closeSelectedItem.enabled = !selected.isEmpty()
        closeSelectedItem.addActionListener { closeImageIndices(new ArrayList<Integer>(selected)) }
        popup.add(openLoupeItem)
        popup.addSeparator()
        popup.add(closeImageItem)
        popup.add(closeSelectedItem)
        popup.show(e.component,e.x,e.y)
        e.consume()
    }
    def clicker = new MouseAdapter() {
        void mousePressed(MouseEvent e) { showTileMenu(e) }
        void mouseReleased(MouseEvent e) { showTileMenu(e) }
        void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isRightMouseButton(e) || e.popupTrigger) return
            selectIndex(idx,e)
            if (large && e.clickCount==2) { loupeButton.selected=true; gridMode=false; render() }
        }
    }
    tile.addMouseListener(clicker); imageLabel.addMouseListener(clicker); name.addMouseListener(clicker); footer.addMouseListener(clicker)
    tile
}

render = {
    filmstrip.removeAll(); gridPanel.removeAll()
    images.eachWithIndex { imp, idx ->
        filmstrip.add(makeTile(idx,false)); gridPanel.add(makeTile(idx,true))
    }
    if (gridMode) {
        workspace.remove(canvasHost); workspace.remove(filmScroll); workspace.add(gridScroll,BorderLayout.CENTER)
        fileLabel.text = "Batch — ${images.size()} images"
    } else {
        workspace.remove(gridScroll); workspace.add(canvasHost,BorderLayout.CENTER); workspace.add(filmScroll,BorderLayout.SOUTH)
        if (currentIndex >= 0) {
            def imp=images[currentIndex] as ImagePlus
            boolean changedImage = displayedIndex != currentIndex
            zoomCanvas.setImage(imp.bufferedImage, changedImage)
            zoomCanvas.setScaleBarOverlay(imp.overlay, overlayField.selected)
            displayedIndex = currentIndex
            fileLabel.text=imp.title + (imageEditHistory(imp).isEmpty() ? '' : '  • Edited')
            statusLabel.text=presetCalibrationSummary(currentPreset() as Map)
        } else {
            zoomCanvas.setImage(null,true)
            zoomCanvas.setScaleBarOverlay(null,false)
            displayedIndex=-1; fileLabel.text='Open images to begin'; statusLabel.text='No image loaded'
        }
    }
    selectionLabel.text="${selected.size()} of ${images.size()} images selected"
    bottom.text=gridMode ?
        "  ${images.size()} images     |     ${selected.size()} selected     |     Preset: ${presetCombo.selectedItem ?: 'None'}" :
        "  ${images.size()} images     |     ${selected.size()} selected     |     ${zoomCanvas.zoomPercent()}%     |     Preset: ${presetCombo.selectedItem ?: 'None'}"
    applySelectedButton.enabled=!selected.isEmpty(); applyAllButton.enabled=!images.isEmpty(); saveAllButton.enabled=!images.isEmpty()
    saveAsItem.enabled = currentIndex >= 0 && currentIndex < images.size()
    boolean hasCurrentImage = currentIndex >= 0 && currentIndex < images.size()
    refreshUndoControls(hasCurrentImage ? images[currentIndex] as ImagePlus : null)
    [cropItem, brightnessContrastItem, channelBalanceItem, rotateRightItem, rotateLeftItem, flipHorizontalItem, flipVerticalItem, imageInfoItem].each { it.enabled = hasCurrentImage }
    [pointerTool,cropTool,rotateTool,measureTool].each { AbstractButton tool ->
        tool.enabled = hasCurrentImage
        tool.foreground = hasCurrentImage ? TEXT : MUTED
    }
    if (hasCurrentImage) channelBalanceItem.enabled = BasicImageEdits.supportsChannelBalance(images[currentIndex] as ImagePlus)
    workspace.revalidate(); workspace.repaint(); filmstrip.revalidate(); gridPanel.revalidate()
}

saveAsItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    File source = sourceFiles[currentIndex] as File
    String originalStem = source.name.replaceFirst(/\.[^.]+$/, '')
    String appliedPresetName = appliedPresetNames.get(imp)
    String suggestedStem = originalStem + (appliedPresetName ? " - ${appliedPresetName}" : '')

    JFileChooser chooser = new JFileChooser(source.parentFile ?: new File('.').absoluteFile)
    SwingUtilities.updateComponentTreeUI(chooser)
    chooser.dialogTitle = 'Save active image as a new copy'
    chooser.dialogType = JFileChooser.SAVE_DIALOG
    chooser.acceptAllFileFilterUsed = false
    FileNameExtensionFilter pngFilter = new FileNameExtensionFilter('PNG image (*.png)', 'png')
    FileNameExtensionFilter tiffFilter = new FileNameExtensionFilter('TIFF image (*.tif, *.tiff)', 'tif', 'tiff')
    FileNameExtensionFilter jpegFilter = new FileNameExtensionFilter('JPEG image (*.jpg, *.jpeg)', 'jpg', 'jpeg')
    chooser.addChoosableFileFilter(pngFilter)
    chooser.addChoosableFileFilter(tiffFilter)
    chooser.addChoosableFileFilter(jpegFilter)
    String detectedFormat = sourceFormat(source) ?: 'PNG'
    chooser.fileFilter = detectedFormat == 'JPEG' ? jpegFilter : detectedFormat == 'TIFF' ? tiffFilter : pngFilter
    String suggestedExtension = detectedFormat == 'JPEG' ? '.jpg' : detectedFormat == 'TIFF' ? '.tif' : '.png'
    chooser.selectedFile = new File(source.parentFile, suggestedStem + suggestedExtension)
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return

    File target = chooser.selectedFile
    String lowerName = target.name.toLowerCase(Locale.ROOT)
    String format
    if (lowerName.endsWith('.tif') || lowerName.endsWith('.tiff')) {
        format = 'TIFF'
    } else if (lowerName.endsWith('.jpg') || lowerName.endsWith('.jpeg')) {
        format = 'JPEG'
    } else if (lowerName.endsWith('.png')) {
        format = 'PNG'
    } else if (chooser.fileFilter == tiffFilter) {
        format = 'TIFF'; target = new File(target.parentFile, target.name + '.tif')
    } else if (chooser.fileFilter == jpegFilter) {
        format = 'JPEG'; target = new File(target.parentFile, target.name + '.jpg')
    } else {
        format = 'PNG'; target = new File(target.parentFile, target.name + '.png')
    }

    try {
        if (target.canonicalFile == source.canonicalFile) {
            JOptionPane.showMessageDialog(frame,
                'Save As will not overwrite the original image. Choose a different filename or folder.',
                'Original protected', JOptionPane.WARNING_MESSAGE)
            return
        }
        if (target.exists()) {
            int replace = JOptionPane.showConfirmDialog(frame,
                "${target.name} already exists. Replace it?",
                'Replace existing copy?', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (replace != JOptionPane.YES_OPTION) return
        }

        boolean success = false
        if (format == 'PNG') {
            BufferedImage nativeSource = ImageIO.read(source)
            if (nativeSource != null && nativeChannelDepth(nativeSource) > 8) {
                BufferedImage nativeCopy = applyNativeImageEdits(nativeSource, nativeImageEditHistory(imp))
                if (imp.overlay) drawScaleBarOnNativeImage(nativeCopy, imp.overlay)
                success = ImageIO.write(nativeCopy, 'PNG', target)
            }
        }
        if (!success) {
            ImagePlus copy = imp.overlay ? imp.flatten() : imp.duplicate()
            try {
                int previousJpegQuality = FileSaver.getJpegQuality()
                try {
                    FileSaver.setJpegQuality(prefs.getInt('save.jpegQuality', 95))
                    FileSaver saver = new FileSaver(copy)
                    success = format == 'TIFF' ? saver.saveAsTiff(target.absolutePath) :
                        format == 'JPEG' ? saver.saveAsJpeg(target.absolutePath) : saver.saveAsPng(target.absolutePath)
                } finally {
                    FileSaver.setJpegQuality(previousJpegQuality)
                }
            } finally {
                copy.close()
            }
        }
        if (!success) throw new IOException('the image encoder did not save a file')
        String completion = "Saved a copy to\n${target.absolutePath}\n\nThe original image was not modified."
        JOptionPane.showMessageDialog(frame,
            completion,
            'Save As complete', JOptionPane.INFORMATION_MESSAGE)
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,
            "The image could not be saved:\n${error.message ?: error.class.simpleName}",
            'Save As error', JOptionPane.ERROR_MESSAGE)
    }
}

def openFiles = { File[] files ->
    files.each { f ->
        def imp=IJ.openImage(f.absolutePath)
        if (imp) {
            imp.title=f.name
            images.add(imp)
            sourceFiles.add(f)
            sourceImageDetails.put(imp,[width:imp.width,height:imp.height,bitDepth:imp.bitDepth])
            editHistories.put(imp, [])
            editSnapshots.put(imp, [])
            nativeEditHistories.put(imp, [])
        }
    }
    if (!images.isEmpty()) { currentIndex=0; anchorIndex=0; selected.clear(); selected.add(0) }
    render()
}

List<File> launchFiles = binding.hasVariable('fijicalLaunchFiles') ?
    ((binding.getVariable('fijicalLaunchFiles') ?: []) as List<File>) : Collections.emptyList()
if (!launchFiles.isEmpty()) openFiles(launchFiles as File[])

openItem.addActionListener {
    def fc=new JFileChooser(); SwingUtilities.updateComponentTreeUI(fc); fc.multiSelectionEnabled=true; fc.fileSelectionMode=JFileChooser.FILES_ONLY
    fc.fileFilter=new FileNameExtensionFilter('Images (TIFF, PNG, JPEG)','tif','tiff','png','jpg','jpeg')
    if (fc.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION) openFiles(fc.selectedFiles)
}
def clearBatch = {
    images.each { ImagePlus image ->
        try { image.close() } catch (ignored) {}
    }
    images.clear()
    sourceFiles.clear()
    appliedPresetNames.clear()
    appliedPresetData.clear()
    sourceImageDetails.clear()
    editHistories.clear()
    editSnapshots.clear()
    nativeEditHistories.clear()
    scaleBarPositions.clear()
    selected.clear()
    currentIndex = -1
    anchorIndex = -1
    displayedIndex = -1
}
def exitApplication = {
    clearBatch()
    frame.ownedWindows.each { Window owned ->
        try { owned.dispose() } catch (ignored) {}
    }
    frame.dispose()
}
closeBatchItem.addActionListener { clearBatch(); render() }
exitItem.addActionListener { exitApplication() }
frame.addWindowListener(new WindowAdapter() {
    @Override void windowClosing(WindowEvent event) { exitApplication() }
})

new DropTarget(canvasHost,new java.awt.dnd.DropTargetAdapter(){
    void drop(java.awt.dnd.DropTargetDropEvent e) {
        try { e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY); openFiles((e.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List).findAll{it instanceof File} as File[]) } catch(ignored) { e.rejectDrop() }
    }
})

presetCombo.addActionListener {
    if (presetCombo.selectedIndex >= 0) { showPreset(); render() }
}
overlayField.addActionListener { render() }
loupeButton.addActionListener { gridMode=false; loupeButton.background=ACCENT_DARK; gridButton.background=PANEL_2; render() }
gridButton.addActionListener { gridMode=true; gridButton.background=ACCENT_DARK; loupeButton.background=PANEL_2; render() }
fitButton.addActionListener { zoomCanvas.fitImage() }
actualButton.addActionListener { zoomCanvas.actualSize() }
pointerTool.addActionListener {
    if (zoomCanvas.cropMode) cancelCropItem.doClick()
    zoomCanvas.cursor = Cursor.getDefaultCursor()
}
cropTool.addActionListener {
    if (currentIndex >= 0 && currentIndex < images.size()) cropItem.doClick()
    else pointerTool.selected = true
}
rotateTool.addActionListener { if (rotateRightItem.enabled) rotateRightItem.doClick() }
measureTool.addActionListener { if (calibrateItem.enabled) calibrateItem.doClick() }

def editCurrentImage = { String operation ->
    if (currentIndex < 0 || currentIndex >= images.size()) return
    try {
        performImageEdit(images[currentIndex] as ImagePlus, operation, true)
        displayedIndex = -1
        render()
        Map<String,String> editLabels = [rotateRight:'Rotate Right 90°',rotateLeft:'Rotate Left 90°',
                                         flipHorizontal:'Flip Horizontal',flipVertical:'Flip Vertical']
        statusLabel.text = "Applied ${editLabels[operation] ?: operation} · Ctrl+Z to undo"
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,
            "The image edit could not be applied:\n${error.message ?: error.class.simpleName}",
            'Image edit error', JOptionPane.ERROR_MESSAGE)
    }
}

def addCustomEdit = { ImagePlus imp, String label, Map nativeOperation, Closure work ->
    snapshotImageEdit(imp)
    try {
        work.call()
        imageEditHistory(imp).add(label)
        nativeImageEditHistory(imp).add(new LinkedHashMap(nativeOperation))
        refreshAppliedScaleBar(imp)
        imp.updateAndDraw()
        displayedIndex = -1
        render()
        refreshUndoControls(imp)
        statusLabel.text = "Applied ${label} · Ctrl+Z to undo"
    } catch (Exception error) {
        List<Map> snapshots = imageEditSnapshots(imp)
        if (!snapshots.isEmpty()) {
            Map snapshot = snapshots.remove(snapshots.size()-1)
            restoreImageEditSnapshot(imp,snapshot)
        }
        throw error
    }
}

cropItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    zoomCanvas.beginCrop()
    statusLabel.text = 'Crop mode · drag a handle to resize, drag inside to move, or drag outside to draw a new crop.'
}

cancelCropItem.addActionListener {
    zoomCanvas.cancelCrop()
    statusLabel.text = 'Crop cancelled; image unchanged.'
}
frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), 'cancelCurrentTool')
frame.rootPane.actionMap.put('cancelCurrentTool', new AbstractAction() {
    @Override void actionPerformed(ActionEvent event) {
        if (zoomCanvas.cropMode) cancelCropItem.doClick()
    }
})

applyCropItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    Rectangle selection = zoomCanvas.takeCropSelection()
    applyCropItem.enabled = false
    cancelCropItem.enabled = false
    if (selection == null || selection.width < 2 || selection.height < 2) {
        statusLabel.text = 'Crop was not applied; image unchanged.'
        return
    }
    ImagePlus imp = images[currentIndex] as ImagePlus
    try {
        int cropX = selection.x, cropY = selection.y, cropW = selection.width, cropH = selection.height
        addCustomEdit(imp,"crop ${cropW}×${cropH}",
            [type:'crop',x:cropX,y:cropY,width:cropW,height:cropH]) {
            Point currentBarPosition = scaleBarPositions.get(imp)
            if (currentBarPosition != null)
                scaleBarPositions.put(imp,AwtGeometry.point(currentBarPosition.getX()-cropX,currentBarPosition.getY()-cropY))
            imp.setRoi(cropX,cropY,cropW,cropH)
            ImagePlus cropped = imp.crop('stack')
            imp.deleteRoi()
            if (cropped == null) throw new IllegalStateException('ImageJ did not return a cropped image')
            imp.setImage(cropped)
            imp.getCalibration().xOrigin -= cropX
            imp.getCalibration().yOrigin -= cropY
        }
    } catch (Exception error) {
        statusLabel.text = "Crop failed safely: ${error.message ?: error.class.simpleName}"
    }
}

applyCropButton.addActionListener { applyCropItem.doClick() }
cancelCropButton.addActionListener { cancelCropItem.doClick() }

brightnessContrastItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    def brightness = new JSpinner(new SpinnerNumberModel(0d,-100d,100d,1d))
    def contrast = new JSpinner(new SpinnerNumberModel(100d,0d,300d,1d))
    [brightness,contrast].each { styleInput(it as JComponent) }
    def panel=new JPanel(new GridLayout(0,2,8,8)); panel.background=PANEL; panel.border=new EmptyBorder(10,10,10,10)
    panel.add(new JLabel('Brightness (%)')); panel.add(brightness)
    panel.add(new JLabel('Contrast (%)')); panel.add(contrast)
    int choice=JOptionPane.showConfirmDialog(frame,panel,'Brightness & Contrast',JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)
    if (choice != JOptionPane.OK_OPTION) return
    try {
        double b=(brightness.value as Number).doubleValue(), c=(contrast.value as Number).doubleValue()
        addCustomEdit(imp,"brightness ${b}% / contrast ${c}%",
            [type:'brightnessContrast',brightness:b,contrast:c]) {
            BasicImageEdits.brightnessContrast(imp,b,c)
        }
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,"The tonal adjustment could not be applied:\n${error.message ?: error.class.simpleName}",'Brightness & contrast error',JOptionPane.ERROR_MESSAGE)
    }
}

channelBalanceItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    if (!BasicImageEdits.supportsChannelBalance(imp)) return
    def red = new JSpinner(new SpinnerNumberModel(100d,0d,300d,1d))
    def green = new JSpinner(new SpinnerNumberModel(100d,0d,300d,1d))
    def blue = new JSpinner(new SpinnerNumberModel(100d,0d,300d,1d))
    [red,green,blue].each { styleInput(it as JComponent) }
    def panel=new JPanel(new GridLayout(0,2,8,8)); panel.background=PANEL; panel.border=new EmptyBorder(10,10,10,10)
    panel.add(new JLabel('Red (%)')); panel.add(red); panel.add(new JLabel('Green (%)')); panel.add(green); panel.add(new JLabel('Blue (%)')); panel.add(blue)
    int choice=JOptionPane.showConfirmDialog(frame,panel,'RGB Channel Balance',JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)
    if (choice != JOptionPane.OK_OPTION) return
    try {
        double r=(red.value as Number).doubleValue(), g=(green.value as Number).doubleValue(), b=(blue.value as Number).doubleValue()
        addCustomEdit(imp,"RGB ${r}/${g}/${b}%",
            [type:'channelBalance',red:r,green:g,blue:b]) {
            BasicImageEdits.channelBalance(imp,r,g,b)
        }
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,"The channel adjustment could not be applied:\n${error.message ?: error.class.simpleName}",'Channel balance error',JOptionPane.ERROR_MESSAGE)
    }
}

imageInfoItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    File source = sourceFiles[currentIndex] as File
    def fileInfo = imp.originalFileInfo
    String embedded = (imp.getProperty('Info') ?: fileInfo?.description ?: fileInfo?.info ?: '') as String
    if (embedded.length() > 6000) embedded = embedded.substring(0,6000) + '\n\n[Embedded information truncated]'
    def cal=imp.getCalibration()
    String report = """IMAGE\nName: ${imp.title}\nSource: ${source.absolutePath}\nDimensions: ${imp.width} × ${imp.height} px\nBit depth: ${imp.bitDepth}-bit\nChannels: ${imp.nChannels}\n\nCALIBRATION\nPixel width: ${cal.pixelWidth}\nPixel height: ${cal.pixelHeight}\nUnit: ${cal.unit ?: 'pixel'}\nPixels per unit: ${cal.pixelWidth > 0d ? 1d/cal.pixelWidth : 'n/a'}\n\nEMBEDDED INFORMATION\n${embedded ?: '(No embedded ImageJ information was available for this image.)'}"""
    def text=new JTextArea(report,24,84); text.editable=false; text.caretPosition=0; text.background=INPUT; text.foreground=TEXT; text.font=new Font(Font.MONOSPACED,Font.PLAIN,12)
    JOptionPane.showMessageDialog(frame,new JScrollPane(text),'Metadata & Calibration',JOptionPane.PLAIN_MESSAGE)
}

rotateRightItem.addActionListener { editCurrentImage('rotateRight') }
rotateLeftItem.addActionListener { editCurrentImage('rotateLeft') }
flipHorizontalItem.addActionListener { editCurrentImage('flipHorizontal') }
flipVerticalItem.addActionListener { editCurrentImage('flipVertical') }

undoEditItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    List<String> history = imageEditHistory(imp)
    List<Map> snapshots = imageEditSnapshots(imp)
    if (history.isEmpty() || snapshots.isEmpty()) return
    String previous = history[history.size() - 1]
    Map snapshot = snapshots[snapshots.size() - 1]
    try {
        restoreImageEditSnapshot(imp, snapshot)
        snapshots.remove(snapshots.size() - 1)
        displayedIndex = -1
        render()
        statusLabel.text = "Undid ${previous}"
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,
            "The image edit could not be undone:\n${error.message ?: error.class.simpleName}",
            'Undo error', JOptionPane.ERROR_MESSAGE)
    }
}

frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
    KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK),'undoFijiCalImageEdit')
frame.rootPane.getActionMap().put('undoFijiCalImageEdit',new AbstractAction() {
    @Override void actionPerformed(ActionEvent event) {
        if (undoEditItem.enabled) undoEditItem.doClick()
    }
})

resetEditsItem.addActionListener {
    if (currentIndex < 0 || currentIndex >= images.size()) return
    ImagePlus imp = images[currentIndex] as ImagePlus
    try {
        while (!imageEditSnapshots(imp).isEmpty()) {
            List<Map> snapshots = imageEditSnapshots(imp)
            Map snapshot = snapshots[snapshots.size() - 1]
            restoreImageEditSnapshot(imp, snapshot)
            snapshots.remove(snapshots.size() - 1)
        }
        displayedIndex = -1
        render()
        statusLabel.text = 'Reset all image edits'
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,
            "The image edits could not be fully reset:\n${error.message ?: error.class.simpleName}",
            'Reset edits error', JOptionPane.ERROR_MESSAGE)
        render()
    }
}

applySelectedButton.addActionListener {
    def p=currentPreset()
    selected.each {
        ImagePlus imp = images[it] as ImagePlus
        scaleBarPositions.remove(imp)
        applyPreset(imp, p, axesSwappedFor(imp))
        appliedPresetNames.put(imp, (p.name ?: presetCombo.selectedItem ?: 'Preset') as String)
        appliedPresetData.put(imp, new LinkedHashMap(p as Map))
    }
    render()
}
applyAllButton.addActionListener {
    if (images.isEmpty()) return
    Map preset = new LinkedHashMap(currentPreset() as Map)
    String presetName = (preset.name ?: presetCombo.selectedItem ?: 'Preset') as String
    List<ImagePlus> batchImages = new ArrayList<ImagePlus>(images as List<ImagePlus>)
    int total = batchImages.size()
    Map progressUi = createBatchProgressDialog('Apply to All', "Applying '${presetName}' to ${total} images", total)
    AtomicBoolean cancelRequested = new AtomicBoolean(false)
    (progressUi.cancel as JButton).addActionListener {
        cancelRequested.set(true)
        (progressUi.cancel as JButton).enabled = false
        (progressUi.current as JLabel).text = 'Cancelling after the current image…'
    }
    (progressUi.dialog as JDialog).addWindowListener(new WindowAdapter() {
        @Override void windowClosing(WindowEvent event) { (progressUi.cancel as JButton).doClick() }
    })

    Thread worker = new Thread({
        int applied = 0
        int skipped = 0
        List<String> ledger = []
        Map<ImagePlus, String> successful = new IdentityHashMap<ImagePlus, String>()
        for (int idx = 0; idx < total; idx++) {
            if (cancelRequested.get()) break
            ImagePlus imp = batchImages[idx]
            String imageName = imp.title ?: "Image ${idx + 1}"
            updateBatchProgress(progressUi, idx, total, "Checking ${imageName}")
            String incompatibility = presetCompatibilityError(imp, preset)
            if (incompatibility) {
                skipped++
                ledger.add("SKIPPED  ${imageName} — ${incompatibility}")
                updateBatchProgress(progressUi, idx + 1, total, "Skipped ${imageName}")
                continue
            }
            try {
                scaleBarPositions.remove(imp)
                applyPreset(imp, preset, axesSwappedFor(imp))
                successful.put(imp, presetName)
                applied++
                ledger.add("APPLIED  ${imageName} — ${presetName}")
            } catch (Exception error) {
                skipped++
                ledger.add("ERROR    ${imageName} — ${error.message ?: error.class.simpleName}")
            }
            updateBatchProgress(progressUi, idx + 1, total, "Processed ${imageName}")
        }
        int unprocessed = total - applied - skipped
        boolean cancelled = cancelRequested.get()
        if (cancelled && unprocessed > 0) ledger.add("CANCELLED  ${unprocessed} images were not processed")
        SwingUtilities.invokeLater {
            successful.each { ImagePlus imp, String name ->
                appliedPresetNames.put(imp, name)
                appliedPresetData.put(imp, new LinkedHashMap(preset))
            }
            (progressUi.dialog as JDialog).dispose()
            render()
            String headline = "Applied '${presetName}' to ${applied} of ${total} images."
            if (skipped) headline += "\nSkipped or failed: ${skipped}."
            if (cancelled) headline += "\nCancelled; not processed: ${unprocessed}."
            showBatchSummary(cancelled ? 'Apply to All cancelled' : (skipped ? 'Apply to All completed with issues' : 'Apply to All complete'),
                headline, ledger, cancelled || skipped > 0)
        }
    }, 'FijiCal Apply to All')
    worker.daemon = true
    worker.start()
    (progressUi.dialog as JDialog).visible = true
}

saveAllButton.addActionListener {
    if (images.isEmpty()) return
    File firstSource = sourceFiles[0] as File
    File sourceParent = firstSource.parentFile ?: new File('.').absoluteFile
    File defaultDir = new File(sourceParent, 'Scale')

    JTextField folderField = new JTextField(defaultDir.absolutePath, 36)
    JButton browseButton = new JButton('Browse…')
    JPanel folderRow = new JPanel(new BorderLayout(8, 0))
    folderRow.add(folderField, BorderLayout.CENTER)
    folderRow.add(browseButton, BorderLayout.EAST)

    JComboBox<String> formatField = new JComboBox<>(['PNG', 'TIFF', 'JPEG'] as String[])
    List<String> detectedFormats = sourceFiles.collect { sourceFormat(it as File) }.unique()
    formatField.selectedItem = detectedFormats.size() == 1 && detectedFormats[0] ?
        detectedFormats[0] : prefs.get('save.format', 'PNG')
    JComboBox<String> namingField = new JComboBox<>(['Numbered sequence', 'Keep original names'] as String[])
    namingField.selectedItem = prefs.get('save.naming', 'Numbered sequence')
    JTextField sequenceField = new JTextField(prefs.get('save.sequenceName', 'Image'), 18)
    JSpinner startField = new JSpinner(new SpinnerNumberModel(prefs.getInt('save.start', 1), 0, 1000000, 1))
    JSpinner digitsField = new JSpinner(new SpinnerNumberModel(prefs.getInt('save.digits', 3), 1, 9, 1))
    JTextField prefixField = new JTextField(prefs.get('save.prefix', ''), 10)
    JTextField suffixField = new JTextField(prefs.get('save.suffix', ''), 10)

    JPanel sequenceOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    sequenceOptions.add(new JLabel('Name'))
    sequenceOptions.add(sequenceField)
    sequenceOptions.add(new JLabel('Start'))
    sequenceOptions.add(startField)
    sequenceOptions.add(new JLabel('Digits'))
    sequenceOptions.add(digitsField)

    JPanel affixOptions = new JPanel(new GridLayout(1, 2, 8, 0))
    JPanel prefixPanel = new JPanel(new BorderLayout(6, 0))
    prefixPanel.add(new JLabel('Prefix'), BorderLayout.WEST)
    prefixPanel.add(prefixField, BorderLayout.CENTER)
    JPanel suffixPanel = new JPanel(new BorderLayout(6, 0))
    suffixPanel.add(new JLabel('Suffix'), BorderLayout.WEST)
    suffixPanel.add(suffixField, BorderLayout.CENTER)
    affixOptions.add(prefixPanel)
    affixOptions.add(suffixPanel)

    JComboBox<String> resizeField = new JComboBox<>(['Original size', 'Percentage', 'Target width', 'Target height'] as String[])
    resizeField.selectedItem = prefs.get('save.resizeMode', 'Original size')
    JSpinner resizeValueField = new JSpinner(new SpinnerNumberModel(prefs.getInt('save.resizeValue', 100), 1, 100000, 1))
    JPanel resizeOptions = new JPanel(new BorderLayout(8, 0))
    resizeOptions.add(resizeField, BorderLayout.CENTER)
    resizeOptions.add(resizeValueField, BorderLayout.EAST)

    JComboBox<String> conflictField = new JComboBox<>(['Automatically rename', 'Skip existing', 'Overwrite'] as String[])
    conflictField.selectedItem = prefs.get('save.conflicts', 'Automatically rename')
    JSpinner qualityField = new JSpinner(new SpinnerNumberModel(prefs.getInt('save.jpegQuality', 95), 1, 100, 1))
    JCheckBox includeBarsField = new JCheckBox('Include scale bars (flatten into exported copies)', true)
    JCheckBox appendPresetField = new JCheckBox('Append applied preset name to each filename', prefs.getBoolean('save.appendPreset', true))
    appendPresetField.toolTipText = 'For example: Image_001 - 4x Obj.png. Images without an applied preset keep their normal name.'
    JCheckBox minifyPngField = new JCheckBox('Minify PNG (convert to 8-bit/channel)', prefs.getBoolean('save.minifyPng', false))
    minifyPngField.toolTipText = 'Produces smaller display copies, but reduces 16-bit source precision. Off preserves native PNG bit depth.'
    JLabel safetyLabel = new JLabel('Original files are never modified.')
    safetyLabel.foreground = new Color(20, 112, 103)

    JPanel form = new JPanel(new GridBagLayout())
    form.border = new EmptyBorder(8, 8, 4, 8)
    Closure addSaveRow = { int row, String labelText, Component component ->
        GridBagConstraints labelGc = new GridBagConstraints()
        labelGc.gridx = 0; labelGc.gridy = row; labelGc.anchor = GridBagConstraints.WEST
        labelGc.insets = new Insets(5, 4, 5, 12)
        JLabel label = new JLabel(labelText)
        label.font = label.font.deriveFont(Font.BOLD)
        form.add(label, labelGc)
        GridBagConstraints fieldGc = new GridBagConstraints()
        fieldGc.gridx = 1; fieldGc.gridy = row; fieldGc.weightx = 1
        fieldGc.fill = GridBagConstraints.HORIZONTAL
        fieldGc.insets = new Insets(5, 4, 5, 4)
        form.add(component, fieldGc)
    }

    addSaveRow(0, 'Output folder', folderRow)
    addSaveRow(1, 'Format', formatField)
    addSaveRow(2, 'File naming', namingField)
    addSaveRow(3, 'Sequence', sequenceOptions)
    addSaveRow(4, 'Prefix / suffix', affixOptions)
    addSaveRow(5, 'Resize', resizeOptions)
    addSaveRow(6, 'Existing files', conflictField)
    addSaveRow(7, 'JPEG quality', qualityField)
    GridBagConstraints optionGc = new GridBagConstraints()
    optionGc.gridx = 1; optionGc.gridy = 8; optionGc.anchor = GridBagConstraints.WEST
    optionGc.insets = new Insets(8, 4, 3, 4)
    form.add(includeBarsField, optionGc)
    GridBagConstraints presetNameGc = new GridBagConstraints()
    presetNameGc.gridx = 1; presetNameGc.gridy = 9; presetNameGc.anchor = GridBagConstraints.WEST
    presetNameGc.insets = new Insets(3, 4, 3, 4)
    form.add(appendPresetField, presetNameGc)
    GridBagConstraints minifyGc = new GridBagConstraints()
    minifyGc.gridx = 1; minifyGc.gridy = 10; minifyGc.anchor = GridBagConstraints.WEST
    minifyGc.insets = new Insets(3, 4, 3, 4)
    form.add(minifyPngField, minifyGc)
    GridBagConstraints safetyGc = new GridBagConstraints()
    safetyGc.gridx = 1; safetyGc.gridy = 11; safetyGc.anchor = GridBagConstraints.WEST
    safetyGc.insets = new Insets(3, 4, 8, 4)
    form.add(safetyLabel, safetyGc)

    Closure updateSaveControls = {
        boolean sequence = namingField.selectedItem == 'Numbered sequence'
        sequenceField.enabled = sequence
        startField.enabled = sequence
        digitsField.enabled = sequence
        resizeValueField.enabled = resizeField.selectedItem != 'Original size'
        qualityField.enabled = formatField.selectedItem == 'JPEG'
        minifyPngField.enabled = formatField.selectedItem == 'PNG'
    }
    namingField.addActionListener { updateSaveControls() }
    resizeField.addActionListener { updateSaveControls() }
    formatField.addActionListener { updateSaveControls() }
    updateSaveControls()

    browseButton.addActionListener {
        JFileChooser chooser = new JFileChooser(folderField.text.trim() ? new File(folderField.text.trim()) : defaultDir)
        SwingUtilities.updateComponentTreeUI(chooser)
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = 'Choose Scale output folder'
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            folderField.text = chooser.selectedFile.absolutePath
        }
    }

    JPanel dialogPanel = new JPanel(new BorderLayout(0, 8))
    dialogPanel.add(new JLabel('<html><b>Save every image in this batch</b><br>Choose the output once; FijiCal handles the sequence and records what it did.</html>'), BorderLayout.NORTH)
    dialogPanel.add(form, BorderLayout.CENTER)
    int choice = JOptionPane.showConfirmDialog(frame, dialogPanel, 'Save All', JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    if (choice != JOptionPane.OK_OPTION) return

    File outDir = new File(folderField.text.trim())
    if ((!outDir.exists() && !outDir.mkdirs()) || !outDir.isDirectory()) {
        JOptionPane.showMessageDialog(frame, "The output folder could not be created:\n${outDir.absolutePath}", 'Save All error', JOptionPane.ERROR_MESSAGE)
        return
    }

    String format = formatField.selectedItem as String
    String naming = namingField.selectedItem as String
    String resizeMode = resizeField.selectedItem as String
    String conflicts = conflictField.selectedItem as String
    int sequenceStart = (startField.value as Number).intValue()
    int sequenceDigits = (digitsField.value as Number).intValue()
    int resizeValue = (resizeValueField.value as Number).intValue()
    int jpegQuality = (qualityField.value as Number).intValue()
    boolean includeBars = includeBarsField.selected
    boolean appendPreset = appendPresetField.selected
    boolean minifyPng = minifyPngField.selected

    Closure cleanFragment = { String value ->
        (value ?: '').trim().replaceAll(/[\\\/:*?"<>|]/, '_').replaceAll(/[. ]+$/, '')
    }
    Closure cleanStem = { String value -> cleanFragment(value) ?: 'Image' }
    String sequenceName = cleanStem(sequenceField.text)
    String prefix = cleanFragment(prefixField.text)
    String suffix = cleanFragment(suffixField.text)
    String extension = format == 'TIFF' ? '.tif' : format == 'JPEG' ? '.jpg' : '.png'

    prefs.put('save.format', format)
    prefs.put('save.naming', naming)
    prefs.put('save.sequenceName', sequenceName)
    prefs.putInt('save.start', sequenceStart)
    prefs.putInt('save.digits', sequenceDigits)
    prefs.put('save.prefix', prefix)
    prefs.put('save.suffix', suffix)
    prefs.put('save.resizeMode', resizeMode)
    prefs.putInt('save.resizeValue', resizeValue)
    prefs.put('save.conflicts', conflicts)
    prefs.putInt('save.jpegQuality', jpegQuality)
    prefs.putBoolean('save.appendPreset', appendPreset)
    prefs.putBoolean('save.minifyPng', minifyPng)

    Closure nextAvailableFile = { File candidate ->
        if (!candidate.exists()) return candidate
        String filename = candidate.name
        String stem = filename.substring(0, filename.length() - extension.length())
        int counter = 2
        File available
        do {
            available = new File(candidate.parentFile, "${stem}_${counter}${extension}")
            counter++
        } while (available.exists())
        available
    }

    List<ImagePlus> exportImages = images.collect { it as ImagePlus }
    List<File> exportSources = sourceFiles.collect { it as File }
    Closure<Map> manifestRecordFor = { ImagePlus imp, File source, File output, String status,
                                        int outputWidth, int outputHeight, int outputBitDepth,
                                        String note=null, String error=null ->
        Map sourceDetails = sourceImageDetails.get(imp) ?: [width:imp.width,height:imp.height,bitDepth:imp.bitDepth]
        Calibration outputCalibration = imp.getCalibration().copy()
        outputCalibration.pixelWidth *= imp.width / (double) Math.max(1,outputWidth)
        outputCalibration.pixelHeight *= imp.height / (double) Math.max(1,outputHeight)
        Point workingPosition = scaleBarPositions.get(imp)
        Point outputPosition = workingPosition == null ? null : AwtGeometry.point(
            workingPosition.getX() * outputWidth / Math.max(1d,imp.width as double),
            workingPosition.getY() * outputHeight / Math.max(1d,imp.height as double))
        ProcessingManifest.imageRecord([
            status:status, source:source, output:output, format:format,
            sourceWidth:sourceDetails.width, sourceHeight:sourceDetails.height, sourceBitDepth:sourceDetails.bitDepth,
            outputWidth:outputWidth, outputHeight:outputHeight, outputBitDepth:outputBitDepth,
            preset:appliedPresetData.get(imp), presetName:appliedPresetNames.get(imp),
            calibration:outputCalibration, displayEdits:new ArrayList(imageEditHistory(imp)),
            nativeEdits:nativeImageEditHistory(imp).collect { Map edit -> new LinkedHashMap(edit) },
            includeScaleBar:includeBars && imp.overlay != null,
            customScaleBarPosition:outputPosition, note:note, error:error
        ])
    }
    int total = exportImages.size()
    Map progressUi = createBatchProgressDialog('Save All', "Exporting ${total} images to ${outDir.name}", total)
    AtomicBoolean cancelRequested = new AtomicBoolean(false)
    (progressUi.cancel as JButton).addActionListener {
        cancelRequested.set(true)
        (progressUi.cancel as JButton).enabled = false
        (progressUi.current as JLabel).text = 'Cancelling after the current image…'
    }
    (progressUi.dialog as JDialog).addWindowListener(new WindowAdapter() {
        @Override void windowClosing(WindowEvent event) { (progressUi.cancel as JButton).doClick() }
    })

    Thread worker = new Thread({
        int saved = 0
        int skipped = 0
        int failed = 0
        int protectedOriginals = 0
        int processedCount = 0
        List<String> ledger = []
        List<Map> manifestRecords = []
        int previousJpegQuality = FileSaver.getJpegQuality()
        FileSaver.setJpegQuality(jpegQuality)
        try {
            for (int idx = 0; idx < total; idx++) {
                if (cancelRequested.get()) break
                ImagePlus imp = exportImages[idx]
                File source = exportSources[idx]
                String originalStem = source.name.replaceFirst(/\.[^.]+$/, '')
                String numberedStem = "${sequenceName}_${String.format("%0${sequenceDigits}d", sequenceStart + idx)}"
                String outputStem = "${prefix}${naming == 'Numbered sequence' ? numberedStem : cleanStem(originalStem)}${suffix}"
                String appliedPresetName = appliedPresetNames.get(imp)
                if (appendPreset && appliedPresetName) outputStem += " - ${cleanStem(appliedPresetName)}"
                File target = new File(outDir, outputStem + extension)
                boolean protectedOriginal = false
                updateBatchProgress(progressUi, idx, total, "Saving ${source.name}")

                int oldWidth = imp.width
                int oldHeight = imp.height
                int newWidth = oldWidth
                int newHeight = oldHeight
                if (resizeMode == 'Percentage') {
                    newWidth = Math.max(1, Math.round(oldWidth * resizeValue / 100d) as int)
                    newHeight = Math.max(1, Math.round(oldHeight * resizeValue / 100d) as int)
                } else if (resizeMode == 'Target width') {
                    newWidth = resizeValue
                    newHeight = Math.max(1, Math.round(oldHeight * newWidth / (double) oldWidth) as int)
                } else if (resizeMode == 'Target height') {
                    newHeight = resizeValue
                    newWidth = Math.max(1, Math.round(oldWidth * newHeight / (double) oldHeight) as int)
                }
                int outputBitDepth = format == 'JPEG' || (format == 'PNG' && minifyPng) ? 8 : imp.bitDepth

                try {
                    boolean isOriginal = target.canonicalFile == source.canonicalFile
                    if (isOriginal) {
                        target = nextAvailableFile(target)
                        protectedOriginal = true
                        protectedOriginals++
                    } else if (target.exists()) {
                        if (conflicts == 'Skip existing') {
                            skipped++
                            ledger.add("SKIPPED  ${source.name} — ${target.name} already exists")
                            manifestRecords.add(manifestRecordFor(imp,source,target,'SKIPPED',
                                newWidth,newHeight,outputBitDepth,'Existing output preserved',null))
                            continue
                        }
                        if (conflicts == 'Automatically rename') target = nextAvailableFile(target)
                    }

                    boolean success = false
                    if (format == 'PNG' && !minifyPng && imp.bitDepth > 8 && imp.bitDepth != 24) {
                        BufferedImage nativeSource = ImageIO.read(source)
                        int sourceDepth = nativeChannelDepth(nativeSource)
                        if (nativeSource == null || sourceDepth <= 8) {
                            throw new IOException("native ${imp.bitDepth}-bit PNG data could not be preserved")
                        }
                        BufferedImage nativeCopy = applyNativeImageEdits(nativeSource, nativeImageEditHistory(imp))
                        if (includeBars && imp.overlay) drawScaleBarOnNativeImage(nativeCopy, imp.overlay)
                        nativeCopy = resizeNativeImage(nativeCopy, newWidth, newHeight)
                        success = ImageIO.write(nativeCopy, 'PNG', target)
                        if (!success) throw new IOException('the native-depth PNG encoder did not save a file')
                    } else {
                        ImagePlus copy = includeBars && imp.overlay ? imp.flatten() : imp.duplicate()
                        try {
                            if (newWidth != oldWidth || newHeight != oldHeight) {
                                ImageProcessor processor = copy.getProcessor()
                                processor.setInterpolationMethod(ImageProcessor.BILINEAR)
                                copy.setProcessor(copy.title, processor.resize(newWidth, newHeight))
                                copy.getCalibration().pixelWidth *= oldWidth / (double) newWidth
                                copy.getCalibration().pixelHeight *= oldHeight / (double) newHeight
                            }

                            FileSaver saver = new FileSaver(copy)
                            success = format == 'TIFF' ? saver.saveAsTiff(target.absolutePath) :
                                format == 'JPEG' ? saver.saveAsJpeg(target.absolutePath) : saver.saveAsPng(target.absolutePath)
                            if (!success) throw new IOException('the encoder did not save a file')
                        } finally {
                            copy.close()
                        }
                    }
                    saved++
                    String note = protectedOriginal ? ' (original protected by automatic rename)' : ''
                    ledger.add("SAVED    ${source.name} → ${target.name}${note}")
                    manifestRecords.add(manifestRecordFor(imp,source,target,'SAVED',newWidth,newHeight,
                        outputBitDepth,protectedOriginal ? 'Original protected by automatic rename' : null,null))
                } catch (Exception error) {
                    failed++
                    ledger.add("ERROR    ${source.name} — ${error.message ?: error.class.simpleName}")
                    manifestRecords.add(manifestRecordFor(imp,source,target,'ERROR',newWidth,newHeight,
                        outputBitDepth,null,error.message ?: error.class.simpleName))
                } finally {
                    processedCount = idx + 1
                    updateBatchProgress(progressUi, idx + 1, total, "Processed ${source.name}")
                }
            }
        } finally {
            FileSaver.setJpegQuality(previousJpegQuality)
        }

        int unprocessed = total - saved - skipped - failed
        boolean cancelled = cancelRequested.get()
        if (cancelled && unprocessed > 0) {
            ledger.add("CANCELLED  ${unprocessed} images were not exported")
            for (int idx = processedCount; idx < total; idx++) {
                ImagePlus imp = exportImages[idx]
                File source = exportSources[idx]
                int outputBitDepth = format == 'JPEG' || (format == 'PNG' && minifyPng) ? 8 : imp.bitDepth
                manifestRecords.add(manifestRecordFor(imp,source,null,'CANCELLED',imp.width,imp.height,
                    outputBitDepth,'Export cancelled before this image',null))
            }
        }

        SwingUtilities.invokeLater {
            (progressUi.dialog as JDialog).dispose()
            String headline = "Saved ${saved} of ${total} images to\n${outDir.absolutePath}"
            if (skipped) headline += "\nSkipped existing files: ${skipped}."
            if (failed) headline += "\nFailed: ${failed}."
            if (protectedOriginals) headline += "\nProtected originals by renaming: ${protectedOriginals}."
            if (cancelled) headline += "\nCancelled; not exported: ${unprocessed}."
            showBatchSummary(cancelled ? 'Save All cancelled' : (failed ? 'Save All completed with warnings' : 'Save All complete'),
                headline, ledger, cancelled || failed > 0)
        }
    }, 'FijiCal Save All')
    worker.daemon = true
    worker.start()
    (progressUi.dialog as JDialog).visible = true
}

presetSettingsButton.addActionListener { presetManagerItem.doClick() }
calibrateButton.addActionListener { calibrateItem.doClick() }

presetManagerItem.addActionListener {
    try {
        File managerScript = bundledFile('Scale_Bar_Presets.groovy')
        if (!managerScript.isFile()) throw new FileNotFoundException("Bundled preset manager was not found at ${managerScript.absolutePath}")
        Binding managerBinding = new Binding([fijicalOwner: frame, fijicalPresetFile: presetFile, fijicalPreferences: prefs])
        new GroovyShell(this.class.classLoader, managerBinding).evaluate(
            managerScript.readLines('UTF-8').drop(1).join('\n')
        )
        String managerSelectedName = managerBinding.hasVariable('fijicalSelectedPresetName') ?
            (managerBinding.getVariable('fijicalSelectedPresetName') ?: '').toString() : null
        reloadPresets(managerSelectedName)
    } catch (Exception error) {
        JOptionPane.showMessageDialog(frame,
            "The Preset Manager could not open.\n${error.message}",
            'Preset Manager error', JOptionPane.ERROR_MESSAGE)
    }
}
calibrateItem.addActionListener {
    Map styleBase = new LinkedHashMap(currentPreset() as Map)
    String originalPresetName = (styleBase.name ?: 'Calibration preset') as String
    def dialog = new JDialog(frame, 'Calibrate from Image — Ovalau', Dialog.ModalityType.APPLICATION_MODAL)
    dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    dialog.minimumSize = AwtGeometry.dimension(720,520)

    def calibrationCanvas = new CalibrationCanvas(BG)
    calibrationCanvas.preferredSize = AwtGeometry.dimension(760,680)
    def sourceLabel = new JLabel('No calibration image loaded')
    def pixelLabel = new JLabel('Draw a line across a known distance')
    def editPixelButton = new JButton('Edit…')
    editPixelButton.enabled = false
    def pixelDistancePanel = new JPanel(new BorderLayout(8,0))
    pixelDistancePanel.opaque = false
    pixelDistancePanel.add(pixelLabel,BorderLayout.CENTER)
    pixelDistancePanel.add(editPixelButton,BorderLayout.EAST)
    def resultLabel = new JLabel('— pixels/unit    |    — unit/pixel')
    def barPreviewLabel = new JLabel('Current bar width: — pixels')
    [sourceLabel,pixelLabel,resultLabel,barPreviewLabel].each { it.foreground = MUTED }

    def nameField = new JTextField(originalPresetName + ' calibration',22)
    def knownField = new JSpinner(new SpinnerNumberModel(prefs.getDouble('calibration.lastKnownDistance',1d),0.000001d,1000000000d,1d))
    String inheritedUnit = ((styleBase.calibrationUnit ?: styleBase.scaleUnit ?: 'µm') as String).trim()
    // Units belong to presets, not to the application session. Remembering the
    // last typed unit caused one millimetre calibration to contaminate every
    // subsequently created preset.
    String initialUnit = inheritedUnit
    double inheritedBarWidth = ((styleBase.width ?: 10d) as Number).doubleValue()
    String inheritedScaleBarUnit = ((styleBase.scaleUnit ?: inheritedUnit) as String).trim()
    def calibrationUnit = new JTextField(initialUnit,12)
    def scaleBarUnit = new JTextField(inheritedScaleBarUnit,12)
    def calibrationBarWidth = new JSpinner(new SpinnerNumberModel(inheritedBarWidth,0.000001d,1000000000d,1d))
    def saveMode = new JComboBox(['Save as new preset','Update selected preset'] as String[])
    def chooseButton = new JButton('Choose calibration image…')
    def fitCalibrationButton = new JButton('Fit')
    def clearLineButton = new JButton('Clear line')
    def saveCalibrationButton = new JButton('Save calibration preset')
    def cancelCalibrationButton = new JButton('Cancel')
    saveCalibrationButton.enabled = false
    File calibrationSource = null
    ImagePlus ownedCalibrationImage = null
    ImagePlus calibrationImage = null
    String savedPresetName = null
    Double manualPixels = null

    Closure updateCalibration = null
    updateCalibration = {
        double drawnPixels = calibrationCanvas.measuredPixels()
        double pixels = manualPixels != null ? manualPixels : drawnPixels
        double known = 0d
        try {
            String typedKnown = ((knownField.editor as JSpinner.DefaultEditor).textField.text ?: '').replace(',','').trim()
            known = typedKnown ? Double.parseDouble(typedKnown) : 0d
        } catch (ignored) {}
        String unit = calibrationUnit.text.trim()
        String barUnit = scaleBarUnit.text.trim()
        double barWidth = (calibrationBarWidth.value as Number).doubleValue()
        pixelLabel.text = pixels > 0d ? (manualPixels != null ? String.format('Pixel distance: %.2f px (edited; line %.2f px)',pixels,drawnPixels) : String.format('Pixel distance: %.2f px',pixels)) : 'Draw a line across a known distance'
        editPixelButton.enabled = drawnPixels > 0.5d
        if (pixels > 0.5d && known > 0d) {
            double pixelsPerUnit=pixels/known, unitsPerPixel=known/pixels
            resultLabel.text=String.format('%.4f pixels/%s    |    %.6f %s/pixel',pixelsPerUnit,unit ?: 'unit',unitsPerPixel,unit ?: 'unit')
            try {
                double barInCalibrationUnits = PhysicalUnits.convert(barWidth,barUnit,unit)
                barPreviewLabel.text=String.format('Scale bar %.6g %s → %.1f pixels',barWidth,barUnit ?: 'unit',barInCalibrationUnits*pixelsPerUnit)
            } catch (IllegalArgumentException conversionError) {
                barPreviewLabel.text=conversionError.message
            }
        } else { resultLabel.text='— pixels/unit    |    — unit/pixel'; barPreviewLabel.text='Current bar width: — pixels' }
        saveCalibrationButton.enabled = calibrationImage != null && pixels > 0.5d && known > 0d && unit && nameField.text.trim()
    }
    calibrationCanvas.measurementChanged = { manualPixels=null; updateCalibration() }

    Closure loadCalibrationImage = { ImagePlus imp, File file, boolean owned ->
        if (ownedCalibrationImage && ownedCalibrationImage != imp) ownedCalibrationImage.close()
        ownedCalibrationImage = owned ? imp : null
        calibrationImage=imp; calibrationSource=file; manualPixels=null
        calibrationCanvas.setImage(imp?.bufferedImage)
        sourceLabel.text = file?.name ?: imp?.title ?: 'Calibration image'
        updateCalibration()
    }
    if (currentIndex >= 0 && currentIndex < images.size())
        loadCalibrationImage(images[currentIndex] as ImagePlus, sourceFiles[currentIndex] as File, false)

    chooseButton.addActionListener {
        def chooser=new JFileChooser(); SwingUtilities.updateComponentTreeUI(chooser); chooser.fileFilter=new FileNameExtensionFilter('Calibration images (TIFF, PNG, JPEG)','tif','tiff','png','jpg','jpeg')
        if (chooser.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION) {
            ImagePlus imp=IJ.openImage(chooser.selectedFile.absolutePath)
            if (imp) loadCalibrationImage(imp,chooser.selectedFile,true)
            else JOptionPane.showMessageDialog(dialog,'That image could not be opened.','Ovalau',JOptionPane.ERROR_MESSAGE)
        }
    }
    fitCalibrationButton.addActionListener { calibrationCanvas.fitImage() }
    clearLineButton.addActionListener { calibrationCanvas.clearLine() }
    editPixelButton.addActionListener {
        double drawnPixels=calibrationCanvas.measuredPixels()
        if (drawnPixels <= 0.5d) return
        double startingPixels=manualPixels != null ? manualPixels : drawnPixels
        def pixelEditor=new JSpinner(new SpinnerNumberModel(startingPixels,0.01d,1000000000d,0.01d))
        def editorField=(pixelEditor.editor as JSpinner.DefaultEditor).textField
        editorField.columns=14; editorField.selectAll()
        def prompt=new JPanel(new BorderLayout(0,8))
        prompt.add(new JLabel(String.format('Drawn line: %.2f pixels',drawnPixels)),BorderLayout.NORTH)
        prompt.add(pixelEditor,BorderLayout.CENTER)
        Object[] pixelActions=['Save','Cancel'] as Object[]
        int answer=JOptionPane.showOptionDialog(dialog,prompt,'Edit pixel distance',JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,pixelActions,pixelActions[0])
        if (answer==0) {
            try {
                pixelEditor.commitEdit()
                double edited=(pixelEditor.value as Number).doubleValue()
                if (edited <= 0d) throw new IllegalArgumentException()
                manualPixels=edited
                updateCalibration()
            } catch (ignored) {
                JOptionPane.showMessageDialog(dialog,'Pixel distance must be a valid number greater than zero.','Check pixel distance',JOptionPane.WARNING_MESSAGE)
            }
        }
    }
    knownField.addChangeListener { updateCalibration() }
    calibrationBarWidth.addChangeListener { updateCalibration() }
    def knownEditorField = (knownField.editor as JSpinner.DefaultEditor).textField
    knownEditorField.document.addDocumentListener([insertUpdate:{updateCalibration()},removeUpdate:{updateCalibration()},changedUpdate:{updateCalibration()}] as javax.swing.event.DocumentListener)
    calibrationUnit.document.addDocumentListener([insertUpdate:{updateCalibration()},removeUpdate:{updateCalibration()},changedUpdate:{updateCalibration()}] as javax.swing.event.DocumentListener)
    scaleBarUnit.document.addDocumentListener([insertUpdate:{updateCalibration()},removeUpdate:{updateCalibration()},changedUpdate:{updateCalibration()}] as javax.swing.event.DocumentListener)
    nameField.document.addDocumentListener([insertUpdate:{updateCalibration()},removeUpdate:{updateCalibration()},changedUpdate:{updateCalibration()}] as javax.swing.event.DocumentListener)
    saveMode.addActionListener {
        boolean updating=saveMode.selectedIndex==1
        nameField.enabled=!updating
        if (updating) nameField.text=originalPresetName
        updateCalibration()
    }
    cancelCalibrationButton.addActionListener { dialog.dispose() }
    saveCalibrationButton.addActionListener {
        try { knownField.commitEdit() } catch (ignored) {
            JOptionPane.showMessageDialog(dialog,'Known distance must be a valid number greater than zero.','Check calibration',JOptionPane.WARNING_MESSAGE)
            return
        }
        double pixels=manualPixels != null ? manualPixels : calibrationCanvas.measuredPixels(), known=(knownField.value as Number).doubleValue()
        String unit=calibrationUnit.text.trim(), barUnit=scaleBarUnit.text.trim(), wantedName=nameField.text.trim()
        if (pixels <= 0.5d || known <= 0d || !unit || !barUnit || !wantedName) {
            JOptionPane.showMessageDialog(dialog,'Draw a measurable line and enter a preset name, known distance, calibration unit, and scale-bar unit.','Incomplete calibration',JOptionPane.WARNING_MESSAGE)
            return
        }
        double unitsPerPixel=known/pixels
        double barWidth=(calibrationBarWidth.value as Number).doubleValue()
        double widthInCalibrationUnits
        try { widthInCalibrationUnits=PhysicalUnits.convert(barWidth,barUnit,unit) }
        catch (IllegalArgumentException conversionError) {
            JOptionPane.showMessageDialog(dialog,conversionError.message,'Check units',JOptionPane.WARNING_MESSAGE)
            return
        }
        double previewPixels=widthInCalibrationUnits/unitsPerPixel
        boolean suspicious = unitsPerPixel > 1000d || unitsPerPixel < 0.000000001d || previewPixels < 1d || previewPixels > calibrationImage.width * 2d
        if (suspicious) {
            String warning=String.format("The calibration gives %.6g %s/pixel.\n\nThe %.6g %s scale bar would be %.2f pixels wide on this %d-pixel image.\nAdjust SCALE BAR WIDTH or SCALE BAR UNIT if that is not intended. Save anyway?",unitsPerPixel,unit,barWidth,barUnit,previewPixels,calibrationImage.width)
            int answer=JOptionPane.showConfirmDialog(dialog,warning,'Check calibration',JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)
            if (answer!=JOptionPane.YES_OPTION) return
        }
        int targetIndex = saveMode.selectedIndex==1 ? presetCombo.selectedIndex : presets.findIndexOf { (it.name ?: '') == wantedName }
        if (saveMode.selectedIndex==0 && targetIndex>=0) {
            int answer=JOptionPane.showConfirmDialog(dialog,"A preset named '${wantedName}' already exists. Replace it?",'Replace preset?',JOptionPane.YES_NO_OPTION)
            if (answer!=JOptionPane.YES_OPTION) return
        }
        Map calibrated=new LinkedHashMap(styleBase)
        calibrated.name = saveMode.selectedIndex==1 ? originalPresetName : wantedName
        calibrated.pixelDistance=pixels; calibrated.knownDistance=known; calibrated.calibrationUnit=unit; calibrated.scaleUnit=barUnit; calibrated.width=barWidth
        calibrated.calibrationSource=calibrationSource?.name ?: calibrationImage?.title ?: 'Calibration image'
        calibrated.calibratedAt=java.time.Instant.now().toString()
        if (targetIndex>=0) presets[targetIndex]=calibrated else presets.add(calibrated)
        writePresetFile(presets); prefs.putDouble('calibration.lastKnownDistance',known); prefs.flush()
        savedPresetName=calibrated.name as String
        dialog.dispose()
    }

    def toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT,8,4)); toolbar.background=PANEL
    [chooseButton,fitCalibrationButton,clearLineButton].each { toolbar.add(it) }
    def canvasPanel=new JPanel(new BorderLayout()); canvasPanel.background=BG
    def instruction=new JLabel('  Left-drag: draw line  •  Shift-drag: lock horizontal/vertical  •  Drag endpoint: adjust  •  Wheel: zoom  •  Right/middle-drag: pan')
    instruction.foreground=MUTED; instruction.border=new EmptyBorder(6,6,6,6)
    canvasPanel.add(toolbar,BorderLayout.NORTH); canvasPanel.add(calibrationCanvas,BorderLayout.CENTER); canvasPanel.add(instruction,BorderLayout.SOUTH)

    def details=new JPanel(); details.layout=new BoxLayout(details,BoxLayout.Y_AXIS); details.background=PANEL; details.border=new EmptyBorder(22,22,22,22); details.preferredSize=AwtGeometry.dimension(360,680)
    def calibrationHeading=new JLabel('Calibrate from Image'); calibrationHeading.font=calibrationHeading.font.deriveFont(Font.BOLD,22f); calibrationHeading.foreground=TEXT
    def subheading=new JLabel('One line. One known distance. Done.'); subheading.foreground=MUTED
    details.add(calibrationHeading); details.add(Box.createVerticalStrut(4)); details.add(subheading); details.add(Box.createVerticalStrut(24))
    def addCalibrationField={ String label, JComponent component -> def l=new JLabel(label); l.foreground=TEXT; l.font=l.font.deriveFont(Font.BOLD); l.alignmentX=Component.LEFT_ALIGNMENT; component.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,34); component.alignmentX=Component.LEFT_ALIGNMENT; details.add(l); details.add(Box.createVerticalStrut(5)); details.add(component); details.add(Box.createVerticalStrut(14)) }
    addCalibrationField('CALIBRATION IMAGE',sourceLabel); addCalibrationField('SAVE MODE',saveMode); addCalibrationField('PRESET NAME',nameField); addCalibrationField('KNOWN DISTANCE',knownField); addCalibrationField('CALIBRATION UNIT',calibrationUnit); addCalibrationField('SCALE BAR WIDTH',calibrationBarWidth); addCalibrationField('SCALE BAR UNIT',scaleBarUnit)
    addCalibrationField('PIXEL DISTANCE',pixelDistancePanel)
    details.add(Box.createVerticalStrut(10)); [resultLabel,barPreviewLabel].each { it.alignmentX=Component.LEFT_ALIGNMENT; details.add(it); details.add(Box.createVerticalStrut(10)) }
    def inherited=new JLabel("Scale-bar style inherited from '${originalPresetName}'."); inherited.foreground=ACCENT; inherited.alignmentX=Component.LEFT_ALIGNMENT; details.add(inherited); details.add(Box.createVerticalGlue())
    def actions=new JPanel(new GridLayout(1,2,8,0)); actions.background=PANEL; actions.maximumSize=AwtGeometry.dimension(Integer.MAX_VALUE,40); actions.add(cancelCalibrationButton); actions.add(saveCalibrationButton); actions.alignmentX=Component.LEFT_ALIGNMENT; details.add(actions)

    def content=new JPanel(new BorderLayout()); content.background=BG; content.add(canvasPanel,BorderLayout.CENTER); content.add(details,BorderLayout.EAST)
    def contentScroll = new JScrollPane(content)
    contentScroll.border = null
    contentScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    contentScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    dialog.contentPane=contentScroll
    dialog.rootPane.defaultButton = saveCalibrationButton
    dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), 'cancelCalibration')
    dialog.rootPane.actionMap.put('cancelCalibration', new AbstractAction() {
        @Override void actionPerformed(ActionEvent event) { cancelCalibrationButton.doClick() }
    })
    dialog.pack()
    WindowGeometry.fitToScreen(dialog, 1120, 760, 720, 520)
    dialog.visible=true
    if (ownedCalibrationImage) ownedCalibrationImage.close()
    if (savedPresetName) {
        reloadPresets(savedPresetName)
        int savedIndex=presets.findIndexOf { (it.name ?: '') == savedPresetName }
        if (savedIndex>=0) presetCombo.selectedIndex=savedIndex
    }
}

showPreset(); render()
SwingUtilities.invokeLater {
    try { IJ.getInstance()?.visible=false } catch(ignored) {}
    frame.pack()
    WindowGeometry.fitToScreen(frame,
        AwtGeometry.integer(Math.min(1500, usableScreen.width)),
        AwtGeometry.integer(Math.min(900, usableScreen.height)),
        980, 640)
    frame.visible=true
}
