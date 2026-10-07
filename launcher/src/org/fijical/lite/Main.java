// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 nighthunter226-but-real and FijiCal Lite contributors.
// Distributed without warranty; see LICENSE and COPYRIGHT.md.
package org.fijical.lite;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import javax.swing.JOptionPane;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native entry point for FijiCal Lite 0.9 - Mamanuca. */
public final class Main {
    private static final String PRODUCT = "FijiCal Lite 0.9 - Mamanuca";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("tif", "tiff", "png", "jpg", "jpeg");
    private static Path applicationRoot;
    private static Path logDirectory;

    private Main() {}

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> reportFatalError(error));
        try {
            Path appDirectory = applicationDirectory();
            applicationRoot = applicationRoot(appDirectory);
            boolean portable = Boolean.getBoolean("fijical.portable");
            Path dataDirectory = dataDirectory(portable);
            logDirectory = logDirectory(portable);
            Path script = appDirectory.resolve("FijiCal_Lite_Mamanuca.groovy");
            if (!Files.isRegularFile(script)) {
                throw new IllegalStateException("The bundled application script was not found: " + script);
            }

            List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            String source = String.join(System.lineSeparator(), lines.subList(1, lines.size()));
            Binding binding = new Binding();
            binding.setVariable("fijicalAppDir", appDirectory.toFile());
            binding.setVariable("fijicalDataDir", dataDirectory.toFile());
            binding.setVariable("fijicalPortable", portable);
            binding.setVariable("fijicalBuild", "0.9");
            binding.setVariable("fijicalCodename", "Mamanuca");
            binding.setVariable("fijicalLaunchFiles", parseLaunchFiles(args));
            new GroovyShell(Main.class.getClassLoader(), binding).evaluate(source, script.getFileName().toString());
        } catch (Throwable error) {
            reportFatalError(error);
        }
    }

    static List<File> parseLaunchFiles(String[] args) throws IOException {
        List<String> candidates = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--open".equals(argument)) continue;
            if ("--import-list".equals(argument)) {
                if (++index >= args.length) throw new IllegalArgumentException("--import-list requires a file path.");
                Path listFile = Paths.get(args[index]);
                try {
                    candidates.addAll(Files.readAllLines(listFile, StandardCharsets.UTF_8));
                } finally {
                    Files.deleteIfExists(listFile);
                }
                continue;
            }
            if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown FijiCal launch option: " + argument);
            }
            candidates.add(argument);
        }

        List<File> files = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            Path path = Paths.get(candidate.trim()).toAbsolutePath().normalize();
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(path) && IMAGE_EXTENSIONS.contains(extension)) files.add(path.toFile());
        }
        return files;
    }

    private static Path applicationDirectory() throws URISyntaxException {
        Path location = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return Files.isDirectory(location) ? location : location.getParent();
    }

    private static Path applicationRoot(Path appDirectory) {
        Path parent = appDirectory.getParent();
        if (appDirectory.getFileName() != null && "app".equalsIgnoreCase(appDirectory.getFileName().toString())) {
            if (parent != null && parent.getFileName() != null && "lib".equalsIgnoreCase(parent.getFileName().toString())) {
                return parent.getParent() == null ? parent : parent.getParent();
            }
            return parent == null ? appDirectory : parent;
        }
        return appDirectory;
    }

    private static Path dataDirectory(boolean portable) {
        if (portable && applicationRoot != null) return applicationRoot;
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isBlank()) return Paths.get(xdgConfig, "FijiCal Lite");
        return Paths.get(System.getProperty("user.home"), ".config", "FijiCal Lite");
    }

    private static Path logDirectory(boolean portable) {
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData == null || localAppData.isBlank()
                ? Paths.get(System.getProperty("java.io.tmpdir"), "FijiCal Lite", "logs")
                : Paths.get(localAppData, "FijiCal Lite", "logs");
        }
        if (portable && applicationRoot != null) return applicationRoot.resolve("logs");
        String xdgState = System.getenv("XDG_STATE_HOME");
        if (xdgState != null && !xdgState.isBlank()) return Paths.get(xdgState, "FijiCal Lite", "logs");
        return Paths.get(System.getProperty("user.home"), ".local", "state", "FijiCal Lite", "logs");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void reportFatalError(Throwable error) {
        Path log = writeCrashLog(error);
        String message = PRODUCT + " could not start.\n\n" +
            (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) +
            (log == null ? "" : "\n\nA diagnostic log was written to:\n" + log);
        try {
            JOptionPane.showMessageDialog(null, message, "FijiCal Lite startup error", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            error.printStackTrace();
        }
    }

    private static Path writeCrashLog(Throwable error) {
        try {
            Path destination = logDirectory != null ? logDirectory : Paths.get(System.getProperty("java.io.tmpdir"), "FijiCal Lite", "logs");
            Files.createDirectories(destination);
            Path log = destination.resolve("startup.log");
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            String entry = "[" + Instant.now() + "] " + PRODUCT + System.lineSeparator() + stack + System.lineSeparator();
            Files.writeString(log, entry, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return log;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
