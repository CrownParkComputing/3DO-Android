package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.Manifest;
import android.content.pm.PackageManager;

public class SplashActivity extends Activity {

    private static final long SPLASH_DURATION_MS = 2000L;
    private static final int REQUEST_MANAGE_STORAGE = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Ensure fullscreen mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkStoragePermissionAndProceed();
            }
        }, SPLASH_DURATION_MS);
    }

    private void checkStoragePermissionAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): Need MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                } catch (Exception e) {
                    // Fallback for devices that don't support the specific intent
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                }
                return;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-10 (API 23-29): Need runtime READ_EXTERNAL_STORAGE
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE_PERMISSION);
                return;
            }
        }

        // Permission granted, proceed with app launch
        proceedToApp();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            // Check again after user returns from settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                proceedToApp();
            } else {
                // User didn't grant permission, try again or proceed anyway
                // (FileBrowserActivity will show empty directories)
                proceedToApp();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            // Proceed regardless - if denied, file browser will show empty
            proceedToApp();
        }
    }

    private void proceedToApp() {
        // Check if setup has been completed AND paths are still valid
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean setupCompleted = prefs.getBoolean("setup_completed", false);
        String biosPath = prefs.getString(SettingsActivity.KEY_BIOS_PATH, "");
        String libraryPath = prefs.getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
        
        // Check if BIOS file exists
        boolean biosValid = false;
        if (biosPath != null && !biosPath.isEmpty()) {
            java.io.File biosFile = new java.io.File(biosPath);
            biosValid = biosFile.exists();
        }
        
        // Check if library folder exists
        boolean libraryValid = false;
        if (libraryPath != null && !libraryPath.isEmpty()) {
            java.io.File libraryDir = new java.io.File(libraryPath);
            libraryValid = libraryDir.exists() && libraryDir.isDirectory();
        }
        
        // Show setup wizard if not completed OR if paths are invalid
        if (!setupCompleted || !biosValid || !libraryValid) {
            // Need to run setup wizard
            SetupWizardActivity.start(SplashActivity.this);
        } else {
            // Setup complete - go to game library
            Intent intent = new Intent(SplashActivity.this, GameLibraryActivity.class);
            startActivity(intent);
        }
        finish();
    }
}