package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/**
 * Main launcher activity for the 4DO 3DO emulator.
 * This activity serves as the entry point and launches the emulator.
 */
public class MainActivity extends Activity {

    private Button startEmulatorButton;
    private Button settingsButton;

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startEmulatorButton = findViewById(R.id.start_button);
        settingsButton = findViewById(R.id.settings_button);

        // Initialize SDL when the app starts
        if (initSDL() != 0) {
            Toast.makeText(this, "Failed to initialize SDL", Toast.LENGTH_LONG).show();
        }

        startEmulatorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Launch the emulator activity
                Intent intent = new Intent(MainActivity.this, EmulatorActivity.class);
                startActivity(intent);
            }
        });

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Launch settings activity
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up SDL when the app exits
        shutdownSDL();
    }

    // Native methods
    private native int initSDL();
    private native void shutdownSDL();
}
