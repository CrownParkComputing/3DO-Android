package com.fourdo.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

final class EmulatorPathStore {

    private EmulatorPathStore() {
    }

    static String getSavedBiosPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_BIOS_PATH, "");
    }

    static void saveBiosPath(Context context, String biosPath) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(SettingsActivity.KEY_BIOS_PATH, biosPath).apply();
    }

    static String getSavedLastGamePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_LAST_GAME_PATH, "");
    }

    static String getSavedLibraryFolder(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
    }

    static void saveLastGamePath(Context context, String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(SettingsActivity.KEY_LAST_GAME_PATH, gamePath).apply();
    }

    static boolean isValidFilePath(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    static boolean isSupportedCdPath(String path) {
        if (!isValidFilePath(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".cue") || lower.endsWith(".bin") || lower.endsWith(".iso") || lower.endsWith(".chd");
    }

    static boolean isValidDirectoryPath(String path) {
        return path != null && !path.isEmpty() && new File(path).isDirectory();
    }
}
