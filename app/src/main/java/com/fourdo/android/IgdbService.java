package com.fourdo.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;

public class IgdbService {
    private static final String TAG = "IgdbService";
    private static final int PLATFORM_3DO_ID = 50;
    private static final String PLATFORM_3DO_NAME = "3DO";
    private static final String GAME_FIELDS = "name, cover.url, summary, first_release_date, screenshots.url, platforms.name, involved_companies.company.name, involved_companies.publisher";
    
    // IGDB API credentials from BuildConfig
    private static final String CLIENT_ID = BuildConfig.IGDB_CLIENT_ID;
    private static final String CLIENT_SECRET = BuildConfig.IGDB_CLIENT_SECRET;
    
    private static IgdbService instance;
    private String accessToken;
    private Context context;
    private ExecutorService executor;
    private Handler mainHandler;
    
    // In-memory cache
    private Map<String, IgdbGame> gameCache = new HashMap<>();
    private Map<Integer, Bitmap> coverCache = new HashMap<>();
    
    public static class IgdbGame {
        public int id;
        public String name;
        public String summary;
        public String coverUrl;
        public List<String> screenshots = new ArrayList<>();
        public List<String> platforms = new ArrayList<>();
        public String releaseDate;
        public String publisher;
    }
    
    public interface GamesCallback {
        void onGamesLoaded(List<IgdbGame> games);
    }
    
    public interface CoverCallback {
        void onCoverLoaded(Bitmap cover, String localPath);
    }
    
    public interface GameCallback {
        void onGameLoaded(IgdbGame game);
    }
    
    private IgdbService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
        loadGameCacheFromDisk();
    }
    
    public static synchronized IgdbService getInstance(Context context) {
        if (instance == null) {
            instance = new IgdbService(context);
        }
        return instance;
    }
    
    private File getGameCacheFile() {
        return new File(context.getCacheDir(), "igdb_game_cache.json");
    }
    
    private void loadGameCacheFromDisk() {
        executor.execute(() -> {
            try {
                File cacheFile = getGameCacheFile();
                if (cacheFile.exists()) {
                    FileInputStream fis = new FileInputStream(cacheFile);
                    byte[] data = new byte[(int) cacheFile.length()];
                    int bytesRead = fis.read(data);
                    fis.close();
                    
                    if (bytesRead > 0) {
                        String json = new String(data, StandardCharsets.UTF_8);
                        JSONObject root = new JSONObject(json);
                        JSONArray arr = root.getJSONArray("games");
                        
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            IgdbGame game = parseGameFromJson(item);
                            String key = game.name.toLowerCase().trim();
                            gameCache.put(key, game);
                        }
                        
                        Log.d(TAG, "Loaded " + gameCache.size() + " games from cache");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load game cache: " + e.getMessage());
            }
        });
    }
    
    private void saveGameCacheToDisk() {
        executor.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (IgdbGame game : gameCache.values()) {
                    arr.put(gameToJson(game));
                }
                
                JSONObject root = new JSONObject();
                root.put("games", arr);
                
                File cacheFile = getGameCacheFile();
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fos.close();
                
                Log.d(TAG, "Saved " + gameCache.size() + " games to cache");
            } catch (Exception e) {
                Log.e(TAG, "Failed to save game cache: " + e.getMessage());
            }
        });
    }
    
    private JSONObject gameToJson(IgdbGame game) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", game.id);
        item.put("name", game.name);
        item.put("summary", game.summary);
        item.put("coverUrl", game.coverUrl);
        item.put("releaseDate", game.releaseDate);
        item.put("publisher", game.publisher);
        
        JSONArray platforms = new JSONArray();
        for (String p : game.platforms) platforms.put(p);
        item.put("platforms", platforms);
        
        return item;
    }
    
    private IgdbGame parseGameFromJson(JSONObject item) throws Exception {
        IgdbGame game = new IgdbGame();
        game.id = item.optInt("id", 0);
        game.name = item.optString("name", "");
        game.summary = item.optString("summary", "");
        game.coverUrl = item.optString("coverUrl", "");
        game.releaseDate = item.optString("releaseDate", "");
        game.publisher = item.optString("publisher", "");
        
        JSONArray platforms = item.optJSONArray("platforms");
        if (platforms != null) {
            for (int i = 0; i < platforms.length(); i++) {
                game.platforms.add(platforms.getString(i));
            }
        }
        
        return game;
    }
    
    public void authenticate(final Runnable onSuccess, final Runnable onError) {
        executor.execute(() -> {
            try {
                String urlStr = "https://id.twitch.tv/oauth2/token?client_id=" + CLIENT_ID +
                        "&client_secret=" + CLIENT_SECRET + "&grant_type=client_credentials";
                
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                
                InputStream is = conn.getInputStream();
                String response = readStream(is);
                is.close();
                
                JSONObject json = new JSONObject(response);
                accessToken = json.getString("access_token");
                
                mainHandler.post(() -> {
                    if (accessToken != null && !accessToken.isEmpty()) {
                        if (onSuccess != null) onSuccess.run();
                    } else {
                        if (onError != null) onError.run();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Auth error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (onError != null) onError.run();
                });
            }
        });
    }
    
    /**
     * Look up a game by name, using cache if available
     */
    public void lookupGame(String gameName, GameCallback callback) {
        String key = gameName.toLowerCase().trim();
        
        // Check in-memory cache first
        IgdbGame cached = gameCache.get(key);
        if (cached != null) {
            Log.d(TAG, "Cache hit for: " + gameName);
            mainHandler.post(() -> callback.onGameLoaded(cached));
            return;
        }
        
        // Search online
        searchGames(gameName, games -> {
            if (!games.isEmpty()) {
                IgdbGame game = games.get(0);
                
                // Cache it
                gameCache.put(key, game);
                saveGameCacheToDisk();
                
                callback.onGameLoaded(game);
            } else {
                callback.onGameLoaded(null);
            }
        });
    }
    
    public void searchGames(String query, GamesCallback callback) {
        executor.execute(() -> {
            try {
                String safeQuery = query == null ? "" : query.trim();
                if (safeQuery.isEmpty()) {
                    mainHandler.post(() -> callback.onGamesLoaded(new ArrayList<>()));
                    return;
                }

                // Check cache first
                String cacheKey = safeQuery.toLowerCase().trim();
                IgdbGame cachedGame = gameCache.get(cacheKey);
                if (cachedGame != null) {
                    List<IgdbGame> result = new ArrayList<>();
                    result.add(cachedGame);
                    Log.d(TAG, "Using cached result for: " + safeQuery);
                    mainHandler.post(() -> callback.onGamesLoaded(result));
                    return;
                }

                ensureAccessToken();
                String escapedQuery = escapeIgdbSearchText(safeQuery);

                String body3do = "search \"" + escapedQuery + "\"; fields " + GAME_FIELDS + "; where platforms = (" + PLATFORM_3DO_ID + "); limit 50;";
                List<IgdbGame> games = parseGames(executeIgdbRequest(body3do));

                if (games.isEmpty()) {
                    String fallbackBody = "search \"" + escapedQuery + "\"; fields " + GAME_FIELDS + "; limit 50;";
                    List<IgdbGame> fallbackGames = parseGames(executeIgdbRequest(fallbackBody));
                    List<IgdbGame> filtered3do = filterLikely3doGames(fallbackGames);
                    games = filtered3do.isEmpty() ? fallbackGames : filtered3do;
                }
                
                // Cache results
                for (IgdbGame game : games) {
                    String key = game.name.toLowerCase().trim();
                    gameCache.put(key, game);
                }
                if (!games.isEmpty()) {
                    saveGameCacheToDisk();
                }
                
                final List<IgdbGame> resultGames = games;
                mainHandler.post(() -> callback.onGamesLoaded(resultGames));
            } catch (Exception e) {
                Log.e(TAG, "Search error: " + e.getMessage());
                mainHandler.post(() -> callback.onGamesLoaded(new ArrayList<>()));
            }
        });
    }

    private void ensureAccessToken() throws Exception {
        if (accessToken != null && !accessToken.isEmpty()) {
            return;
        }

        URL authUrl = new URL("https://id.twitch.tv/oauth2/token?client_id=" + CLIENT_ID +
                "&client_secret=" + CLIENT_SECRET + "&grant_type=client_credentials");
        HttpURLConnection authConn = (HttpURLConnection) authUrl.openConnection();
        authConn.setRequestMethod("POST");
        authConn.setConnectTimeout(10000);
        authConn.setReadTimeout(10000);

        int responseCode = authConn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            String errorBody = readStream(authConn.getErrorStream());
            authConn.disconnect();
            throw new IOException("Auth failed (" + responseCode + "): " + errorBody);
        }

        JSONObject authJson = new JSONObject(readStream(authConn.getInputStream()));
        accessToken = authJson.getString("access_token");
        authConn.disconnect();
    }

    private String executeIgdbRequest(String body) throws Exception {
        URL url = new URL("https://api.igdb.com/v4/games");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Client-ID", CLIENT_ID);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            String errorBody = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("IGDB request failed (" + responseCode + "): " + errorBody + " body=" + body);
        }

        String response = readStream(conn.getInputStream());
        conn.disconnect();
        return response;
    }

    private List<IgdbGame> filterLikely3doGames(List<IgdbGame> games) {
        List<IgdbGame> filtered = new ArrayList<>();
        for (IgdbGame game : games) {
            for (String platformName : game.platforms) {
                if (platformName != null && platformName.toLowerCase().contains(PLATFORM_3DO_NAME.toLowerCase())) {
                    filtered.add(game);
                    break;
                }
            }
        }
        return filtered;
    }

    private String escapeIgdbSearchText(String query) {
        return query.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    
    public void loadCover(String coverUrl, int gameId, CoverCallback callback) {
        // Check in-memory cache
        if (coverCache.containsKey(gameId)) {
            Bitmap cached = coverCache.get(gameId);
            mainHandler.post(() -> callback.onCoverLoaded(cached, null));
            return;
        }
        
        executor.execute(() -> {
            try {
                // Check disk cache first
                File cacheDir = new File(context.getCacheDir(), "covers");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    Log.w(TAG, "Failed to create covers cache directory");
                }
                
                File cachedFile = new File(cacheDir, gameId + "_cover.jpg");
                if (cachedFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                    if (bitmap != null) {
                        coverCache.put(gameId, bitmap);
                        mainHandler.post(() -> callback.onCoverLoaded(bitmap, cachedFile.getAbsolutePath()));
                        return;
                    }
                }
                
                // Download cover
                String finalUrl = coverUrl;
                if (finalUrl.startsWith("//")) finalUrl = "https:" + finalUrl;
                
                // Use cover_big size
                finalUrl = finalUrl.replace("t_thumb", "t_cover_big");
                
                URL url = new URL(finalUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                
                InputStream is = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                
                // Save to cache
                if (bitmap != null) {
                    coverCache.put(gameId, bitmap);
                    FileOutputStream fos = new FileOutputStream(cachedFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                    fos.close();
                }
                
                final String localPath = cachedFile.getAbsolutePath();
                mainHandler.post(() -> callback.onCoverLoaded(bitmap, localPath));
            } catch (Exception e) {
                Log.e(TAG, "Cover load error: " + e.getMessage());
                mainHandler.post(() -> callback.onCoverLoaded(null, null));
            }
        });
    }
    
    private List<IgdbGame> parseGames(String response) {
        List<IgdbGame> games = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(response);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                IgdbGame game = new IgdbGame();
                game.id = item.optInt("id", 0);
                game.name = item.optString("name", "");
                game.summary = item.optString("summary", "");
                
                // Cover
                if (item.has("cover") && !item.isNull("cover")) {
                    JSONObject cover = item.getJSONObject("cover");
                    game.coverUrl = cover.optString("url", "");
                }
                
                // Release date
                if (item.has("first_release_date") && !item.isNull("first_release_date")) {
                    long timestamp = item.optLong("first_release_date", 0) * 1000;
                    if (timestamp > 0) {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy", java.util.Locale.US);
                        game.releaseDate = sdf.format(new java.util.Date(timestamp));
                    }
                }
                
                // Screenshots
                if (item.has("screenshots") && !item.isNull("screenshots")) {
                    JSONArray screen = item.getJSONArray("screenshots");
                    for (int j = 0; j < screen.length(); j++) {
                        String screenUrl = screen.getJSONObject(j).optString("url", "");
                        if (!screenUrl.isEmpty()) {
                            if (screenUrl.startsWith("//")) screenUrl = "https:" + screenUrl;
                            game.screenshots.add(screenUrl);
                        }
                    }
                }

                // Platforms
                if (item.has("platforms") && !item.isNull("platforms")) {
                    JSONArray platforms = item.getJSONArray("platforms");
                    for (int j = 0; j < platforms.length(); j++) {
                        JSONObject platform = platforms.getJSONObject(j);
                        String platformName = platform.optString("name", "");
                        if (!platformName.isEmpty()) {
                            game.platforms.add(platformName);
                        }
                    }
                }
                
                // Publisher
                if (item.has("involved_companies") && !item.isNull("involved_companies")) {
                    JSONArray companies = item.getJSONArray("involved_companies");
                    for (int j = 0; j < companies.length(); j++) {
                        JSONObject company = companies.getJSONObject(j);
                        if (company.optBoolean("publisher", false)) {
                            if (company.has("company") && !company.isNull("company")) {
                                JSONObject comp = company.getJSONObject("company");
                                game.publisher = comp.optString("name", "");
                                break;
                            }
                        }
                    }
                }
                
                games.add(game);
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return games;
    }
    
    private String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
    
    /**
     * Update cache with a new game entry
     */
    public void updateCache(String key, IgdbGame game) {
        if (key != null && !key.isEmpty() && game != null) {
            gameCache.put(key.toLowerCase().trim(), game);
            saveGameCacheToDisk();
        }
    }
    
    /**
     * Clear all caches
     */
    public void clearCache() {
        gameCache.clear();
        coverCache.clear();
        
        executor.execute(() -> {
            // Delete game cache file
            File gameCacheFile = getGameCacheFile();
            if (gameCacheFile.exists()) gameCacheFile.delete();
            
            // Delete cover cache files
            File coverDir = new File(context.getCacheDir(), "covers");
            if (coverDir.exists() && coverDir.isDirectory()) {
                File[] files = coverDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        });
    }
}