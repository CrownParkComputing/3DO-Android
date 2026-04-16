package com.fourdo.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService {
    private static final String TAG = "DownloadService";
    private static DownloadService instance;
    private Context context;
    private ExecutorService executor;
    private Handler mainHandler;
    
    public interface DownloadCallback {
        void onProgress(int progress);
        void onComplete(String filePath);
        void onError(String error);
    }
    
    private DownloadService(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static synchronized DownloadService getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadService(context);
        }
        return instance;
    }
    
    public File getDownloadsDir() {
        File dir = new File(context.getExternalFilesDir(null), "ROMs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    
    public void downloadFile(String url, String fileName, DownloadCallback callback) {
        executor.execute(() -> {
            try {
                File destFile = new File(getDownloadsDir(), fileName);
                URL downloadUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                
                int fileSize = conn.getContentLength();
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(destFile);
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytesRead = 0;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        final int p = progress;
                        mainHandler.post(() -> callback.onProgress(p));
                    }
                }
                
                fos.close();
                is.close();
                conn.disconnect();
                
                mainHandler.post(() -> callback.onComplete(destFile.getAbsolutePath()));
            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    public List<String> getDownloadedRoms() {
        List<String> roms = new ArrayList<>();
        File dir = getDownloadsDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".iso") || 
                                                    name.toLowerCase().endsWith(".bin") ||
                                                    name.toLowerCase().endsWith(".cue"));
        if (files != null) {
            for (File f : files) {
                roms.add(f.getAbsolutePath());
            }
        }
        return roms;
    }
}