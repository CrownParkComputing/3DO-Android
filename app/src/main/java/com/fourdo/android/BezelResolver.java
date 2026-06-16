package com.fourdo.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BezelResolver {
    private static final int MAX_DEPTH = 7;
    private static final int COUNT_MAX_DEPTH = 10;
    private static final String PREFS_NAME = "bezel_matches";
    private static final String PREF_PREFIX = "game_key_";

    private BezelResolver() {
    }

    static File findBezelForGame(Context context, String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return null;
        }

        File gameFile = new File(gamePath);
        String gameKey = normalizeKey(baseName(gameFile.getName()), true);
        if (gameKey.isEmpty()) {
            return null;
        }

        File manualMatch = getManualBezelMatch(context, gameKey);
        if (manualMatch != null) {
            return manualMatch;
        }

        File exact = findInDirectory(gameFile.getParentFile(), gameKey, new HashSet<>(), 2);
        if (exact != null) {
            return exact;
        }

        String libraryPath = EmulatorPathStore.getSavedLibraryFolder(context);
        if (libraryPath != null && !libraryPath.isEmpty()) {
            File libraryRoot = new File(libraryPath);
            exact = findInCommonRoots(libraryRoot, gameKey);
            if (exact != null) {
                return exact;
            }
        }

        File appRoot = SafFileImporter.getManagedAppRoot(context);
        exact = findInCommonRoots(appRoot, gameKey);
        if (exact != null) {
            return exact;
        }

        File filesRoot = context.getFilesDir();
        return findInCommonRoots(filesRoot, gameKey);
    }

    static String describeBezel(Context context, String gamePath) {
        File bezel = findBezelForGame(context, gamePath);
        if (bezel != null) {
            return "Matched bezel: " + bezel.getName();
        }
        int count = countDownloadedBezels(context);
        return count > 0 ? "No matching bezel (" + count + " downloaded)" : "Default 3DO bezel";
    }

    static String describeBezelDiagnostic(Context context, String gamePath) {
        String gameName = displayGameName(gamePath);
        String gameKey = expectedKeyForGame(gamePath);
        File bezel = findBezelForGame(context, gamePath);
        if (bezel != null) {
            return "Matched bezel: " + bezel.getName() + " | key " + gameKey;
        }

        int count = countDownloadedBezels(context);
        if (count > 0) {
            return "No bezel match for " + gameName + " | key " + gameKey + " | " + count + " downloaded";
        }
        return "No downloaded bezels | key " + gameKey;
    }

    static int countDownloadedBezels(Context context) {
        Set<String> visited = new HashSet<>();
        int count = 0;
        File appRoot = SafFileImporter.getManagedAppRoot(context);
        count += countPngs(new File(appRoot, "bezels"), visited, COUNT_MAX_DEPTH);
        return count;
    }

    static String describeDownloadedBezels(Context context) {
        int count = countDownloadedBezels(context);
        return count == 1 ? "1 bezel downloaded" : count + " bezels downloaded";
    }

    static void saveManualBezelMatch(Context context, String gamePath, File bezelFile) {
        if (context == null || bezelFile == null || !bezelFile.isFile()) {
            return;
        }
        String gameKey = expectedKeyForGame(gamePath);
        if (gameKey.isEmpty()) {
            return;
        }
        prefs(context).edit()
                .putString(PREF_PREFIX + gameKey, bezelFile.getAbsolutePath())
                .apply();
    }

    static List<File> listDownloadedBezels(Context context) {
        List<File> bezels = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        File appRoot = SafFileImporter.getManagedAppRoot(context);
        collectPngs(new File(appRoot, "bezels"), visited, COUNT_MAX_DEPTH, bezels);
        bezels.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return bezels;
    }

    static String expectedKeyForGame(String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return "";
        }
        return normalizeKey(baseName(new File(gamePath).getName()), true);
    }

    private static File getManualBezelMatch(Context context, String gameKey) {
        if (context == null || gameKey == null || gameKey.isEmpty()) {
            return null;
        }
        String path = prefs(context).getString(PREF_PREFIX + gameKey, "");
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (file.isFile() && isPng(file)) {
            return file;
        }
        prefs(context).edit().remove(PREF_PREFIX + gameKey).apply();
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static File findInCommonRoots(File root, String gameKey) {
        if (root == null || !root.isDirectory()) {
            return null;
        }

        File match = findInDirectory(root, gameKey, new HashSet<>(), 1);
        if (match != null) {
            return match;
        }

        String[] candidates = {
                "bezels",
                "Bezels",
                "overlays",
                "Overlays",
                "GameBezels",
                "gamebezels",
                "The Bezel Project",
                "media",
                "Media",
                "3DO",
                "Panasonic 3DO"
        };
        for (String candidate : candidates) {
            match = findInDirectory(new File(root, candidate), gameKey, new HashSet<>(), MAX_DEPTH);
            if (match != null) {
                return match;
            }
        }

        return findInDirectory(root, gameKey, new HashSet<>(), 3);
    }

    private static File findInDirectory(File directory, String gameKey, Set<String> visited, int depthRemaining) {
        if (directory == null || depthRemaining < 0 || !directory.isDirectory()) {
            return null;
        }

        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Exception ignored) {
            path = directory.getAbsolutePath();
        }
        if (!visited.add(path)) {
            return null;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }

        File looseMatch = null;
        for (File file : files) {
            if (!file.isFile() || !isPng(file)) {
                continue;
            }
            String exactKey = normalizeKey(baseName(file.getName()), false);
            String looseKey = normalizeKey(baseName(file.getName()), true);
            if (exactKey.equals(gameKey) || looseKey.equals(gameKey)) {
                return file;
            }
            if (looseMatch == null && (looseKey.contains(gameKey) || gameKey.contains(looseKey))) {
                looseMatch = file;
            }
        }
        if (looseMatch != null) {
            return looseMatch;
        }

        if (depthRemaining == 0) {
            return null;
        }

        for (File file : files) {
            if (file.isDirectory() && shouldTraverse(file.getName())) {
                File match = findInDirectory(file, gameKey, visited, depthRemaining - 1);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static boolean shouldTraverse(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(".")
                && !lower.equals("cache")
                && !lower.equals("tmp")
                && !lower.equals("temp");
    }

    private static int countPngs(File directory, Set<String> visited, int depthRemaining) {
        if (directory == null || depthRemaining < 0 || !directory.isDirectory()) {
            return 0;
        }

        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Exception ignored) {
            path = directory.getAbsolutePath();
        }
        if (!visited.add(path)) {
            return 0;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }

        int count = 0;
        for (File file : files) {
            if (file.isFile() && isPng(file)) {
                count++;
            } else if (file.isDirectory() && shouldTraverse(file.getName())) {
                count += countPngs(file, visited, depthRemaining - 1);
            }
        }
        return count;
    }

    private static void collectPngs(File directory, Set<String> visited, int depthRemaining, List<File> out) {
        if (directory == null || depthRemaining < 0 || !directory.isDirectory()) {
            return;
        }

        String path;
        try {
            path = directory.getCanonicalPath();
        } catch (Exception ignored) {
            path = directory.getAbsolutePath();
        }
        if (!visited.add(path)) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile() && isPng(file)) {
                out.add(file);
            } else if (file.isDirectory() && shouldTraverse(file.getName())) {
                collectPngs(file, visited, depthRemaining - 1, out);
            }
        }
    }

    private static boolean isPng(File file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        String value = new File(name).getName();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String displayGameName(String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return "unknown game";
        }
        String value = baseName(new File(gamePath).getName()).trim();
        return value.isEmpty() ? "unknown game" : value;
    }

    private static String normalizeKey(String name, boolean dropQualifiers) {
        String value = name == null ? "" : name.replace('_', ' ').toLowerCase(Locale.ROOT);
        if (dropQualifiers) {
            value = value.replaceAll("\\([^)]*\\)", " ");
            value = value.replaceAll("\\[[^]]*\\]", " ");
            value = value.replaceAll("\\{[^}]*\\}", " ");
            value = value.replaceAll("(?i)\\bv\\d+(?:\\.\\d+)*\\b", " ");
        }

        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
