package com.fourdo.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class SetupWizardActivity extends AppCompatActivity {

    private static final int REQUEST_BIOS_FILE = 1;
    private static final int REQUEST_GAME = 2;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_BIOS = 1;
    private static final int STEP_GAME = 2;

    private int currentStep = STEP_WELCOME;

    private LinearLayout stepWelcome;
    private LinearLayout stepBios;
    private LinearLayout stepGame;

    private TextView biosStatusText;
    private TextView gameStatusText;

    private String selectedBiosPath;
    private String selectedBiosFolder;
    private String selectedGamePath;
    private String selectedGamesFolder;

    private Button nextButton;
    private Button backButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        // Use landscape orientation
        DeviceOrientationManager.setLandscapeOrientation(this);

        stepWelcome = findViewById(R.id.step_welcome);
        stepBios = findViewById(R.id.step_bios);
        stepGame = findViewById(R.id.step_game);

        biosStatusText = findViewById(R.id.bios_status_text);
        gameStatusText = findViewById(R.id.game_status_text);

        nextButton = findViewById(R.id.next_button);
        backButton = findViewById(R.id.back_button);

        Button selectBiosButton = findViewById(R.id.select_bios_button);
        Button selectGameButton = findViewById(R.id.select_game_button);

        selectBiosButton.setOnClickListener(v -> openBiosPicker());
        selectGameButton.setOnClickListener(v -> openGamePicker());

        nextButton.setOnClickListener(v -> goToNextStep());
        backButton.setOnClickListener(v -> goToPreviousStep());

        showStep(STEP_WELCOME);
    }

    private void showStep(int step) {
        currentStep = step;

        stepWelcome.setVisibility(step == STEP_WELCOME ? View.VISIBLE : View.GONE);
        stepBios.setVisibility(step == STEP_BIOS ? View.VISIBLE : View.GONE);
        stepGame.setVisibility(step == STEP_GAME ? View.VISIBLE : View.GONE);

        backButton.setVisibility(step > STEP_WELCOME ? View.VISIBLE : View.GONE);

        // Update next button text and state
        switch (step) {
            case STEP_WELCOME:
                nextButton.setText(R.string.setup_next);
                nextButton.setEnabled(true);
                break;
            case STEP_BIOS:
                nextButton.setText(R.string.setup_next);
                nextButton.setEnabled(selectedBiosPath != null && !selectedBiosPath.isEmpty());
                break;
            case STEP_GAME:
                nextButton.setText(R.string.setup_launch);
                nextButton.setEnabled(selectedGamePath != null && !selectedGamePath.isEmpty());
                break;
        }
    }

    private void goToNextStep() {
        switch (currentStep) {
            case STEP_WELCOME:
                showStep(STEP_BIOS);
                break;
            case STEP_BIOS:
                if (selectedBiosPath == null || selectedBiosPath.isEmpty()) {
                    Toast.makeText(this, R.string.setup_bios_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                showStep(STEP_GAME);
                break;
            case STEP_GAME:
                if (selectedGamePath == null || selectedGamePath.isEmpty()) {
                    Toast.makeText(this, R.string.setup_game_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                saveSettingsAndLaunch();
                break;
        }
    }

    private void goToPreviousStep() {
        switch (currentStep) {
            case STEP_BIOS:
                showStep(STEP_WELCOME);
                break;
            case STEP_GAME:
                showStep(STEP_BIOS);
                break;
        }
    }

    private void openBiosPicker() {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        startActivityForResult(intent, REQUEST_BIOS_FILE);
    }

    private void openGamePicker() {
        // During setup, use FileBrowser since library folder may not be set yet
        Intent intent = new Intent(this, FileBrowserActivity.class);
        startActivityForResult(intent, REQUEST_GAME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = uri.getPath();
                switch (requestCode) {
                    case REQUEST_BIOS_FILE:
                        if (isValidBiosFile(path)) {
                            selectedBiosPath = path;
                            // Auto-set parent folder as bios folder
                            File biosFile = new File(path);
                            File parentDir = biosFile.getParentFile();
                            if (parentDir != null) {
                                selectedBiosFolder = parentDir.getAbsolutePath();
                            }
                            biosStatusText.setText(getString(R.string.setup_bios_selected, getDisplayPath(path)));
                            nextButton.setEnabled(true);
                        } else {
                            Toast.makeText(this, R.string.setup_invalid_bios, Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case REQUEST_GAME:
                        if (isSupportedGameFile(path)) {
                            selectedGamePath = path;
                            // Auto-set parent folder as games library
                            File gameFile = new File(path);
                            File parentDir = gameFile.getParentFile();
                            if (parentDir != null) {
                                selectedGamesFolder = parentDir.getAbsolutePath();
                            }
                            gameStatusText.setText(getString(R.string.setup_game_selected, gameFile.getName()));
                            nextButton.setEnabled(true);
                        } else {
                            Toast.makeText(this, R.string.setup_invalid_game, Toast.LENGTH_SHORT).show();
                        }
                        break;
                }
            }
        }
    }

    private boolean isValidBiosFile(String path) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        if (!file.isFile()) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".bin") || lower.endsWith(".rom");
    }

    private boolean isSupportedGameFile(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".cue") || lower.endsWith(".iso") || lower.endsWith(".chd") || lower.endsWith(".bin");
    }

    private void saveSettingsAndLaunch() {
        // Save all settings synchronously
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("setup_completed", true);
        editor.putString(SettingsActivity.KEY_BIOS_PATH, selectedBiosPath);
        editor.putString(SettingsActivity.KEY_LAST_GAME_PATH, selectedGamePath);
        // Save auto-detected folders
        if (selectedBiosFolder != null && !selectedBiosFolder.isEmpty()) {
            editor.putString("bios_folder", selectedBiosFolder);
        }
        if (selectedGamesFolder != null && !selectedGamesFolder.isEmpty()) {
            editor.putString(SettingsActivity.KEY_LIBRARY_FOLDER, selectedGamesFolder);
        }
        // Use commit() for synchronous save instead of apply()
        boolean saved = editor.commit();
        
        if (!saved) {
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Verify paths are valid before launching
        File biosFile = new File(selectedBiosPath);
        File gameFile = new File(selectedGamePath);
        
        if (!biosFile.exists()) {
            Toast.makeText(this, "BIOS file not found: " + selectedBiosPath, Toast.LENGTH_LONG).show();
            return;
        }
        
        if (!gameFile.exists()) {
            Toast.makeText(this, "Game file not found: " + selectedGamePath, Toast.LENGTH_LONG).show();
            return;
        }

        // Launch game library after setup
        Intent intent = new Intent(this, GameLibraryActivity.class);
        startActivity(intent);
        finish();
    }

    private String getDisplayPath(String path) {
        if (path == null || path.isEmpty()) return path;
        String externalStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(externalStorage)) {
            String relative = path.substring(externalStorage.length());
            if (relative.isEmpty()) return "Storage";
            return "Storage" + relative;
        }
        return path;
    }

    public static boolean isSetupCompleted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("setup_completed", false);
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, SetupWizardActivity.class);
        context.startActivity(intent);
    }
}