package com.fourdo.android;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;

public class SetupWizardActivity extends AppCompatActivity {

    private static final int REQUEST_BIOS_FILE = 1;
    private static final int REQUEST_GAME = 2;
    private static final int REQUEST_EXTRACT_ARCHIVE = 3;

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
    private String pendingArchivePath;

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
                nextButton.setEnabled(selectedGamesFolder != null && !selectedGamesFolder.isEmpty());
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
                if (selectedGamesFolder == null || selectedGamesFolder.isEmpty()) {
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
        Intent intent = SafFileImporter.createOpenDocumentIntent();
        startActivityForResult(intent, REQUEST_BIOS_FILE);
    }

    private void openGamePicker() {
        Intent intent = SafFileImporter.createOpenDocumentTreeIntent();
        startActivityForResult(intent, REQUEST_GAME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                switch (requestCode) {
                    case REQUEST_BIOS_FILE:
                        try {
                            selectedBiosPath = SafFileImporter.importBios(this, uri);
                            File biosFile = new File(selectedBiosPath);
                            File parentDir = biosFile.getParentFile();
                            if (parentDir != null) {
                                selectedBiosFolder = parentDir.getAbsolutePath();
                            }
                            biosStatusText.setText(getString(R.string.setup_bios_selected, getDisplayPath(selectedBiosPath)));
                            nextButton.setEnabled(true);
                        } catch (Exception e) {
                            Toast.makeText(this, "BIOS import failed", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case REQUEST_GAME:
                        Toast.makeText(this, "Importing library...", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            try {
                                SafFileImporter.ImportResult result = SafFileImporter.importLibraryTree(this, uri);
                                selectedGamesFolder = result.path;
                                selectedGamePath = null;
                                runOnUiThread(() -> {
                                    gameStatusText.setText(getString(R.string.setup_game_selected, getDisplayPath(result.path)));
                                    nextButton.setEnabled(true);
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(this, "Library import failed", Toast.LENGTH_SHORT).show());
                            }
                        }, "setup-library-import").start();
                        break;
                }
            }
        }
    }

    private void showExtractArchiveDialog(String archivePath) {
        File archiveFile = new File(archivePath);
        new AlertDialog.Builder(this)
            .setTitle(R.string.extract_archive)
            .setMessage("Extract \"" + archiveFile.getName() + "\" to access game files?")
            .setPositiveButton(R.string.extract_archive, (dialog, which) -> {
                new ExtractArchiveTask().execute(archivePath);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private class ExtractArchiveTask extends AsyncTask<String, Integer, String> {
        private ProgressDialog progressDialog;

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(SetupWizardActivity.this);
            progressDialog.setMessage(getString(R.string.extracting));
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setIndeterminate(true);
            progressDialog.show();
        }

        @Override
        protected String doInBackground(String... params) {
            String archivePath = params[0];
            File archiveFile = new File(archivePath);
            File extractDir = archiveFile.getParentFile();

            try {
                String lower = archivePath.toLowerCase();
                if (lower.endsWith(".zip")) {
                    return extractZipFile(archiveFile, extractDir);
                } else if (lower.endsWith(".7z")) {
                    return extract7zFile(archiveFile, extractDir);
                } else if (lower.endsWith(".rar")) {
                    // RAR is not easily extractable without third-party libs
                    // For now, inform user to use external app
                    return "RAR_EXTERNAL:" + archivePath;
                }
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
            return "UNSUPPORTED";
        }

        private String extractZipFile(File archive, File destDir) throws IOException {
            List<String> extractedFiles = new ArrayList<>();
            byte[] buffer = new byte[8192];
            int bytesRead;

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File newFile = newFile(destDir, entry);
                    if (entry.isDirectory()) {
                        if (!newFile.isDirectory() && !newFile.mkdirs()) {
                            throw new IOException("Failed to create directory: " + newFile);
                        }
                    } else {
                        File parent = newFile.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) {
                            throw new IOException("Failed to create directory: " + parent);
                        }
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            while ((bytesRead = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                        extractedFiles.add(newFile.getName());
                    }
                }
            }

            // Find a game file in the extracted files
            for (String fileName : extractedFiles) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".cue") || lower.endsWith(".iso") || lower.endsWith(".chd")) {
                    File gameFile = new File(destDir, fileName);
                    if (gameFile.exists()) {
                        return "SUCCESS:" + gameFile.getAbsolutePath();
                    }
                }
            }
            // If no game file found, return first extracted file
            if (!extractedFiles.isEmpty()) {
                return "SUCCESS:" + new File(destDir, extractedFiles.get(0)).getAbsolutePath();
            }
            return "NO_FILES";
        }

        private String extract7zFile(File archive, File destDir) throws IOException {
            List<String> extractedFiles = new ArrayList<>();
            byte[] buffer = new byte[8192];

            try (SevenZFile sevenZFile = new SevenZFile(archive)) {
                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    File newFile = new File(destDir, entry.getName());
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory: " + parent);
                    }

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int bytesRead;
                        while ((bytesRead = sevenZFile.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    extractedFiles.add(newFile.getName());
                }
            }

            // Find a game file in the extracted files
            for (String fileName : extractedFiles) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".cue") || lower.endsWith(".iso") || lower.endsWith(".chd")) {
                    File gameFile = new File(destDir, fileName);
                    if (gameFile.exists()) {
                        return "SUCCESS:" + gameFile.getAbsolutePath();
                    }
                }
            }
            // If no game file found, return first extracted file
            if (!extractedFiles.isEmpty()) {
                return "SUCCESS:" + new File(destDir, extractedFiles.get(0)).getAbsolutePath();
            }
            return "NO_FILES";
        }

        private File newFile(File destDir, ZipEntry entry) throws IOException {
            File destFile = new File(destDir, entry.getName());
            String destDirPath = destDir.getCanonicalPath();
            String destFilePath = destFile.getCanonicalPath();
            if (!destFilePath.startsWith(destDirPath + File.separator)) {
                throw new IOException("Entry outside target dir: " + entry.getName());
            }
            return destFile;
        }

        @Override
        protected void onPostExecute(String result) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }

            if (result.startsWith("SUCCESS:")) {
                String extractedPath = result.substring(8);
                Toast.makeText(SetupWizardActivity.this, R.string.extraction_complete, Toast.LENGTH_SHORT).show();
                // Auto-select the extracted game file
                if (isSupportedGameFile(extractedPath)) {
                    selectedGamePath = extractedPath;
                    File gameFile = new File(extractedPath);
                    File parentDir = gameFile.getParentFile();
                    if (parentDir != null) {
                        selectedGamesFolder = parentDir.getAbsolutePath();
                    }
                    gameStatusText.setText(getString(R.string.setup_game_selected, gameFile.getName()));
                    nextButton.setEnabled(true);
                }
            } else if (result.startsWith("7Z_EXTERNAL:") || result.startsWith("RAR_EXTERNAL:")) {
                new AlertDialog.Builder(SetupWizardActivity.this)
                    .setTitle("Manual Extraction Required")
                    .setMessage("For .7z and .rar files, please use a file manager app to extract the archive first.\n\nThen select the extracted .cue, .iso, or .chd file.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            } else if (result.startsWith("ERROR:")) {
                Toast.makeText(SetupWizardActivity.this, 
                    getString(R.string.extraction_failed) + ": " + result.substring(6), Toast.LENGTH_LONG).show();
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

    private boolean isArchiveFile(String path) {
        if (path == null || path.isEmpty()) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar");
    }

    private void saveSettingsAndLaunch() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("setup_completed", true);
        editor.putString(SettingsActivity.KEY_BIOS_PATH, selectedBiosPath);
        if (selectedBiosFolder != null && !selectedBiosFolder.isEmpty()) {
            editor.putString("bios_folder", selectedBiosFolder);
        }
        if (selectedGamesFolder != null && !selectedGamesFolder.isEmpty()) {
            editor.putString(SettingsActivity.KEY_LIBRARY_FOLDER, selectedGamesFolder);
        }
        boolean saved = editor.commit();

        if (!saved) {
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show();
            return;
        }

        File biosFile = new File(selectedBiosPath);
        if (!biosFile.exists()) {
            Toast.makeText(this, "BIOS file not found: " + selectedBiosPath, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, EmulatorActivity.class);
        startActivity(intent);
        finish();
    }

    private String getDisplayPath(String path) {
        if (path == null || path.isEmpty()) return path;
        File libraryDir = SafFileImporter.getManagedLibraryDirectory(this);
        if (path.startsWith(libraryDir.getAbsolutePath())) {
            String relative = path.substring(libraryDir.getAbsolutePath().length());
            return relative.isEmpty() ? "Managed Library" : "Managed Library" + relative;
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
