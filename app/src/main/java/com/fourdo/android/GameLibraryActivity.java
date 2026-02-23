package com.fourdo.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameLibraryActivity extends AppCompatActivity implements CarouselAdapter.OnGameClickListener {

    private static final String TAG = "GameLibrary";
    private static final Pattern VERSION_SUFFIX_PATTERN = Pattern.compile("(?i)\\bv\\d+(?:\\.\\d+)*\\b");
    private static final int MAX_CONCURRENT_IGDB_REQUESTS = 3;

    public static final String EXTRA_LIBRARY_PATH = "library_path";

    private GridView gameGridView;
    private ViewPager2 carouselView;
    private ProgressBar loadingProgress;
    private TextView statusText;
    private ImageButton settingsButton;
    private ImageButton searchButton;
    private ImageButton viewToggleBtn;

    private final List<GameItem> gameItems = new ArrayList<>();
    private GameGridAdapter adapter;
    private CarouselAdapter carouselAdapter;
    private IgdbService igdbService;
    private String libraryPath;
    
    // View mode
    private int viewStyle = SettingsActivity.VIEW_STYLE_GRID_MEDIUM;
    
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

        adapter = new GameGridAdapter(this, gameItems);
        gameGridView.setAdapter(adapter);
        
        carouselAdapter = new CarouselAdapter(this, gameItems);
        carouselView.setAdapter(carouselAdapter);
        carouselView.setOffscreenPageLimit(3);

        applyViewStyle();

        gameGridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < gameItems.size()) {
                showGameDetails(gameItems.get(position), position);
            }
        });

        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        searchButton.setOnClickListener(v -> showSearchDialog());

        loadGames(root);
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
    }
    
    private void cycleViewStyle() {
        viewStyle = (viewStyle + 1) % 4;
        
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putInt(SettingsActivity.KEY_VIEW_STYLE, viewStyle).apply();
        
        applyViewStyle();
        
        String[] styleNames = {"Small Grid", "Medium Grid", "Large Grid", "Carousel"};
        Toast.makeText(this, "View: " + styleNames[viewStyle], Toast.LENGTH_SHORT).show();
    }
    
    private void applyViewStyle() {
        switch (viewStyle) {
            case SettingsActivity.VIEW_STYLE_GRID_SMALL:
                gameGridView.setNumColumns(6);
                gameGridView.setVisibility(View.VISIBLE);
                carouselView.setVisibility(View.GONE);
                break;
            case SettingsActivity.VIEW_STYLE_GRID_MEDIUM:
                gameGridView.setNumColumns(5);
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
                adapter.notifyDataSetChanged();
                carouselAdapter.notifyDataSetChanged();
                loadingProgress.setVisibility(View.GONE);
                
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
        return name.endsWith(".cue") || name.endsWith(".iso") || name.endsWith(".chd") || name.endsWith(".bin");
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
                                    adapter.notifyDataSetChanged();
                                    carouselAdapter.notifyDataSetChanged();
                                });
                            }
                        });
                    }
                    
                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        carouselAdapter.notifyDataSetChanged();
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

        // Set cover
        if (item.coverBitmap != null) {
            coverView.setImageBitmap(item.coverBitmap);
        } else {
            coverView.setImageResource(R.mipmap.ic_launcher);
        }

        // Set title
        titleView.setText(item.igdbGame != null ? item.igdbGame.name : item.name);
        
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
        if (item.igdbGame != null && item.igdbGame.summary != null && !item.igdbGame.summary.isEmpty()) {
            summaryView.setText(item.igdbGame.summary);
            summaryView.setVisibility(View.VISIBLE);
        } else {
            summaryView.setVisibility(View.GONE);
        }

        builder.setView(view);
        AlertDialog dialog = builder.create();

        // Correct game button
        correctGameButton.setOnClickListener(v -> {
            dialog.dismiss();
            showCorrectGameDialog(item, position);
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
                        adapter.notifyDataSetChanged();
                        carouselAdapter.notifyDataSetChanged();
                    });
                }
            });
        }
        
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            carouselAdapter.notifyDataSetChanged();
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
                                adapter.notifyDataSetChanged();
                                carouselAdapter.notifyDataSetChanged();
                            });
                        }
                    });
                }
            }
            
            adapter.notifyDataSetChanged();
            carouselAdapter.notifyDataSetChanged();
        });
    }
}