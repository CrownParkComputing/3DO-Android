package com.fourdo.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
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

    private Button backButton;
    private Button selectBiosButton;
    private Button selectLibraryButton;
    private Button controllerMapperButton;
    private Button clearCacheButton;
    private Button viewLogsButton;
    private TextView biosPathText;
    private TextView libraryPathText;
    private Switch debugOverlaySwitch;
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
        controllerMapperButton = findViewById(R.id.controller_mapper_button);
        clearCacheButton = findViewById(R.id.clear_cache_button);
        viewLogsButton = findViewById(R.id.view_logs_button);
        biosPathText = findViewById(R.id.bios_path_text);
        libraryPathText = findViewById(R.id.library_path_text);
        debugOverlaySwitch = findViewById(R.id.debug_overlay_switch);
        viewStyleSpinner = findViewById(R.id.view_style_spinner);
        rendererSpinner = findViewById(R.id.renderer_spinner);
        filteringSpinner = findViewById(R.id.filtering_spinner);
        regionSpinner = findViewById(R.id.region_spinner);

        refreshBiosPathText();
        refreshLibraryPathText();
        setupViewStyleSpinner();
        setupRendererSpinner();
        setupFilteringSpinner();
        setupRegionSpinner();
        setupDebugOverlaySwitch();

        selectBiosButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, FileBrowserActivity.class);
                startActivityForResult(intent, REQUEST_BIOS_PICKER);
            }
        });

        selectLibraryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, FileBrowserActivity.class);
                intent.putExtra(FileBrowserActivity.EXTRA_SELECT_FOLDER_MODE, true);
                startActivityForResult(intent, REQUEST_LIBRARY_PICKER);
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
        String[] renderers = {"Automatic", "OpenGL ES", "Vulkan", "Software"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, renderers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rendererSpinner.setAdapter(adapter);
        
        // Load saved preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedRenderer = normalizeRendererType(prefs.getInt(KEY_RENDERER_TYPE, RENDERER_AUTO));
        int selection = 0;
        if (savedRenderer == RENDERER_OPENGL_ES) {
            selection = 1;
        } else if (savedRenderer == RENDERER_VULKAN) {
            selection = 2;
        } else if (savedRenderer == RENDERER_SOFTWARE) {
            selection = 3;
        }
        rendererSpinner.setSelection(selection);
        prefs.edit().putInt(KEY_RENDERER_TYPE, savedRenderer).apply();
        
        rendererSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                int rendererType;
                if (position == 1) {
                    rendererType = RENDERER_OPENGL_ES;
                } else if (position == 2) {
                    rendererType = RENDERER_VULKAN;
                } else if (position == 3) {
                    rendererType = RENDERER_SOFTWARE;
                } else {
                    rendererType = RENDERER_AUTO;
                }
                prefs.edit().putInt(KEY_RENDERER_TYPE, rendererType).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private int normalizeRendererType(int rendererType) {
        if (rendererType == RENDERER_AUTO) {
            return RENDERER_AUTO;
        }
        if (rendererType == RENDERER_OPENGL_ES) {
            return RENDERER_OPENGL_ES;
        }
        if (rendererType == RENDERER_VULKAN) {
            return RENDERER_VULKAN;
        }
        if (rendererType == RENDERER_SOFTWARE) {
            return RENDERER_SOFTWARE;
        }
        return RENDERER_AUTO;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_BIOS_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String biosPath = uri.getPath();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(KEY_BIOS_PATH, biosPath).apply();
                refreshBiosPathText();
            }
        }

        if (requestCode == REQUEST_LIBRARY_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String folderPath = uri.getPath();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(KEY_LIBRARY_FOLDER, folderPath).apply();
                refreshLibraryPathText();
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
    
    private String getDisplayPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String externalStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(externalStorage)) {
            String relative = path.substring(externalStorage.length());
            if (relative.isEmpty()) {
                return "Storage";
            }
            return "Storage" + relative;
        }
        return path;
    }
}