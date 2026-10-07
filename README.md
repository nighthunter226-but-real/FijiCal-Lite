# FijiCal Lite 0.9 — Mamanuca

This bridge adds a **FijiCal** destination to Lightroom Classic's Export dialog and teaches FijiCal Lite 0.9 - Mamanuca to accept a batch at launch.

## Lightroom installation

1. Open **File → Plug-in Manager** in Lightroom Classic.
2. Choose **Add** and select `Export to FijiCal.lrplugin` from the `lightroom` folder.
3. Export images and choose **FijiCal** in the Export To menu.
4. If needed, use **Choose…** once to locate `FijiCal Lite.exe`.

Lightroom retains its normal file settings. The plug-in copies the finished renditions into the chosen FijiCal export folder, then opens them in Mamanuca through Lightroom's native application launcher. Existing exports are never overwritten; a numbered filename is chosen instead.

## Launch contract

Mamanuca accepts image paths directly:

```text
FijiCal Lite.exe --open image1.tif image2.png
```

For large batches it also accepts a UTF-8 file containing one image path per line:

```text
FijiCal Lite.exe --import-list batch.txt
```

For manual manifest launches, the file is deleted after the launcher reads it. The Lightroom plug-in passes image paths directly. Missing files and unsupported extensions are ignored. Lightroom may split very large batches into multiple application launches to respect Windows command-line limits.

## Save formats

Save As defaults to the source image's format. Save All defaults to the source format when every image uses the same format; mixed batches use the last chosen output format. Ordinary 24-bit RGB images can be exported as PNG without triggering the high-bit-depth preservation check.

## Build a portable release

The source tree contains the Mamanuca Groovy app, Java launcher, and Lightroom Classic plug-in. The existing Windows portable provides the native launcher, Java runtime, Groovy and ImageJ dependencies. A JDK supporting Java 17 or later is required to compile the launcher.

```powershell
.\build-portable.ps1 -BasePortable 'H:\FijiCal Lite 0.9 - Mamanuca\FijiCal Lite' -JdkRoot 'C:\Program Files\WatchFaceStudio\tools\window\jdk'
```

Each build creates a new folder beneath `build`, runs launcher tests, and produces a portable ZIP and SHA-256 checksum. Generated binaries and personal portable settings are excluded from Git.

The release was manually checked through Lightroom export, Mamanuca launch, scale-bar PNG export, and JPEG saving.
