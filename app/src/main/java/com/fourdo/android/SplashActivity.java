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
                // Check if setup has been completed
                if (!SetupWizardActivity.isSetupCompleted(SplashActivity.this)) {
                    // First time user - show setup wizard
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