
package com.fourdo.android;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GameLibraryActivity extends AppCompatActivity implements CarouselAdapter.OnGameClickListener {

    private static final String TAG = "GameLibrary";
    private static final Pattern VERSION_SUFFIX_PATTERN = Pattern.compile("(?i)\\bv\\d+(?:\\.\\d+)*\\b");
    private static final int MAX_CONCURRENT_IGDB_REQUESTS = 3;

    public static final String EXTRA_LIBRARY_PATH = "library_path";
    public static final String EXTRA_PICK_MODE = "pick_mode";

    private GridView gameGridView;
    private ViewPager2 carouselView;
    private ProgressBar loadingProgress;
    private TextView statusText;
    private EditText librarySearch;
    private Button settingsButton;
    private Button searchButton;
    private Button viewToggleBtn;

    private final List<GameItem> gameItems = new ArrayList<>();
    private final List<GameItem> visibleGameItems = new ArrayList<>();
    private GameGridAdapter adapter;
    private CarouselAdapter carouselAdapter;
    private IgdbService igdbService;
    private String libraryPath;
    private boolean pickMode = false;
    
    // View mode
    private int viewStyle = SettingsActivity.VIEW_STYLE_GRID_MEDIUM;
    private volatile boolean libraryImportInProgress = false;
    
    // Cache for game matches
    private Map<String, IgdbService.IgdbGame> gameMatches = new HashMap<>();
    private int igdbNextIndex = 0;
    private int igdbInFlight = 0;

    public static class GameItem {
        String name;
        String filePath;
        IgdbService.IgdbGame igdbGame;
        Bitmap coverBitmap;
        boolean coverLoaded = false;
        
        GameItem(String name, String filePath) {
            this.name = name;
            this.filePath = filePath;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_library);

        DeviceOrientationManager.setLandscapeOrientation(this);

        gameGridView = findViewById(R.id.game_grid);
        carouselView = findViewById(R.id.carousel_view);
        loadingProgress = findViewById(R.id.loading_progress);
        statusText = findViewById(R.id.status_text);
        librarySearch = findViewById(R.id.library_search);
        settingsButton = findViewById(R.id.settings_button);
        searchButton = findViewById(R.id.search_button);
        viewToggleBtn = findViewById(R.id.view_toggle_button);

        igdbService = IgdbService.getInstance(this);

        // Load view style preference
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        viewStyle = prefs.getInt(SettingsActivity.KEY_VIEW_STYLE, SettingsActivity.VIEW_STYLE_GRID_MEDIUM);

        // View toggle button
        viewToggleBtn.setOnClickListener(v -> cycleViewStyle());

        log("GameLibraryActivity onCreate");

        // Get library path
        libraryPath = getIntent() != null ? getIntent().getStringExtra(EXTRA_LIBRARY_PATH) : null;
        pickMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_PICK_MODE, false);
        if (libraryPath == null || libraryPath.isEmpty()) {
            libraryPath = prefs.getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
        }
        
        log("Library path: " + libraryPath);

        // Also log BIOS path for debugging
        String biosPath = prefs.getString(SettingsActivity.KEY_BIOS_PATH, "");
        log("BIOS path: " + biosPath);
        
        File biosFile = new File(biosPath);
        log("BIOS file exists: " + biosFile.exists());

        if (libraryPath == null || libraryPath.isEmpty()) {
            log("ERROR: Library path not set");
            statusText.setText(R.string.library_not_set);
            statusText.setVisibility(View.VISIBLE);
            gameGridView.setVisibility(View.GONE);
            return;
        }

        File root = new File(libraryPath);
        if (!root.isDirectory()) {
            log("ERROR: Library path is not a directory: " + libraryPath);
            statusText.setText(R.string.library_not_set);
            statusText.setVisibility(View.VISIBLE);
            gameGridView.setVisibility(View.GONE);
            return;
        }

        adapter = new GameGridAdapter(this, visibleGameItems);
        gameGridView.setAdapter(adapter);
        
        carouselAdapter = new CarouselAdapter(this, visibleGameItems);
        carouselView.setAdapter(carouselAdapter);
        carouselView.setOffscreenPageLimit(3);

        applyViewStyle();

        gameGridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < visibleGameItems.size()) {
                showGameDetails(visibleGameItems.get(position), position);
            }
        });

        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        searchButton.setOnClickListener(v -> {
            if (libraryPath != null && !libraryPath.isEmpty()) {
                File refreshRoot = new File(libraryPath);
                if (refreshRoot.isDirectory()) {
                    loadGames(refreshRoot);
                }
            }
        });

        if (librarySearch != null) {
            librarySearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    applyLibraryFilter();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        loadGames(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }
    
    private boolean autoExtractRoms(File libraryFolder) {
        File[] archives = libraryFolder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar");
        });
        
        if (archives == null || archives.length == 0) {
            return false;
        }

        boolean extractedAny = false;
        
        for (File archive : archives) {
            extractedAny = extractArchive(archive, libraryFolder) || extractedAny;
        }

        return extractedAny;
    }
    
    private boolean extractArchive(File archive, File outputDir) {
        log("Extracting: " + archive.getName());
        
        try {
            String lower = archive.getName().toLowerCase();
            boolean success;
            if (lower.endsWith(".7z")) {
                success = extract7zArchive(archive, outputDir);
            } else if (lower.endsWith(".zip")) {
                success = extractZipArchive(archive, outputDir);
            } else {
                log("Unknown archive format: " + archive.getName());
                return false;
            }

            if (success) {
                log("Extraction successful: " + archive.getName());
                if (!archive.delete()) {
                    log("Could not delete extracted archive: " + archive.getAbsolutePath());
                }
                return true;
            } else {
                log("Extraction failed for: " + archive.getName());
                return false;
            }
        } catch (Throwable e) {
            log("Error extracting archive: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Reload view style in case it changed in settings
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int newStyle = prefs.getInt(SettingsActivity.KEY_VIEW_STYLE, SettingsActivity.VIEW_STYLE_GRID_MEDIUM);
        if (newStyle != viewStyle) {
            viewStyle = newStyle;
            applyViewStyle();
        }

        String savedLibraryPath = prefs.getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
        if (savedLibraryPath != null && !savedLibraryPath.isEmpty() && !savedLibraryPath.equals(libraryPath)) {
            log("Library path changed: " + savedLibraryPath);
            libraryPath = savedLibraryPath;
            File newLibraryFolder = new File(libraryPath);
            if (newLibraryFolder.isDirectory()) {
                statusText.setVisibility(View.GONE);
                gameGridView.setVisibility(View.VISIBLE);
                processLibraryDownloadsAsync(newLibraryFolder);
                loadGames(newLibraryFolder);
            }
            return;
        }

        if (libraryPath != null && !libraryPath.isEmpty()) {
            File libraryFolder = new File(libraryPath);
            if (libraryFolder.isDirectory()) {
                processLibraryDownloadsAsync(libraryFolder);
            }
        }
    }

    private void processLibraryDownloadsAsync(File libraryFolder) {
        if (libraryImportInProgress) {
            return;
        }

        libraryImportInProgress = true;
        new Thread(() -> {
            boolean libraryChanged = false;
            try {
                libraryChanged = consumeLibraryRefreshFlag() || libraryChanged;
                libraryChanged = autoExtractRoms(libraryFolder) || libraryChanged;
            } finally {
                libraryImportInProgress = false;
            }

            if (libraryChanged) {
                runOnUiThread(() -> loadGames(libraryFolder));
            }
        }, "library-import").start();
    }

    private boolean consumeLibraryRefreshFlag() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean refreshNeeded = prefs.getBoolean(SettingsActivity.KEY_LIBRARY_REFRESH_REQUIRED, false);
        if (refreshNeeded) {
            prefs.edit().putBoolean(SettingsActivity.KEY_LIBRARY_REFRESH_REQUIRED, false).apply();
        }
        return refreshNeeded;
    }

    private boolean extractZipArchive(File archive, File destDir) throws IOException {
        byte[] buffer = new byte[8192];

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File extractedFile = resolveExtractedFile(destDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!extractedFile.isDirectory() && !extractedFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + extractedFile.getAbsolutePath());
                    }
                    continue;
                }

                File parent = extractedFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
                }

                try (FileOutputStream fos = new FileOutputStream(extractedFile)) {
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
            }
        }

        return true;
    }

    private boolean extract7zArchive(File archive, File destDir) throws IOException {
        byte[] buffer = new byte[8192];

        try (SevenZFile sevenZFile = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                File extractedFile = resolveExtractedFile(destDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!extractedFile.isDirectory() && !extractedFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + extractedFile.getAbsolutePath());
                    }
                    continue;
                }

                File parent = extractedFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
                }

                try (FileOutputStream fos = new FileOutputStream(extractedFile)) {
                    int bytesRead;
                    while ((bytesRead = sevenZFile.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
            }
        }

        return true;
    }

    private File resolveExtractedFile(File destDir, String entryName) throws IOException {
        File destFile = new File(destDir, entryName);
        String destDirPath = destDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator) && !destFilePath.equals(destDirPath)) {
            throw new IOException("Entry outside target dir: " + entryName);
        }
        return destFile;
    }
    
    private void cycleViewStyle() {
        viewStyle = viewStyle == SettingsActivity.VIEW_STYLE_CAROUSEL
                ? SettingsActivity.VIEW_STYLE_GRID_MEDIUM
                : SettingsActivity.VIEW_STYLE_CAROUSEL;
        
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(SettingsActivity.KEY_VIEW_STYLE, viewStyle).apply();
        
        applyViewStyle();
        
        Toast.makeText(this, "View: " + (viewStyle == SettingsActivity.VIEW_STYLE_CAROUSEL ? "Carousel" : "Grid"), Toast.LENGTH_SHORT).show();
    }
    
    private void applyViewStyle() {
        switch (viewStyle) {
            case SettingsActivity.VIEW_STYLE_GRID_SMALL:
                gameGridView.setNumColumns(4);
                gameGridView.setVisibility(View.VISIBLE);
                carouselView.setVisibility(View.GONE);
                break;
            case SettingsActivity.VIEW_STYLE_GRID_MEDIUM:
                gameGridView.setNumColumns(4);
                gameGridView.setVisibility(View.VISIBLE);
                carouselView.setVisibility(View.GONE);
                break;
            case SettingsActivity.VIEW_STYLE_GRID_LARGE:
                gameGridView.setNumColumns(4);
                gameGridView.setVisibility(View.VISIBLE);
                carouselView.setVisibility(View.GONE);
                break;
            case SettingsActivity.VIEW_STYLE_CAROUSEL:
                gameGridView.setVisibility(View.GONE);
                carouselView.setVisibility(View.VISIBLE);
                break;
        }

        if (viewToggleBtn != null) {
            viewToggleBtn.setText(viewStyle == SettingsActivity.VIEW_STYLE_CAROUSEL ? "Grid" : "Carousel");
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (carouselAdapter != null) {
            carouselAdapter.notifyDataSetChanged();
        }
    }

    private void applyLibraryFilter() {
        String query = librarySearch == null ? "" : librarySearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        visibleGameItems.clear();

        for (GameItem item : gameItems) {
            if (matchesLibraryQuery(item, query)) {
                visibleGameItems.add(item);
            }
        }

        notifyLibraryChanged();

        if (gameItems.isEmpty()) {
            statusText.setText(R.string.no_games_found);
            statusText.setVisibility(View.VISIBLE);
        } else if (visibleGameItems.isEmpty()) {
            statusText.setText("No matches for \"" + query + "\"");
            statusText.setVisibility(View.VISIBLE);
        } else {
            statusText.setText(visibleGameItems.size() + " of " + gameItems.size() + " games");
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private boolean matchesLibraryQuery(GameItem item, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        String title = item.igdbGame != null ? item.igdbGame.name : item.name;
        String publisher = item.igdbGame != null ? item.igdbGame.publisher : "";
        String fileName = item.filePath == null ? "" : new File(item.filePath).getName();
        String haystack = (title + " " + item.name + " " + publisher + " " + fileName).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private void notifyLibraryChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (carouselAdapter != null) {
            carouselAdapter.notifyDataSetChanged();
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
    }

    private void loadGames(File root) {
        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.GONE);
        log("Loading games from: " + root.getAbsolutePath());

        new Thread(() -> {
            // Scan local files
            List<File> gameFiles = new ArrayList<>();
            scanGames(root, gameFiles);
            Collections.sort(gameFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            log("Found " + gameFiles.size() + " game files");

            // Create game items
            gameItems.clear();
            for (File file : gameFiles) {
                GameItem item = new GameItem(getGameName(file.getName()), file.getAbsolutePath());
                gameItems.add(item);
            }

            runOnUiThread(() -> {
                loadingProgress.setVisibility(View.GONE);
                applyLibraryFilter();
                
                if (gameItems.isEmpty()) {
                    statusText.setText(R.string.no_games_found);
                    statusText.setVisibility(View.VISIBLE);
                } else {
                    // Try to match games with IGDB
                    matchGamesWithIgdb();
                }
            });
        }).start();
    }

    private void scanGames(File dir, List<File> gameFiles) {
        File[] files = dir.listFiles();
        if (files == null) {
            log("Cannot list files in: " + dir.getAbsolutePath());
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanGames(file, gameFiles);
            } else if (isSupportedGameFile(file)) {
                gameFiles.add(file);
            }
        }
    }

    private boolean isSupportedGameFile(File file) {
        String name = file.getName().toLowerCase();
        // CUE files are the master for CUE/BIN pairs. Show a BIN only when no
        // CUE in the same folder points at it, so direct BIN libraries still appear.
        // CHD and ISO are standalone formats
        return name.endsWith(".cue")
                || name.endsWith(".iso")
                || name.endsWith(".chd")
                || (name.endsWith(".bin") && !hasCueForBin(file));
    }

    private boolean hasCueForBin(File binFile) {
        File parent = binFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return false;
        }

        String binName = binFile.getName();
        int dot = binName.lastIndexOf('.');
        String baseName = dot > 0 ? binName.substring(0, dot) : binName;
        File sameBaseCue = new File(parent, baseName + ".cue");
        if (sameBaseCue.isFile()) {
            return true;
        }

        File[] cueFiles = parent.listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".cue"));
        if (cueFiles == null) {
            return false;
        }

        for (File cueFile : cueFiles) {
            if (cueReferencesFile(cueFile, binName)) {
                return true;
            }
        }
        return false;
    }

    private boolean cueReferencesFile(File cueFile, String fileName) {
        try (BufferedReader reader = new BufferedReader(new FileReader(cueFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.regionMatches(true, 0, "FILE", 0, 4)) {
                    continue;
                }
                String referencedName = parseCueFileReference(trimmed);
                if (referencedName != null && new File(referencedName).getName().equalsIgnoreCase(fileName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log("Failed reading cue file for BIN pairing: " + e.getMessage());
        }
        return false;
    }

    private String getGameName(String fileName) {
        // Remove extension
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    private String buildIgdbQueryName(String rawName) {
        if (rawName == null) {
            return "";
        }

        String queryName = rawName.trim();
        int cutIndex = queryName.length();

        int parenIndex = queryName.indexOf('(');
        if (parenIndex >= 0) {
            cutIndex = Math.min(cutIndex, parenIndex);
        }

        int bracketIndex = queryName.indexOf('[');
        if (bracketIndex >= 0) {
            cutIndex = Math.min(cutIndex, bracketIndex);
        }

        int braceIndex = queryName.indexOf('{');
        if (braceIndex >= 0) {
            cutIndex = Math.min(cutIndex, braceIndex);
        }

        Matcher matcher = VERSION_SUFFIX_PATTERN.matcher(queryName);
        if (matcher.find() && matcher.start() > 0) {
            cutIndex = Math.min(cutIndex, matcher.start());
        }

        if (cutIndex < queryName.length()) {
            queryName = queryName.substring(0, cutIndex).trim();
        }

        return queryName;
    }

    private String normalizeForComparison(String name) {
        if (name == null) {
            return "";
        }

        return buildIgdbQueryName(name)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private void matchGamesWithIgdb() {
        log("Starting IGDB matching...");
        
        // Authenticate first
        igdbService.authenticate(() -> {
            log("IGDB auth successful, searching for " + gameItems.size() + " games");
            igdbNextIndex = 0;
            igdbInFlight = 0;
            pumpIgdbQueue();
        }, () -> {
            log("ERROR: IGDB auth failed");
        });
    }

    private void pumpIgdbQueue() {
        while (igdbInFlight < MAX_CONCURRENT_IGDB_REQUESTS && igdbNextIndex < gameItems.size()) {
            final GameItem item = gameItems.get(igdbNextIndex++);
            igdbInFlight++;
            searchGameOnIgdb(item, () -> {
                igdbInFlight--;
                pumpIgdbQueue();
            });
        }
    }

    private void searchGameOnIgdb(GameItem item, Runnable onComplete) {
        String queryName = buildIgdbQueryName(item.name);
        if (queryName.isEmpty()) {
            queryName = item.name;
        }

        log("Looking up (cached): " + item.name + " (query: " + queryName + ")");
        
        String finalQueryName = queryName;
        igdbService.lookupGame(finalQueryName, igdbGame -> {
            try {
                if (igdbGame != null) {
                    item.igdbGame = igdbGame;
                    log("Matched '" + item.name + "' to IGDB: " + igdbGame.name);
                    
                    // Load cover
                    if (igdbGame.coverUrl != null && !igdbGame.coverUrl.isEmpty()) {
                        igdbService.loadCover(igdbGame.coverUrl, igdbGame.id, (bitmap, localPath) -> {
                            if (bitmap != null) {
                                item.coverBitmap = bitmap;
                                item.coverLoaded = true;
                                runOnUiThread(() -> {
                                    notifyLibraryChanged();
                                });
                            }
                        });
                    }
                    
                    runOnUiThread(() -> {
                        applyLibraryFilter();
                    });
                } else {
                    log("No IGDB match for: " + item.name);
                }
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    private void showGameDetails(GameItem item, int position) {
        log("Showing details for: " + item.name);
        log("  File path: " + item.filePath);
        log("  IGDB match: " + (item.igdbGame != null ? item.igdbGame.name : "none"));
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_game_details, null);
        
        ImageView coverView = view.findViewById(R.id.detail_cover);
        TextView titleView = view.findViewById(R.id.detail_title);
        TextView yearView = view.findViewById(R.id.detail_year);
        TextView publisherView = view.findViewById(R.id.detail_publisher);
        TextView summaryView = view.findViewById(R.id.detail_summary);
        Button playButton = view.findViewById(R.id.play_button);
        Button cancelButton = view.findViewById(R.id.cancel_button);
        Button correctGameButton = view.findViewById(R.id.correct_game_button);
        Button deleteButton = view.findViewById(R.id.delete_button);

        // Set cover
        if (item.coverBitmap != null) {
            coverView.setImageBitmap(item.coverBitmap);
        } else {
            coverView.setImageResource(R.drawable.ic_launcher_foreground_source);
        }

        // Set title
        String title = item.igdbGame != null ? item.igdbGame.name : item.name;
        String fileFormat = getGameFormatLabel(item.filePath);
        if (!fileFormat.isEmpty()) {
            title = title + " [" + fileFormat + "]";
        }
        titleView.setText(title);
        
        // Set year
        if (item.igdbGame != null && item.igdbGame.releaseDate != null && !item.igdbGame.releaseDate.isEmpty()) {
            yearView.setText(item.igdbGame.releaseDate);
            yearView.setVisibility(View.VISIBLE);
        } else {
            yearView.setVisibility(View.GONE);
        }
        
        // Set publisher
        if (item.igdbGame != null && item.igdbGame.publisher != null && !item.igdbGame.publisher.isEmpty()) {
            publisherView.setText(item.igdbGame.publisher);
            publisherView.setVisibility(View.VISIBLE);
        } else {
            publisherView.setVisibility(View.GONE);
        }
        
        // Set summary
        String bezelStatus = BezelResolver.describeBezel(this, item.filePath);
        String summary = item.igdbGame != null && item.igdbGame.summary != null ? item.igdbGame.summary : "";
        if (!summary.isEmpty()) {
            summary = summary + "\n\n" + bezelStatus;
        } else {
            summary = bezelStatus;
        }
        summaryView.setText(summary);
        summaryView.setVisibility(View.VISIBLE);

        builder.setView(view);
        AlertDialog dialog = builder.create();

        // Correct game button
        correctGameButton.setOnClickListener(v -> {
            dialog.dismiss();
            showCorrectGameDialog(item, position);
        });

        deleteButton.setVisibility(pickMode ? View.GONE : View.VISIBLE);
        deleteButton.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteGame(item);
        });

        playButton.setOnClickListener(v -> {
            dialog.dismiss();
            if (item.filePath != null && !item.filePath.isEmpty()) {
                log("Launching game: " + item.filePath);
                
                // Check if file exists
                File gameFile = new File(item.filePath);
                if (!gameFile.exists()) {
                    log("ERROR: Game file does not exist: " + item.filePath);
                    Toast.makeText(this, "Game file not found", Toast.LENGTH_LONG).show();
                    return;
                }
                
                // Check BIOS
                SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                String biosPath = prefs.getString(SettingsActivity.KEY_BIOS_PATH, "");
                log("BIOS path from prefs: " + biosPath);
                
                if (biosPath == null || biosPath.isEmpty()) {
                    log("ERROR: BIOS path not set");
                    Toast.makeText(this, "BIOS not configured. Go to Settings.", Toast.LENGTH_LONG).show();
                    return;
                }
                
                File biosFile = new File(biosPath);
                if (!biosFile.exists()) {
                    log("ERROR: BIOS file does not exist: " + biosPath);
                    Toast.makeText(this, "BIOS file not found", Toast.LENGTH_LONG).show();
                    return;
                }

                if (pickMode) {
                    Intent resultIntent = new Intent();
                    resultIntent.setData(android.net.Uri.fromFile(gameFile));
                    setResult(RESULT_OK, resultIntent);
                    finish();
                    return;
                }
                
                log("All checks passed, launching EmulatorActivity");
                // Launch emulator with the game
                EmulatorActivity.start(GameLibraryActivity.this, item.filePath);
            } else {
                log("ERROR: No file path for game");
                Toast.makeText(GameLibraryActivity.this, "No game file available", Toast.LENGTH_SHORT).show();
            }
        });

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void confirmDeleteGame(GameItem item) {
        if (item == null || item.filePath == null || item.filePath.isEmpty()) {
            Toast.makeText(this, "No game file available", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete Game")
                .setMessage("Delete this game from the library?\n\n" + item.name)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteGameFiles(item))
                .show();
    }

    private void deleteGameFiles(GameItem item) {
        File gameFile = new File(item.filePath);
        List<File> filesToDelete = collectFilesToDelete(gameFile);
        List<String> failedDeletes = new ArrayList<>();

        for (File file : filesToDelete) {
            if (file.exists() && !file.delete()) {
                failedDeletes.add(file.getName());
            }
        }

        if (!failedDeletes.isEmpty()) {
            log("Failed deleting files for " + item.name + ": " + failedDeletes);
            Toast.makeText(this, "Could not delete: " + String.join(", ", failedDeletes), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Deleted: " + item.name, Toast.LENGTH_SHORT).show();
        File root = new File(libraryPath);
        if (root.isDirectory()) {
            loadGames(root);
        } else {
            gameItems.remove(item);
            applyLibraryFilter();
        }
    }

    private List<File> collectFilesToDelete(File gameFile) {
        List<File> files = new ArrayList<>();
        files.add(gameFile);

        String name = gameFile.getName();
        int dot = name.lastIndexOf('.');
        String baseName = dot > 0 ? name.substring(0, dot) : name;
        String lowerName = name.toLowerCase(Locale.ROOT);
        File parent = gameFile.getParentFile();

        if (parent == null || !parent.isDirectory()) {
            return files;
        }

        if (lowerName.endsWith(".cue")) {
            addCueReferencedFiles(files, gameFile);

            File[] sidecars = parent.listFiles(file -> {
                if (!file.isFile()) {
                    return false;
                }
                String sidecarName = file.getName();
                int sidecarDot = sidecarName.lastIndexOf('.');
                String sidecarBase = sidecarDot > 0 ? sidecarName.substring(0, sidecarDot) : sidecarName;
                String sidecarLower = sidecarName.toLowerCase(Locale.ROOT);
                return sidecarBase.equalsIgnoreCase(baseName)
                        && (sidecarLower.endsWith(".bin")
                        || sidecarLower.endsWith(".img")
                        || sidecarLower.endsWith(".iso")
                        || sidecarLower.endsWith(".sub")
                        || sidecarLower.endsWith(".ccd")
                        || sidecarLower.endsWith(".mdf")
                        || sidecarLower.endsWith(".mds")
                        || sidecarLower.endsWith(".wav")
                        || sidecarLower.endsWith(".wv"));
            });
            if (sidecars != null) {
                Collections.addAll(files, sidecars);
            }
        }

        return files;
    }

    private void addCueReferencedFiles(List<File> files, File cueFile) {
        File parent = cueFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(cueFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.regionMatches(true, 0, "FILE", 0, 4)) {
                    continue;
                }

                String referencedName = parseCueFileReference(trimmed);
                if (referencedName == null || referencedName.isEmpty()) {
                    continue;
                }

                File referencedFile = new File(parent, referencedName.replace('\\', File.separatorChar));
                if (referencedFile.isFile() && !files.contains(referencedFile)) {
                    files.add(referencedFile);
                }
            }
        } catch (IOException e) {
            log("Failed reading cue file for delete sidecars: " + e.getMessage());
        }
    }

    private String parseCueFileReference(String trimmedLine) {
        if (trimmedLine == null || !trimmedLine.regionMatches(true, 0, "FILE", 0, 4)) {
            return null;
        }

        String rest = trimmedLine.substring(4).trim();
        if (rest.isEmpty()) {
            return null;
        }

        if (rest.charAt(0) == '"') {
            int secondQuote = rest.indexOf('"', 1);
            if (secondQuote <= 1) {
                return null;
            }
            return rest.substring(1, secondQuote);
        }

        int lastSpace = rest.lastIndexOf(' ');
        return lastSpace > 0 ? rest.substring(0, lastSpace).trim() : rest;
    }

    private String getGameFormatLabel(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        String name = new File(filePath).getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }

        return name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
    
    private void showCorrectGameDialog(GameItem item, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Search for Correct Game");
        
        // Create a LinearLayout for the dialog
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        
        // Add a hint text
        TextView hintText = new TextView(this);
        hintText.setText("Current: " + item.name + "\nEnter the correct game name to search:");
        hintText.setTextColor(0xFFAAAAAA);
        layout.addView(hintText);
        
        // Add EditText
        final EditText input = new EditText(this);
        input.setText(buildIgdbQueryName(item.name));
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        layout.addView(input);
        
        builder.setView(layout);
        
        builder.setPositiveButton("Search", (dialog, which) -> {
            String query = input.getText().toString().trim();
            if (!query.isEmpty()) {
                searchAndSelectGame(item, position, query);
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void searchAndSelectGame(GameItem item, int position, String query) {
        loadingProgress.setVisibility(View.VISIBLE);
        log("Searching for correct game: " + query);
        
        igdbService.searchGames(query, games -> {
            loadingProgress.setVisibility(View.GONE);
            
            if (games.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "No games found for: " + query, Toast.LENGTH_SHORT).show());
                return;
            }
            
            // If only one result, use it directly
            if (games.size() == 1) {
                updateGameInfo(item, position, games.get(0));
                return;
            }
            
            // Show selection dialog
            runOnUiThread(() -> {
                String[] gameNames = new String[games.size()];
                for (int i = 0; i < games.size(); i++) {
                    IgdbService.IgdbGame g = games.get(i);
                    gameNames[i] = g.name + (g.releaseDate != null ? " (" + g.releaseDate + ")" : "");
                }
                
                AlertDialog.Builder selectBuilder = new AlertDialog.Builder(this);
                selectBuilder.setTitle("Select Correct Game");
                selectBuilder.setItems(gameNames, (dialog, which) -> {
                    updateGameInfo(item, position, games.get(which));
                });
                selectBuilder.setNegativeButton("Cancel", null);
                selectBuilder.show();
            });
        });
    }
    
    private void updateGameInfo(GameItem item, int position, IgdbService.IgdbGame game) {
        log("Updating game info: " + item.name + " -> " + game.name);
        
        item.igdbGame = game;
        item.coverLoaded = false;
        item.coverBitmap = null;
        
        // Update cache
        String key = buildIgdbQueryName(item.name).toLowerCase().trim();
        igdbService.updateCache(key, game);
        
        // Load new cover
        if (game.coverUrl != null && !game.coverUrl.isEmpty()) {
            igdbService.loadCover(game.coverUrl, game.id, (bitmap, localPath) -> {
                if (bitmap != null) {
                    item.coverBitmap = bitmap;
                    item.coverLoaded = true;
                    runOnUiThread(() -> {
                        notifyLibraryChanged();
                    });
                }
            });
        }
        
        runOnUiThread(() -> {
            applyLibraryFilter();
            Toast.makeText(this, "Game info updated: " + game.name, Toast.LENGTH_SHORT).show();
        });
    }

    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Search Games");
        
        EditText input = new EditText(this);
        input.setHint("Enter game name");
        builder.setView(input);
        
        builder.setPositiveButton("Search", (dialog, which) -> {
            String query = input.getText().toString().trim();
            if (!query.isEmpty()) {
                searchIgdb(query);
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // Implementation of CarouselAdapter.OnGameClickListener
    @Override
    public void onGameClick(GameItem item, int position) {
        showGameDetails(item, position);
    }

    private void searchIgdb(String query) {
        loadingProgress.setVisibility(View.VISIBLE);
        log("Searching IGDB for: " + query);
        
        igdbService.searchGames(query, games -> {
            loadingProgress.setVisibility(View.GONE);
            log("IGDB search returned " + games.size() + " results");
            
            if (games.isEmpty()) {
                Toast.makeText(this, "No games found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Show results
            gameItems.clear();
            for (IgdbService.IgdbGame game : games) {
                GameItem item = new GameItem(game.name, "");
                item.igdbGame = game;
                gameItems.add(item);
                
                // Load cover
                if (game.coverUrl != null && !game.coverUrl.isEmpty()) {
                    igdbService.loadCover(game.coverUrl, game.id, (bitmap, localPath) -> {
                        if (bitmap != null) {
                            item.coverBitmap = bitmap;
                            item.coverLoaded = true;
                            runOnUiThread(() -> {
                                notifyLibraryChanged();
                            });
                        }
                    });
                }
            }
            
            applyLibraryFilter();
        });
    }
}
