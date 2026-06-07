package com.fourdo.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "fourdo_prefs";
    public static final String KEY_BIOS_PATH = "bios_path";
    public static final String KEY_LAST_GAME_PATH = "last_game_path";
    public static final String KEY_LIBRARY_FOLDER = "library_folder";
    public static final String KEY_LIBRARY_REFRESH_REQUIRED = "library_refresh_required";
    public static final String KEY_DEBUG_OVERLAY_ENABLED = "debug_overlay_enabled";
    public static final String KEY_VIEW_STYLE = "view_style";
    public static final String KEY_RENDERER_TYPE = "renderer_type";
    public static final String KEY_TEXTURE_FILTERING = "texture_filtering";
    public static final String KEY_REGION = "region";
    public static final String KEY_BEZEL_ENABLED = "bezel_enabled";
    public static final String KEY_DISPLAY_ROTATION = "display_rotation";
    public static final String KEY_VULKAN_DRIVER_PATH = "vulkan_driver_path";
    public static final String KEY_CRT_ENABLED = "crt_shader_enabled";

    // Display rotation modes. 0 = follow device orientation (the default);
    // 1..4 = force a fixed 0/90/180/270 deg rotation in the shader.
    public static final int ROTATION_AUTO = 0;
    public static final int ROTATION_FIXED_0 = 1;
    public static final int ROTATION_FIXED_90 = 2;
    public static final int ROTATION_FIXED_180 = 3;
    public static final int ROTATION_FIXED_270 = 4;

    public static final int VIEW_STYLE_GRID_SMALL = 0;
    public static final int VIEW_STYLE_GRID_MEDIUM = 1;
    public static final int VIEW_STYLE_GRID_LARGE = 2;
    public static final int VIEW_STYLE_CAROUSEL = 3;
    
    // Renderer types - must match native code
    public static final int RENDERER_AUTO = 0;
    public static final int RENDERER_OPENGL_ES = 1;
    public static final int RENDERER_VULKAN = 2;
    public static final int RENDERER_SOFTWARE = 3;
    
    // Texture filtering modes
    public static final int FILTERING_LINEAR = 0;
    public static final int FILTERING_NEAREST = 1;

    // Region constants – must match native EmulatorActivity values
    public static final int REGION_NTSC = 0;
    public static final int REGION_PAL1 = 1;
    public static final int REGION_PAL2 = 2;
    
    private static final int REQUEST_BIOS_PICKER = 1;
    private static final int REQUEST_LIBRARY_PICKER = 2;
    private static final int REQUEST_VULKAN_DRIVER_PICKER = 3;

    private Button backButton;
    private Button selectBiosButton;
    private Button selectLibraryButton;
    private Button selectVulkanDriverButton;
    private Button resetVulkanDriverButton;
    private Button controllerMapperButton;
    private Button clearCacheButton;
    private Button viewLogsButton;
    private TextView biosPathText;
    private TextView libraryPathText;
    private TextView vulkanDriverPathText;
    private SwitchCompat debugOverlaySwitch;
    private SwitchCompat bezelSwitch;
    private SwitchCompat crtSwitch;
    private Spinner viewStyleSpinner;
    private Spinner rendererSpinner;
    private Spinner filteringSpinner;
    private Spinner regionSpinner;

    public static void start(Context context) {
        Intent intent = new Intent(context, SettingsActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        backButton = findViewById(R.id.back_button);
        selectBiosButton = findViewById(R.id.select_bios_button);
        selectLibraryButton = findViewById(R.id.select_library_button);
        selectVulkanDriverButton = findViewById(R.id.select_vulkan_driver_button);
        resetVulkanDriverButton = findViewById(R.id.reset_vulkan_driver_button);
        controllerMapperButton = findViewById(R.id.controller_mapper_button);
        clearCacheButton = findViewById(R.id.clear_cache_button);
        viewLogsButton = findViewById(R.id.view_logs_button);
        biosPathText = findViewById(R.id.bios_path_text);
        libraryPathText = findViewById(R.id.library_path_text);
        vulkanDriverPathText = findViewById(R.id.vulkan_driver_path_text);
        debugOverlaySwitch = findViewById(R.id.debug_overlay_switch);
        bezelSwitch = findViewById(R.id.bezel_switch);
        crtSwitch = findViewById(R.id.crt_switch);
        viewStyleSpinner = findViewById(R.id.view_style_spinner);
        rendererSpinner = findViewById(R.id.renderer_spinner);
        filteringSpinner = findViewById(R.id.filtering_spinner);
        regionSpinner = findViewById(R.id.region_spinner);

        refreshBiosPathText();
        refreshLibraryPathText();
        refreshVulkanDriverPathText();
        setupViewStyleSpinner();
        setupRendererSpinner();
        setupFilteringSpinner();
        setupRegionSpinner();
        setupDebugOverlaySwitch();
        setupBezelSwitch();
        setupCrtSwitch();

        selectBiosButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = SafFileImporter.createOpenDocumentIntent();
                startActivityForResult(intent, REQUEST_BIOS_PICKER);
            }
        });

        selectLibraryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = SafFileImporter.createOpenDocumentTreeIntent();
                startActivityForResult(intent, REQUEST_LIBRARY_PICKER);
            }
        });

        selectVulkanDriverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = SafFileImporter.createOpenDocumentIntent();
                startActivityForResult(intent, REQUEST_VULKAN_DRIVER_PICKER);
            }
        });

        resetVulkanDriverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().remove(KEY_VULKAN_DRIVER_PATH).apply();
                refreshVulkanDriverPathText();
                Toast.makeText(SettingsActivity.this, getString(R.string.vulkan_driver_reset), Toast.LENGTH_SHORT).show();
            }
        });

        controllerMapperButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, NewControllerMapperActivity.class);
                startActivity(intent);
            }
        });

        clearCacheButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IgdbService.getInstance(SettingsActivity.this).clearCache();
                Toast.makeText(SettingsActivity.this, "IGDB cache cleared", Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        
        viewLogsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogsDialog();
            }
        });
    }
    
    private void showLogsDialog() {
        // Read logcat
        StringBuilder logs = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -t 200 GameLibrary:D *:S");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
            }
            reader.close();
        } catch (Exception e) {
            logs.append("Error reading logs: ").append(e.getMessage());
        }
        
        if (logs.length() == 0) {
            logs.append("No logs available.");
        }
        
        // Create dialog with scrollable text
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Application Logs");
        
        // Create a scrollable TextView
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        
        TextView logTextView = new TextView(this);
        logTextView.setText(logs.toString());
        logTextView.setTextColor(0xFF00FF00);
        logTextView.setTextSize(10);
        logTextView.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logTextView);
        layout.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        builder.setView(layout);
        builder.setPositiveButton("Close", null);
        builder.setNegativeButton("Clear", (dialog, which) -> {
            try {
                Runtime.getRuntime().exec("logcat -c");
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e("Settings", "Failed to clear logs", e);
            }
        });
        builder.show();
    }

    private void setupViewStyleSpinner() {
        String[] viewStyles = {"Grid (Small)", "Grid (Medium)", "Grid (Large)", "Carousel"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, viewStyles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        viewStyleSpinner.setAdapter(adapter);
        
        // Load saved preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedStyle = prefs.getInt(KEY_VIEW_STYLE, VIEW_STYLE_GRID_MEDIUM);
        viewStyleSpinner.setSelection(savedStyle);
        
        viewStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt(KEY_VIEW_STYLE, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
    
    private void setupRendererSpinner() {
        String[] renderers = {"Vulkan", "OpenGL ES", "Software"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, renderers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rendererSpinner.setAdapter(adapter);
        
        // Load saved preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedRenderer = normalizeRendererType(prefs.getInt(KEY_RENDERER_TYPE, RENDERER_VULKAN));
        
        int selection = 0;
        if (savedRenderer == RENDERER_OPENGL_ES) selection = 1;
        else if (savedRenderer == RENDERER_SOFTWARE) selection = 2;
        
        rendererSpinner.setSelection(selection);
        prefs.edit().putInt(KEY_RENDERER_TYPE, savedRenderer).apply();
        
        rendererSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                int rendererType = RENDERER_VULKAN;
                if (position == 1) rendererType = RENDERER_OPENGL_ES;
                else if (position == 2) rendererType = RENDERER_SOFTWARE;
                prefs.edit().putInt(KEY_RENDERER_TYPE, rendererType).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int normalizeRendererType(int rendererType) {
        if (rendererType == RENDERER_VULKAN || rendererType == RENDERER_OPENGL_ES || rendererType == RENDERER_SOFTWARE) {
            return rendererType;
        }
        return RENDERER_VULKAN;
    }
    
    private void setupFilteringSpinner() {
        String[] filters = {"Linear (Smooth)", "Nearest (Sharp)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, filters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filteringSpinner.setAdapter(adapter);
        
        // Load saved preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedFilter = prefs.getInt(KEY_TEXTURE_FILTERING, FILTERING_LINEAR);
        filteringSpinner.setSelection(savedFilter);
        
        filteringSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt(KEY_TEXTURE_FILTERING, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupRegionSpinner() {
        if (regionSpinner == null) return;
        String[] regions = {
            getString(R.string.region_ntsc),
            getString(R.string.region_pal1),
            getString(R.string.region_pal2)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, regions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        regionSpinner.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedRegion = prefs.getInt(KEY_REGION, REGION_NTSC);
        regionSpinner.setSelection(savedRegion);

        regionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putInt(KEY_REGION, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupDebugOverlaySwitch() {
        if (debugOverlaySwitch == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        debugOverlaySwitch.setChecked(prefs.getBoolean(KEY_DEBUG_OVERLAY_ENABLED, false));
        debugOverlaySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_DEBUG_OVERLAY_ENABLED, isChecked).apply();
            }
        });
    }

    private void setupBezelSwitch() {
        if (bezelSwitch == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bezelSwitch.setChecked(prefs.getBoolean(KEY_BEZEL_ENABLED, true));
        bezelSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_BEZEL_ENABLED, isChecked).apply();
            }
        });
    }

    private void setupCrtSwitch() {
        if (crtSwitch == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        crtSwitch.setChecked(prefs.getBoolean(KEY_CRT_ENABLED, false));
        crtSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_CRT_ENABLED, isChecked).apply();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_BIOS_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    String biosPath = SafFileImporter.importBios(this, uri);
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs.edit().putString(KEY_BIOS_PATH, biosPath).apply();
                    refreshBiosPathText();
                    Toast.makeText(this, "BIOS imported", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "BIOS import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }

        if (requestCode == REQUEST_LIBRARY_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Toast.makeText(this, "Importing library...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        SafFileImporter.ImportResult result = SafFileImporter.importLibraryTree(this, uri);
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit()
                                .putString(KEY_LIBRARY_FOLDER, result.path)
                                .putBoolean(KEY_LIBRARY_REFRESH_REQUIRED, true)
                                .apply();
                        runOnUiThread(() -> {
                            refreshLibraryPathText();
                            Toast.makeText(this, "Imported " + result.importedFileCount + " library files", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "Library import failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }, "settings-library-import").start();
            }
        }

        if (requestCode == REQUEST_VULKAN_DRIVER_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    String driverPath = SafFileImporter.importVulkanDriver(this, uri);
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    prefs.edit().putString(KEY_VULKAN_DRIVER_PATH, driverPath).apply();
                    refreshVulkanDriverPathText();
                    Toast.makeText(this, getString(R.string.vulkan_driver_imported), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.vulkan_driver_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void refreshBiosPathText() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String biosPath = prefs.getString(KEY_BIOS_PATH, "");
        if (biosPath == null || biosPath.isEmpty()) {
            biosPathText.setText(getString(R.string.bios_not_set));
        } else {
            biosPathText.setText(getString(R.string.bios_path_value, getDisplayPath(biosPath)));
        }
    }

    private void refreshLibraryPathText() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String libraryPath = prefs.getString(KEY_LIBRARY_FOLDER, "");
        if (libraryPath == null || libraryPath.isEmpty()) {
            libraryPathText.setText(getString(R.string.library_not_set));
        } else {
            libraryPathText.setText(getString(R.string.library_path_value, getDisplayPath(libraryPath)));
        }
    }

    private void refreshVulkanDriverPathText() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String driverPath = prefs.getString(KEY_VULKAN_DRIVER_PATH, "");
        if (driverPath == null || driverPath.isEmpty()) {
            vulkanDriverPathText.setText(getString(R.string.vulkan_driver_system));
        } else {
            vulkanDriverPathText.setText(getDisplayPath(driverPath));
        }
    }
    
    private String getDisplayPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        File libraryDir = SafFileImporter.getManagedLibraryDirectory(this);
        if (path.startsWith(libraryDir.getAbsolutePath())) {
            String relative = path.substring(libraryDir.getAbsolutePath().length());
            return relative.isEmpty() ? "Managed Library" : "Managed Library" + relative;
        }
        File biosDir = new File(getFilesDir(), "bios");
        if (path.startsWith(biosDir.getAbsolutePath())) {
            String relative = path.substring(biosDir.getAbsolutePath().length());
            return relative.isEmpty() ? "Managed BIOS" : "Managed BIOS" + relative;
        }
        File driverDir = new File(getFilesDir(), "drivers");
        if (path.startsWith(driverDir.getAbsolutePath())) {
            String relative = path.substring(driverDir.getAbsolutePath().length());
            return relative.isEmpty() ? "Managed Driver" : "Managed Driver" + relative;
        }
        return path;
    }
}
