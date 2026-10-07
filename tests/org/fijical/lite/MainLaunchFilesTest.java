// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
// Distributed without warranty; see LICENSE and COPYRIGHT.md.
package org.fijical.lite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MainLaunchFilesTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("fijical-launch-test-");
        Path first = Files.writeString(directory.resolve("first image.TIF"), "test");
        Path second = Files.writeString(directory.resolve("second.png"), "test");
        Path ignored = Files.writeString(directory.resolve("notes.txt"), "test");
        Path missing = directory.resolve("missing.jpg");
        Path manifest = directory.resolve("batch.txt");
        Files.write(manifest, List.of(first.toString(), second.toString(), ignored.toString(), missing.toString()), StandardCharsets.UTF_8);

        List<File> fromManifest = Main.parseLaunchFiles(new String[] { "--import-list", manifest.toString() });
        require(fromManifest.size() == 2, "manifest should retain two supported existing images");
        require(fromManifest.get(0).toPath().equals(first.toAbsolutePath()), "manifest order should be preserved");
        require(!Files.exists(manifest), "manifest should be deleted after reading");

        List<File> direct = Main.parseLaunchFiles(new String[] { "--open", second.toString(), ignored.toString() });
        require(direct.size() == 1 && direct.get(0).toPath().equals(second.toAbsolutePath()), "direct launch should filter unsupported files");

        boolean rejected = false;
        try { Main.parseLaunchFiles(new String[] { "--wat" }); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "unknown options should be rejected");

        Files.deleteIfExists(first);
        Files.deleteIfExists(second);
        Files.deleteIfExists(ignored);
        Files.deleteIfExists(directory);
        System.out.println("MainLaunchFilesTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
