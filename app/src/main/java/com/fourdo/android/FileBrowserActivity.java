package com.fourdo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FileBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_SELECT_FOLDER_MODE = "select_folder_mode";
    public static final String EXTRA_FILE_PATH = "file_path";

    private ListView fileListView;
    private TextView currentPathTextView;
    private Button backButton;
    private Button selectFolderButton;
    private List<String> fileList;
    private List<String> filePathList;
    private File currentDir;
    private boolean selectFolderMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        fileListView = findViewById(R.id.file_list);
        currentPathTextView = findViewById(R.id.current_path);
        backButton = findViewById(R.id.back_button);
        selectFolderButton = findViewById(R.id.select_folder_button);

        selectFolderMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SELECT_FOLDER_MODE, false);
        selectFolderButton.setVisibility(selectFolderMode ? View.VISIBLE : View.GONE);
        selectFolderButton.setOnClickListener(v -> {
            if (currentDir != null) {
                Intent result = new Intent();
                result.putExtra(EXTRA_FILE_PATH, currentDir.getAbsolutePath());
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        });

        // Show drives directly on startup
        showDrivesList();
        
        // Handle back button
        backButton.setOnClickListener(v -> onBackPressed());
    }

    private void showDrivesList() {
        List<StorageVolume> volumes = getAllStorageVolumes();
        
        if (volumes.isEmpty()) {
            // Fallback to default storage
            currentDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath());
            loadFileList(currentDir);
            return;
        }

        // Build drive list for display
        fileList = new ArrayList<>();
        filePathList = new ArrayList<>();
        
        // Add drive icons/names
        for (StorageVolume vol : volumes) {
            fileList.add("[Drive] " + vol.name);
            filePathList.add(vol.path);
        }
        
        // Sort alphabetically
        Collections.sort(fileList, String::compareToIgnoreCase);
        
        // Rebuild paths to match sorted names
        List<String> sortedPaths = new ArrayList<>();
        for (String name : fileList) {
            for (StorageVolume vol : volumes) {
                if (name.equals("[Drive] " + vol.name)) {
                    sortedPaths.add(vol.path);
                    break;
                }
            }
        }
        filePathList = sortedPaths;
        
        currentPathTextView.setText("Drives");
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, fileList);
        fileListView.setAdapter(adapter);
        
        // Handle click to enter drive
        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < filePathList.size()) {
                String path = filePathList.get(position);
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    currentDir = dir;
                    loadFileList(currentDir);
                }
            }
        });
    }

    private List<StorageVolume> getAllStorageVolumes() {
        List<StorageVolume> volumes = new ArrayList<>();

        // Add primary external storage
        File primaryExt = Environment.getExternalStorageDirectory();
        if (primaryExt != null && primaryExt.exists()) {
            volumes.add(new StorageVolume("Internal Storage", primaryExt.getAbsolutePath()));
        }

        // Check for additional storage locations
        File storageDir = new File("/storage");
        if (storageDir.exists() && storageDir.isDirectory()) {
            File[] storageFiles = storageDir.listFiles();
            if (storageFiles != null) {
                for (File f : storageFiles) {
                    if (f.isDirectory() && !f.getName().equals("emulated") && !f.getName().equals("self")) {
                        File[] contents = f.listFiles();
                        if (contents != null && contents.length > 0) {
                            String name = f.getName();
                            // Make name more user-friendly
                            if (name.equals("0000-0000")) {
                                name = "SD Card";
                            } else if (name.toLowerCase().contains("usb")) {
                                name = "USB Drive";
                            }
                            volumes.add(new StorageVolume(name, f.getAbsolutePath()));
                        }
                    }
                }
            }
        }

        // Also check /mnt for other storage
        File[] mountPoints = {
            new File("/mnt/extSdCard"),
            new File("/mnt/sdcard"),
            new File("/mnt/media_rw")
        };
        for (File mp : mountPoints) {
            if (mp.exists() && mp.isDirectory() && mp.canRead()) {
                boolean found = false;
                for (StorageVolume sv : volumes) {
                    if (sv.path.equals(mp.getAbsolutePath())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    String name = mp.getName();
                    if (name.toLowerCase().contains("sd")) {
                        name = "SD Card";
                    } else if (name.toLowerCase().contains("usb")) {
                        name = "USB Drive";
                    }
                    volumes.add(new StorageVolume(name, mp.getAbsolutePath()));
                }
            }
        }

        // Sort volumes: Internal first, then SD, then USB
        Collections.sort(volumes, (a, b) -> {
            if (a.name.equals("Internal Storage")) return -1;
            if (b.name.equals("Internal Storage")) return 1;
            if (a.name.equals("SD Card") && !b.name.equals("Internal Storage")) return -1;
            if (b.name.equals("SD Card") && !a.name.equals("Internal Storage")) return 1;
            return a.name.compareTo(b.name);
        });

        return volumes;
    }

    private static class StorageVolume {
        String name;
        String path;
        StorageVolume(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    private void loadFileList(File dir) {
        // Display user-friendly path
        String displayPath = getDisplayPath(dir);
        currentPathTextView.setText(displayPath);

        File[] files = dir.listFiles();
        fileList = new ArrayList<>();
        filePathList = new ArrayList<>();

        if (files != null) {
            // Sort: folders first, then files, both alphabetically
            List<File> sortedFiles = new ArrayList<>();
            for (File file : files) {
                sortedFiles.add(file);
            }
            Collections.sort(sortedFiles, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() && !b.isDirectory()) {
                        return -1;
                    }
                    if (!a.isDirectory() && b.isDirectory()) {
                        return 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            
            for (File file : sortedFiles) {
                String displayName = file.getName();
                if (file.isDirectory()) {
                    displayName = "[Folder] " + file.getName();
                }
                fileList.add(displayName);
                filePathList.add(file.getAbsolutePath());
            }
        }

        if (fileList.isEmpty()) {
            fileList.add("(empty or no access)");
            filePathList.add("");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, fileList);
        fileListView.setAdapter(adapter);
        
        // Handle clicks
        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < filePathList.size() && !filePathList.get(position).isEmpty()) {
                File selected = new File(filePathList.get(position));
                if (selected.isDirectory()) {
                    currentDir = selected;
                    loadFileList(currentDir);
                } else {
                    // File selected - return result
                    Intent result = new Intent();
                    result.putExtra(EXTRA_FILE_PATH, selected.getAbsolutePath());
                    setResult(Activity.RESULT_OK, result);
                    finish();
                }
            }
        });
    }
    
    @Override
    public void onBackPressed() {
        if (currentDir == null) {
            super.onBackPressed();
            return;
        }
        
        // Get parent directory
        File parent = currentDir.getParentFile();
        
        if (parent == null) {
            // At root, show drives list
            showDrivesList();
        } else {
            currentDir = parent;
            loadFileList(currentDir);
        }
    }
    
    private String getDisplayPath(File dir) {
        String path = dir.getAbsolutePath();
        // Try to show a more user-friendly path
        String externalStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(externalStorage)) {
            String relative = path.substring(externalStorage.length());
            if (relative.isEmpty()) {
                return "Internal Storage";
            }
            return "Internal Storage" + relative;
        }
        // Check for other storage names
        File storageDir = new File("/storage");
        if (path.startsWith(storageDir.getAbsolutePath())) {
            return path;
        }
        File mntDir = new File("/mnt");
        if (path.startsWith(mntDir.getAbsolutePath())) {
            return path;
        }
        return path;
    }
}