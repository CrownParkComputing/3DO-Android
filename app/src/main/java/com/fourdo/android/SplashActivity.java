package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

public class SplashActivity extends Activity {

    private static final long SPLASH_DURATION_MS = 2000L;

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
        }, SPLASH_DURATION_MS);
    }
}