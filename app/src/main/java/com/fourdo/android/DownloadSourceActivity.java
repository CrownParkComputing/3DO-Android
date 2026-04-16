package com.fourdo.android;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadSourceActivity extends AppCompatActivity {

    private static final String TAG = "DownloadSource";
    
    private static final String LOLROMS_BASE_URL = "https://lolroms.com/Panasonic%20-%203DO";
    
    private TextView statusText;
    private RecyclerView gameRecyclerView;
    private Button searchButton;
    private Button refreshButton;
    private Button backButton;
    private EditText searchEditText;
    private ProgressBar loadingProgress;
    
    private List<LolRomGame> gameList = new ArrayList<>();
    private GameDownloadAdapter adapter;
    private ExecutorService downloadExecutor = Executors.newFixedThreadPool(3);
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private IgdbService igdbService;
    
    public static class LolRomGame {
        String title;
        String pageUrl;
        String downloadUrl;
        String fileSize;
        String region;
        
        LolRomGame(String title, String pageUrl) {
            this.title = title;
            this.pageUrl = pageUrl;
        }
        
        LolRomGame(String title, String pageUrl, String region) {
            this.title = title;
            this.pageUrl = pageUrl;
            this.region = region;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_source);
        
        DeviceOrientationManager.setLandscapeOrientation(this);
        
        statusText = findViewById(R.id.status_text);
        gameRecyclerView = findViewById(R.id.game_list);
        searchButton = findViewById(R.id.search_button);
        refreshButton = findViewById(R.id.refresh_button);
        backButton = findViewById(R.id.back_button);
        searchEditText = findViewById(R.id.search_edit);
        loadingProgress = findViewById(R.id.loading_progress);
        
        igdbService = new IgdbService(this);
        
        adapter = new GameDownloadAdapter(this, gameList, igdbService);
        gameRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        gameRecyclerView.setAdapter(adapter);
        
        adapter.setOnGameClickListener((game, igdbInfo) -> {
            if (game != null) {
                downloadGame(game);
            }
        });
        
        searchButton.setOnClickListener(v -> {
            String query = searchEditText.getText().toString().trim();
            if (!query.isEmpty()) {
                searchGames(query);
            } else {
                loadGames();
            }
        });
        
        refreshButton.setOnClickListener(v -> loadGames());
        
        backButton.setOnClickListener(v -> finish());
        
        loadGames();
    }
    
    private void loadGames() {
        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setText("Loading games from lolroms.com...");
        
        downloadExecutor.execute(() -> {
            try {
                List<LolRomGame> games = new ArrayList<>();
                
                Document doc = Jsoup.connect(LOLROMS_BASE_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(30000)
                    .get();
                
                Elements gameLinks = doc.select("a[href*=/rom/]");
                
                Log.d(TAG, "Found " + gameLinks.size() + " game links on main page");
                
                for (Element link : gameLinks) {
                    String href = link.attr("href");
                    String title = link.text().trim();
                    
                    if (title.isEmpty() || title.contains("...")) {
                        continue;
                    }
                    
                    if (href.contains("page=") || href.contains("search=")) {
                        continue;
                    }
                    
                    String region = extractRegion(title);
                    games.add(new LolRomGame(title, href, region));
                }
                
                mainHandler.post(() -> {
                    gameList.clear();
                    gameList.addAll(games);
                    adapter.notifyDataSetChanged();
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setText(games.size() + " games found");
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading games: " + e.getMessage());
                mainHandler.post(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setText("Error: " + e.getMessage());
                });
            }
        });
    }
    
    private String extractRegion(String title) {
        if (title.contains("[U]")) return "USA";
        if (title.contains("[E]")) return "Europe";
        if (title.contains("[J]")) return "Japan";
        if (title.contains("[W]")) return "World";
        if (title.contains("(USA)")) return "USA";
        if (title.contains("(Europe)")) return "Europe";
        if (title.contains("(Japan)")) return "Japan";
        return "";
    }
    
    private void searchGames(String query) {
        loadingProgress.setVisibility(View.VISIBLE);
        statusText.setText("Searching for: " + query);
        
        downloadExecutor.execute(() -> {
            try {
                List<LolRomGame> games = new ArrayList<>();
                
                String searchUrl = "https://lolroms.com/search?q=" + query.replace(" ", "+") + "+Panasonic+3DO";
                
                Document doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(30000)
                    .get();
                
                Elements gameLinks = doc.select("a[href*=/rom/]");
                
                for (Element link : gameLinks) {
                    String href = link.attr("href");
                    String title = link.text().trim();
                    
                    if (title.isEmpty()) continue;
                    
                    String region = extractRegion(title);
                    games.add(new LolRomGame(title, href, region));
                }
                
                mainHandler.post(() -> {
                    gameList.clear();
                    gameList.addAll(games);
                    adapter.notifyDataSetChanged();
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setText(games.size() + " results for: " + query);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error searching: " + e.getMessage());
                mainHandler.post(() -> {
                    loadingProgress.setVisibility(View.GONE);
                    statusText.setText("Error: " + e.getMessage());
                });
            }
        });
    }
    
    private void downloadGame(LolRomGame game) {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("Downloading");
        dialog.setMessage(game.title + "\n\nConnecting to server...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100);
        dialog.setCancelable(true);
        dialog.show();
        
        downloadExecutor.execute(() -> {
            try {
                mainHandler.post(() -> {
                    dialog.setMessage(game.title + "\n\nFetching download page...");
                });
                
                Document page = Jsoup.connect(game.pageUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(30000)
                    .get();
                
                String downloadUrl = findDownloadUrl(page, game.pageUrl);
                
                if (downloadUrl == null) {
                    Elements downloadLinks = page.select("a[href*=.zip], a[href*=download]");
                    for (Element link : downloadLinks) {
                        String href = link.attr("href");
                        if (href.startsWith("http")) {
                            downloadUrl = href;
                            break;
                        }
                    }
                }
                
                if (downloadUrl == null) {
                    Element refresh = page.selectFirst("meta[http-equiv=refresh]");
                    if (refresh != null) {
                        String content = refresh.attr("content");
                        Matcher m = Pattern.compile("url=([^;]+)").matcher(content);
                        if (m.find()) {
                            downloadUrl = m.group(1);
                        }
                    }
                }
                
                if (downloadUrl == null) {
                    throw new Exception("Could not find download link");
                }
                
                String libraryPath = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                    .getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
                
                if (libraryPath.isEmpty()) {
                    libraryPath = getExternalFilesDir(null).getAbsolutePath() + "/roms";
                }
                
                File downloadDir = new File(libraryPath);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                
                String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                if (!fileName.contains(".")) {
                    fileName += ".zip";
                }
                
                File outputFile = new File(downloadDir, fileName);
                
                mainHandler.post(() -> {
                    dialog.setMessage(game.title + "\n\nDownloading to:\n" + outputFile.getAbsolutePath());
                });
                
                downloadFile(downloadUrl, outputFile, dialog, game.title);
                
                mainHandler.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "Download complete: " + fileName, Toast.LENGTH_LONG).show();
                    statusText.setText("Downloaded: " + game.title);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                mainHandler.post(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private String findDownloadUrl(Document page, String baseUrl) {
        Elements downloadElements = page.select("a.download, button.download, .download-btn, a[download]");
        
        for (Element el : downloadElements) {
            String href = el.attr("href");
            if (href.startsWith("http") && (href.contains(".zip") || href.contains("file") || href.contains("download"))) {
                return href;
            }
            
            String dataHref = el.attr("data-href");
            if (!dataHref.isEmpty() && dataHref.startsWith("http")) {
                return dataHref;
            }
        }
        
        Element meta = page.selectFirst("meta[http-equiv=refresh]");
        if (meta != null) {
            String content = meta.attr("content");
            if (content.toLowerCase().contains("url=")) {
                int urlStart = content.toLowerCase().indexOf("url=") + 4;
                String url = content.substring(urlStart).split("[;,]")[0].trim();
                if (url.startsWith("http")) {
                    return url;
                }
            }
        }
        
        Elements scripts = page.select("script");
        for (Element script : scripts) {
            String text = script.html();
            if (text.contains("window.location") || text.contains("window.location.href")) {
                Pattern p = Pattern.compile("(window\\.location[^=]*=[\"'])([^\"']+)");
                Matcher m = p.matcher(text);
                if (m.find()) {
                    return m.group(2);
                }
            }
        }
        
        return null;
    }
    
    private void downloadFile(String urlStr, File outputFile, ProgressDialog dialog, String gameTitle) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://lolroms.com/");
        conn.connect();
        
        int fileLength = conn.getContentLength();
        dialog.setMax(fileLength > 0 ? fileLength : 100);
        
        try (InputStream input = new BufferedInputStream(conn.getInputStream());
             OutputStream output = new FileOutputStream(outputFile)) {
            
            byte[] buffer = new byte[8192];
            int count;
            long downloaded = 0;
            
            while ((count = input.read(buffer)) != -1) {
                if (!dialog.isShowing()) {
                    outputFile.delete();
                    return;
                }
                
                output.write(buffer, 0, count);
                downloaded += count;
                
                if (fileLength > 0) {
                    final long finalDownloaded = downloaded;
                    mainHandler.post(() -> {
                        dialog.setProgress((int) finalDownloaded);
                        int percent = (int) (finalDownloaded * 100 / fileLength);
                        dialog.setMessage(gameTitle + "\n\n" + percent + "% complete");
                    });
                }
            }
        }
        
        conn.disconnect();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloadExecutor.shutdown();
        if (igdbService != null) {
            igdbService.cleanup();
        }
    }
}