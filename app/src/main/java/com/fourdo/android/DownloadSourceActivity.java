package com.fourdo.android;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadSourceActivity extends AppCompatActivity {

    private static final String TAG = "DownloadSource";
    private static final String ARCHIVE_BASE_URL = "https://archive.org/download/chd_3do/CHD-3DO/";

    private TextView statusText;
    private EditText searchEditText;
    private RecyclerView gameRecyclerView;
    private Button refreshButton;
    private ImageButton backButton;
    private ProgressBar loadingProgress;

    private final List<LolRomGame> gameList = new ArrayList<>();
    private final List<LolRomGame> allGames = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    private GameDownloadAdapter adapter;
    private DownloadService downloadService;
    private IgdbService igdbService;
    private volatile boolean downloadInProgress = false;

    public static class LolRomGame {
        String title;
        String pageUrl;
        String downloadUrl;
        String fileSize;
        String region;

        LolRomGame(String title, String pageUrl, String downloadUrl, String fileSize, String region) {
            this.title = title;
            this.pageUrl = pageUrl;
            this.downloadUrl = downloadUrl;
            this.fileSize = fileSize;
            this.region = region;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_source);

        DeviceOrientationManager.setLandscapeOrientation(this);

        statusText = findViewById(R.id.status_text);
        searchEditText = findViewById(R.id.search_edit);
        gameRecyclerView = findViewById(R.id.game_list);
        refreshButton = findViewById(R.id.refresh_button);
        backButton = findViewById(R.id.back_button);
        loadingProgress = findViewById(R.id.loading_progress);

        igdbService = IgdbService.getInstance(this);
        downloadService = DownloadService.getInstance(this);

        adapter = new GameDownloadAdapter(this, gameList, igdbService);
        adapter.setOnGameClickListener((game, igdbInfo) -> downloadGame(game));
        gameRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        gameRecyclerView.setAdapter(adapter);

        refreshButton.setOnClickListener(v -> loadGames());
        backButton.setOnClickListener(v -> finish());
        searchEditText.setOnEditorActionListener((TextView view, int actionId, KeyEvent event) -> {
            boolean searchAction = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE;
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (searchAction || enterKey) {
                searchGames(searchEditText.getText().toString());
                return true;
            }
            return false;
        });

        loadGames();
    }

    private void loadGames() {
        loadingProgress.setVisibility(ProgressBar.VISIBLE);
        statusText.setText("Loading Archive.org 3DO CHDs...");

        executor.execute(() -> {
            try {
                Document doc = Jsoup.connect(ARCHIVE_BASE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) 4DO-Android/2.0")
                    .timeout(30000)
                    .get();

                Elements rows = doc.select("table.directory-listing-table tr");
                List<LolRomGame> games = new ArrayList<>();

                for (Element row : rows) {
                    Element link = row.selectFirst("td a[href$=.chd]");
                    if (link == null) {
                        continue;
                    }

                    String href = link.attr("href");
                    String downloadUrl = link.absUrl("href");
                    if (downloadUrl == null || downloadUrl.isEmpty()) {
                        downloadUrl = ARCHIVE_BASE_URL + href;
                    }

                    String visibleName = link.text().trim();
                    String title = normalizeTitle(visibleName, href);
                    Elements cells = row.select("td");
                    String fileSize = cells.size() > 2 ? cells.get(2).text().trim() : "";
                    String region = extractRegion(title);
                    games.add(new LolRomGame(title, downloadUrl, downloadUrl, fileSize, region));
                }

                mainHandler.post(() -> {
                    allGames.clear();
                    allGames.addAll(games);
                    gameList.clear();
                    gameList.addAll(games);
                    adapter.notifyDataSetChanged();
                    loadingProgress.setVisibility(ProgressBar.GONE);
                    statusText.setText(games.size() + " CHDs available");
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading archive listing", e);
                mainHandler.post(() -> {
                    loadingProgress.setVisibility(ProgressBar.GONE);
                    statusText.setText("Failed to load Archive.org listing");
                });
            }
        });
    }

    private void searchGames(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        gameList.clear();

        if (normalizedQuery.isEmpty()) {
            gameList.addAll(allGames);
        } else {
            for (LolRomGame game : allGames) {
                if (game.title.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    gameList.add(game);
                }
            }
        }

        adapter.notifyDataSetChanged();
        statusText.setText(gameList.size() + " results");
    }

    private void downloadGame(LolRomGame game) {
        if (downloadInProgress) {
            statusText.setText("A download is already in progress");
            return;
        }

        downloadInProgress = true;
        loadingProgress.setVisibility(ProgressBar.VISIBLE);
        statusText.setText("Starting download: " + game.title);

        String fileName = buildDownloadFileName(game);
        downloadService.downloadFile(game.downloadUrl, fileName, downloadService.getPreferredDownloadsDir(), new DownloadService.DownloadCallback() {
            @Override
            public void onProgress(int progress) {
                statusText.setText("Downloading " + game.title + " (" + progress + "%)");
            }

            @Override
            public void onComplete(String filePath) {
                downloadInProgress = false;
                loadingProgress.setVisibility(ProgressBar.GONE);
                markLibraryRefreshRequired();
                statusText.setText("Downloaded: " + game.title);
            }

            @Override
            public void onError(String error) {
                downloadInProgress = false;
                loadingProgress.setVisibility(ProgressBar.GONE);
                statusText.setText("Download failed: " + error);
            }
        });
    }

    private void markLibraryRefreshRequired() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(SettingsActivity.KEY_LIBRARY_REFRESH_REQUIRED, true).apply();
    }

    private String buildDownloadFileName(LolRomGame game) {
        String fileName = game.downloadUrl.substring(game.downloadUrl.lastIndexOf('/') + 1);
        try {
            fileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode download file name", e);
        }

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".chd")) {
            fileName = game.title + ".chd";
        }
        return fileName;
    }

    private String normalizeTitle(String visibleName, String href) {
        String title = visibleName != null && !visibleName.isEmpty() ? visibleName : href;
        try {
            title = URLDecoder.decode(title, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode title", e);
        }
        return title.replaceFirst("(?i)\\.chd$", "").trim();
    }

    private String extractRegion(String title) {
        Matcher matcher = Pattern.compile("\\(([^)]+)\\)").matcher(title);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.equalsIgnoreCase("USA") || value.equalsIgnoreCase("Europe") || value.equalsIgnoreCase("Japan") || value.equalsIgnoreCase("World")) {
                return value;
            }
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            executor.shutdownNow();
        }
    }
}
