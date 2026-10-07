# FijiCal Lite — Mamanuca

**Microscopy calibration and scale bars without menu archaeology.**

FijiCal Lite is a portable Windows application for calibrating microscopy images, applying reusable objective presets, and exporting images with scale bars. Mamanuca (version 0.9) brings image browsing, calibration, basic editing, and batch export into one workspace. An optional Lightroom Classic plug-in sends your developed images directly to FijiCal.

## Features

- Browse TIFF, PNG, and JPEG images in Loupe and Grid views.
- Measure a known distance in a calibration image and save a reusable preset.
- Use separate calibration and scale-bar units, with conversion between recognised metric units.
- Apply scale bars to selected images or an entire batch; drag editable bars into position.
- Crop, rotate, flip, adjust brightness and contrast, and balance RGB channels, with per-image undo.
- Export individual copies or batches with naming, resizing, format, and filename-conflict options.
- Organise presets into collections and import or export JSON preset packs.

## Start the portable app

**[Download the v0.9 Mamanuca portable ZIP](https://github.com/nighthunter226-but-real/FijiCal-Lite/releases/download/v0.9/FijiCal.Lite.0.9.-.Mamanuca.with.Lightroom.Bridge.zip)** · [Release notes and checksum](https://github.com/nighthunter226-but-real/FijiCal-Lite/releases/tag/v0.9)

1. Extract the complete portable ZIP into a writable folder.
2. Open the extracted **FijiCal Lite** folder and double-click **FijiCal Lite.exe**.
3. Keep the executable together with its `app` and `runtime` folders. Java, Groovy, and ImageJ are bundled; no separate Fiji or Java installation is needed.

The GitHub source download is not a runnable portable build. Use a separately supplied portable ZIP, or build one using the instructions below.

Presets and settings live beside the executable in `FijiCal Lite Presets.json` and `FijiCal Lite Settings.properties`. Back up those files to preserve your setup.

## Your first scale bar

1. Choose **File → Open Images…** (`Ctrl+O`), or drag image files into the workspace.
2. Choose a calibration preset matching the objective and imaging setup used for the image.
3. Set the scale-bar width, unit, colour, and position as needed.
4. Click **Apply to Selected** or **Apply to All**.
5. With **Editable overlay** enabled, use the Pointer tool to drag a bar into place. Applying the preset again resets its position.
6. Choose **File → Save As…** for the active image, or **Save All…** for the batch.

Right-click a carousel or Grid thumbnail for **Open in Loupe**, **Close image**, or **Close selected**. Closing removes an image from the workspace, not from disk.

## Create a calibration preset

Use a stage micrometer or another image containing a known distance, captured with the same objective, camera, adapter, and image sizing as the images you will calibrate. Magnification alone does not establish the pixel scale.

1. Open **Calibration → Calibrate from Image…**.
2. Choose your calibration image, or use the active image.
3. Draw a line across the known distance. Hold `Shift` to constrain it horizontally or vertically; adjust the endpoints if needed.
4. Enter the **Known Distance** and **Calibration Unit**. For example, a 0.1 mm distance uses `0.1` and `mm`.
5. Enter the **Scale Bar Width** and **Scale Bar Unit** separately. A calibration in `mm` can display a bar in `µm`.
6. Name the preset, then save it as a new preset or update the selected preset.

Use **Calibration → Preset Manager…** to organise collections, record equipment details and notes, and import or export preset packs. Calibrate each imaging setup rather than assuming another person's preset matches yours. Resizing in Lightroom before importing requires calibration for that rendered size.

## Save and export

**Save As** defaults to the source format. **Save All** does the same when the batch uses one format; mixed batches default to the last selected output format. You can select PNG, TIFF, or JPEG explicitly.

Save All defaults to a `Scale` folder beside the first source image. Choose original names or a numbered sequence, add prefixes or suffixes, optionally append the applied preset name, and decide whether existing outputs are renamed, skipped, or overwritten.

Enable **Include scale bars** to bake the bars into exported copies. Leave **Minify PNG** off when preserving supported high-bit-depth PNG data matters; enabling it intentionally produces an 8-bit-per-channel copy. JPEG quality is adjustable. Image edits are applied to exported copies; the save workflow protects original source files from being overwritten.

## Send images from Lightroom Classic

The plug-in is for **Lightroom Classic on Windows**.

1. Open **File → Plug-in Manager** in Lightroom Classic.
2. Click **Add** and select the **Export to FijiCal.lrplugin** folder supplied with the portable app. In this source repository it is under `lightroom/`.
3. Select your images and open Lightroom's Export dialog.
4. Choose **FijiCal** in the **Export To** menu.
5. Use **Application → Choose…** to locate your extracted **FijiCal Lite.exe**. Update this path if you move the portable app.
6. Choose an export folder and your usual JPEG, TIFF, or PNG settings, then export.

The plug-in copies Lightroom's finished renditions into the chosen folder and opens them in Mamanuca. Existing files get unique names rather than being overwritten. Lightroom retains its rendering controls, including sizing and colour settings. Very large batches may be split into multiple Mamanuca launches because of Windows command-line limits.

After a plug-in update, use **Reload Plug-in** in Plug-in Manager. If images do not open, confirm the application path points to the current portable executable and try **File → Open Images…** in Mamanuca.

## Keyboard shortcuts

| Shortcut | Action |
| --- | --- |
| `Ctrl+O` | Open images |
| `Ctrl+Shift+S` | Save the active image as a copy |
| `Ctrl+W` | Close the current batch |
| `Ctrl+Z` | Undo the last image edit |
| `F1` | Open instructions |
| `Escape` | Cancel crop mode or close the calibration window |

## Build a portable release

The source tree contains `app/FijiCal_Lite_Mamanuca.groovy`, the Java launcher, and the Lightroom plug-in. The build script uses an existing Windows portable as the baseline for its native executable, bundled runtime, Groovy 4.0.28, and ImageJ 1.54p. A JDK supporting Java 17 or later is needed to compile the launcher; its output must be compatible with the baseline runtime.

```powershell
.\build-portable.ps1 -BasePortable 'C:\Tools\FijiCal Lite' -JdkRoot 'C:\Tools\jdk-21'
```

Each build creates a fresh folder under `build/`, compiles and tests the launcher, compiles the Mamanuca script, and produces a portable ZIP with a SHA-256 checksum. Generated binaries and personal portable settings are excluded from Git.

To launch images from another application, pass their quoted paths:

```text
"FijiCal Lite.exe" --open "image1.tif" "image2.png"
```

For a manual large-batch handoff, `--import-list "batch.txt"` accepts a UTF-8 file with one path per line. The launcher deletes that list after reading it. The Lightroom plug-in passes image paths directly.

## Troubleshooting and contributions

If a scale bar looks wrong, check the preset's measured distance, calibration unit, scale-bar unit, and image dimensions. If an error occurs, include the steps, output format, and a suitable sample image in a [GitHub issue](https://github.com/nighthunter226-but-real/FijiCal-Lite/issues). Startup errors show the path to their diagnostic log.

Changes to `main` go through pull requests. Force pushes and deletion of `main` are blocked. Repository automation must obtain the owner's approval before each Git push.
