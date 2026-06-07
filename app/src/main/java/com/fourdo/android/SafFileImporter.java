package com.fourdo.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class SafFileImporter {

    private static final String BIOS_DIR_NAME = "bios";
    private static final String LIBRARY_DIR_NAME = "library";
    private static final String DRIVER_DIR_NAME = "drivers";

    private SafFileImporter() {
    }

    static final class ImportResult {
        final String path;
        final int importedFileCount;

        ImportResult(String path, int importedFileCount) {
            this.path = path;
            this.importedFileCount = importedFileCount;
        }
    }

    static Intent createOpenDocumentIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    static Intent createOpenDocumentTreeIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        return intent;
    }

    static String importVulkanDriver(Context context, Uri uri) throws IOException {
        takePersistableReadPermission(context, uri);

        String displayName = getDisplayName(context, uri);
        String lowerName = displayName.toLowerCase();
        
        File driverDir = new File(context.getFilesDir(), DRIVER_DIR_NAME);
        if (!driverDir.exists() && !driverDir.mkdirs()) {
            throw new IOException("Failed to create drivers directory");
        }

        if (lowerName.endsWith(".so")) {
            File destFile = new File(driverDir, sanitizeName(displayName));
            copyUriToFile(context, uri, destFile);
            return destFile.getAbsolutePath();
        } else if (lowerName.endsWith(".zip")) {
            // Extract .so from ZIP
            android.util.Log.d("SafFileImporter", "Attempting to extract driver from ZIP: " + displayName);
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(is)) {
                
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    android.util.Log.d("SafFileImporter", "ZIP entry: " + entry.getName());
                    if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".so")) {
                        // Found a driver, extract it
                        String fileName = new File(entry.getName()).getName();
                        File destFile = new File(driverDir, sanitizeName(fileName));
                        android.util.Log.d("SafFileImporter", "Extracting driver to: " + destFile.getAbsolutePath());
                        
                        try (FileOutputStream fos = new FileOutputStream(destFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, len);
                            }
                        }
                        zis.closeEntry();
                        return destFile.getAbsolutePath();
                    }
                    zis.closeEntry();
                }
            } catch (Exception e) {
                android.util.Log.e("SafFileImporter", "ZIP extraction failed", e);
                throw new IOException("ZIP extraction failed: " + e.getMessage());
            }
            throw new IOException("No .so driver file found in ZIP");
        } else {
            throw new IOException("Selected file is not a Vulkan driver (.so) or ZIP containing one");
        }
    }

    static String importBios(Context context, Uri uri) throws IOException {
        takePersistableReadPermission(context, uri);

        String displayName = getDisplayName(context, uri);
        if (!isBiosFileName(displayName)) {
            throw new IOException("Selected file is not a BIOS image");
        }

        File biosDir = new File(context.getFilesDir(), BIOS_DIR_NAME);
        if (!biosDir.exists() && !biosDir.mkdirs()) {
            throw new IOException("Failed to create BIOS directory");
        }

        File destFile = new File(biosDir, sanitizeName(displayName));
        copyUriToFile(context, uri, destFile);
        return destFile.getAbsolutePath();
    }

    static String importGameFile(Context context, Uri uri) throws IOException {
        takePersistableReadPermission(context, uri);

        String displayName = getDisplayName(context, uri);
        if (!isSupportedLibraryFileName(displayName)) {
            throw new IOException("Selected file is not a supported game image or archive");
        }

        File libraryDir = getManagedLibraryDirectory(context);
        if (!libraryDir.exists() && !libraryDir.mkdirs()) {
            throw new IOException("Failed to create library directory");
        }

        File destFile = new File(libraryDir, sanitizeName(displayName));
        copyUriToFile(context, uri, destFile);
        return destFile.getAbsolutePath();
    }

    static ImportResult importLibraryTree(Context context, Uri uri) throws IOException {
        takePersistableReadPermission(context, uri);

        DocumentFile tree = DocumentFile.fromTreeUri(context, uri);
        if (tree == null || !tree.isDirectory()) {
            throw new IOException("Selected location is not a readable folder");
        }

        String treeName = tree.getName();
        if (treeName == null || treeName.trim().isEmpty()) {
            treeName = "Imported Library";
        }

        File libraryRoot = getManagedLibraryRoot(context);
        if (!libraryRoot.exists() && !libraryRoot.mkdirs()) {
            throw new IOException("Failed to create managed library root");
        }

        File destinationDir = new File(libraryRoot, sanitizeName(treeName));
        int importedCount = copySupportedTreeFiles(context, tree, destinationDir);
        if (importedCount == 0) {
            throw new IOException("No supported game files or archives were found in the selected folder");
        }

        return new ImportResult(destinationDir.getAbsolutePath(), importedCount);
    }

    static File getManagedLibraryDirectory(Context context) {
        SharedStorageRoot sharedStorageRoot = getSharedStorageRoot(context);
        return new File(sharedStorageRoot.root, LIBRARY_DIR_NAME);
    }

    private static File getManagedLibraryRoot(Context context) {
        return getManagedLibraryDirectory(context);
    }

    private static SharedStorageRoot getSharedStorageRoot(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) {
            root = context.getFilesDir();
        }
        return new SharedStorageRoot(root);
    }

    private static int copySupportedTreeFiles(Context context, DocumentFile sourceDir, File destDir) throws IOException {
        DocumentFile[] children = sourceDir.listFiles();
        int importedCount = 0;

        for (DocumentFile child : children) {
            String childName = child.getName();
            if (childName == null || childName.startsWith(".")) {
                continue;
            }

            if (child.isDirectory()) {
                File childDest = new File(destDir, sanitizeName(childName));
                importedCount += copySupportedTreeFiles(context, child, childDest);
                continue;
            }

            if (!child.isFile() || !isSupportedLibraryFileName(childName)) {
                continue;
            }

            if (!destDir.exists() && !destDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + destDir.getAbsolutePath());
            }

            File destFile = new File(destDir, sanitizeName(childName));
            copyUriToFile(context, child.getUri(), destFile);
            importedCount++;
        }

        return importedCount;
    }

    private static void copyUriToFile(Context context, Uri uri, File destFile) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        if (parent != null) {
            File canonicalParent = parent.getCanonicalFile();
            File canonicalDest = destFile.getCanonicalFile();
            String parentPath = canonicalParent.getPath() + File.separator;
            if (!canonicalDest.getPath().startsWith(parentPath)) {
                throw new IOException("Invalid destination file path");
            }
        }

        try (InputStream inputStream = resolver.openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(destFile)) {
            if (inputStream == null) {
                throw new IOException("Could not open selected file");
            }

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    private static String getDisplayName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (android.database.Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String displayName = cursor.getString(nameIndex);
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        return displayName;
                    }
                }
            }
        }

        String fallback = uri.getLastPathSegment();
        if (fallback == null || fallback.trim().isEmpty()) {
            fallback = "imported-file";
        }
        return fallback;
    }

    private static void takePersistableReadPermission(Context context, Uri uri) {
        try {
            context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private static boolean isBiosFileName(String name) {
        String lower = safeLowerName(name);
        return lower.endsWith(".bin") || lower.endsWith(".rom");
    }

    private static boolean isSupportedLibraryFileName(String name) {
        String lower = safeLowerName(name);
        return lower.endsWith(".cue")
                || lower.endsWith(".iso")
                || lower.endsWith(".chd")
                || lower.endsWith(".bin")
                || lower.endsWith(".img")
                || lower.endsWith(".sub")
                || lower.endsWith(".ccd")
                || lower.endsWith(".mdf")
                || lower.endsWith(".mds")
                || lower.endsWith(".wav")
                || lower.endsWith(".wv")
                || lower.endsWith(".zip")
                || lower.endsWith(".7z")
                || lower.endsWith(".rar");
    }

    private static String safeLowerName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String sanitizeName(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.contains("..") || raw.contains("/") || raw.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name");
        }
        String sanitized = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "imported" : sanitized;
    }

    private static final class SharedStorageRoot {
        final File root;

        SharedStorageRoot(File root) {
            this.root = root;
        }
    }
}
