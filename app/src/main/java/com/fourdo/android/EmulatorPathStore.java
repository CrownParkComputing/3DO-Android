package com.fourdo.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

public final class EmulatorPathStore {

    private EmulatorPathStore() {
    }

    public static String getSavedBiosPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(MainActivity.KEY_BIOS_PATH, "");
    }

    public static void saveBiosPath(Context context, String biosPath) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.KEY_BIOS_PATH, biosPath).apply();
    }

    public static String getSavedLastGamePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(MainActivity.KEY_LAST_GAME_PATH, "");
    }

    public static String getSavedLibraryFolder(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(MainActivity.KEY_LIBRARY_FOLDER, "");
    }

    public static void saveLastGamePath(Context context, String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.KEY_LAST_GAME_PATH, gamePath).apply();
    }

    public static void clearLastGamePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(MainActivity.KEY_LAST_GAME_PATH).apply();
    }

    public static boolean isValidFilePath(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    public static boolean isSupportedCdPath(String path) {
        if (!isValidFilePath(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".cue") || lower.endsWith(".iso") || lower.endsWith(".chd") || lower.endsWith(".bin");
    }

    public static boolean isValidDirectoryPath(String path) {
        return path != null && !path.isEmpty() && new File(path).isDirectory();
    }
}
