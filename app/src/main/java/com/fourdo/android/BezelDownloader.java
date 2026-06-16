package com.fourdo.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class BezelDownloader {
    static final String DEFAULT_GITHUB_ZIP_URL =
            "https://github.com/thebezelproject/bezelprojectsa-3DO/archive/refs/heads/master.zip";

    private static final int BUFFER_SIZE = 64 * 1024;

    private BezelDownloader() {
    }

    interface ProgressCallback {
        void onProgress(String phase, long current, long total, boolean indeterminate);
    }

    static Result downloadGithubZip(Context context, String urlString) throws IOException {
        return downloadGithubZip(context, urlString, null);
    }

    static Result downloadGithubZip(Context context, String urlString, ProgressCallback progressCallback) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IOException("Missing GitHub ZIP URL");
        }

        File appStorage = SafFileImporter.getManagedAppRoot(context);

        File root = new File(appStorage, "bezels/github");
        File destination = new File(root, "latest");
        File tempZip = new File(root, "bezel-download.zip");

        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Could not create bezel folder");
        }

        long bytes = downloadToFile(urlString.trim(), tempZip, 0, progressCallback);
        notifyProgress(progressCallback, "Preparing extraction...", 0, 0, true);
        clearDirectory(destination);
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Could not create destination folder");
        }

        int pngCount;
        try {
            int totalPngs = countPngs(tempZip);
            notifyProgress(progressCallback, "Extracting bezels...", 0, totalPngs, totalPngs <= 0);
            pngCount = extractPngs(tempZip, destination, totalPngs, progressCallback);
        } finally {
            if (tempZip.exists() && !tempZip.delete()) {
                tempZip.deleteOnExit();
            }
        }

        if (pngCount == 0) {
            throw new IOException("No PNG bezels found in archive");
        }

        return new Result(destination, pngCount, bytes);
    }

    private static long downloadToFile(String urlString, File output, int redirects, ProgressCallback progressCallback) throws IOException {
        if (redirects > 5) {
            throw new IOException("Too many redirects");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "3DOOpera-Android");

        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("Redirect without Location header");
            }
            return downloadToFile(new URL(new URL(urlString), location).toString(), output, redirects + 1, progressCallback);
        }

        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + status);
        }

        long total = 0;
        long expected = connection.getContentLengthLong();
        long nextProgress = 0;
        notifyProgress(progressCallback, "Downloading bezel archive...", 0, expected, expected <= 0);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = connection.getInputStream();
             FileOutputStream fileOutput = new FileOutputStream(output)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileOutput.write(buffer, 0, read);
                total += read;
                if (expected <= 0 || total >= nextProgress) {
                    notifyProgress(progressCallback, "Downloading bezel archive...", total, expected, expected <= 0);
                    nextProgress = total + (512 * 1024);
                }
            }
        } finally {
            connection.disconnect();
        }
        notifyProgress(progressCallback, "Download complete", total, expected, expected <= 0);
        return total;
    }

    private static int countPngs(File zipFile) throws IOException {
        int count = 0;
        try (ZipInputStream zipInput = new ZipInputStream(new java.io.FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String entryName = stripArchiveRoot(entry.getName());
                    if (!entryName.isEmpty() && entryName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int extractPngs(File zipFile, File destination, int totalPngs, ProgressCallback progressCallback) throws IOException {
        int count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zipInput = new ZipInputStream(new java.io.FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = stripArchiveRoot(entry.getName());
                if (entryName.isEmpty() || !entryName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    continue;
                }

                File output = resolveInside(destination, entryName);
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create folder: " + parent.getAbsolutePath());
                }

                try (FileOutputStream fileOutput = new FileOutputStream(output)) {
                    int read;
                    while ((read = zipInput.read(buffer)) != -1) {
                        fileOutput.write(buffer, 0, read);
                    }
                }
                count++;
                notifyProgress(progressCallback, "Extracting bezels...", count, totalPngs, totalPngs <= 0);
            }
        }

        return count;
    }

    private static void notifyProgress(ProgressCallback callback, String phase, long current, long total, boolean indeterminate) {
        if (callback != null) {
            callback.onProgress(phase, current, total, indeterminate);
        }
    }

    private static String stripArchiveRoot(String entryName) {
        if (entryName == null) {
            return "";
        }
        String normalized = entryName.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static File resolveInside(File root, String entryName) throws IOException {
        File output = new File(root, entryName);
        String rootPath = root.getCanonicalPath();
        String outputPath = output.getCanonicalPath();
        if (!outputPath.equals(rootPath) && !outputPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Archive entry outside destination: " + entryName);
        }
        return output;
    }

    private static void clearDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }

        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            deleteRecursively(child);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (file.exists() && !file.delete()) {
            throw new IOException("Could not remove old bezel file: " + file.getAbsolutePath());
        }
    }

    static final class Result {
        final File destination;
        final int pngCount;
        final long bytes;

        Result(File destination, int pngCount, long bytes) {
            this.destination = destination;
            this.pngCount = pngCount;
            this.bytes = bytes;
        }
    }
}
