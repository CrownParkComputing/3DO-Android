package com.fourdo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class EmulatorActivity extends AppCompatActivity {

    private static final int MAX_RESOLUTION_SCALE = 9;

    private static final int REQUEST_FILE_PICKER = 1;
    private static final int REQUEST_BIOS_PICKER = 2;
    private static final int REQUEST_LOAD_CD_PICKER = 3;
    private static final int REQUEST_LIBRARY_GAME_PICKER = 4;
    
    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    private Button pauseButton;
    private Button resetButton;
    private Button loadCdButton;
    private Button renderOptionsButton;
    private Button backButton;
    private Button controllerMapButton;
    private LinearLayout overlayMenu;
    private boolean menuVisible = false;
    
    // Quick toolbar buttons
    private Button cdButton;
    private Button aspectRatioButton;
    private Button rendererButton;
    private Button filteringButton;
    private Button aaButton;
    private Button crtButton;
    private Button scaleButton;
    private Button menuToggleButton;
    private Button debugToggleButton;
    private android.widget.TextView statusIndicator;
    private android.widget.TextView fpsCounter;
    private LinearLayout quickToolbar;
    private String appVersion = "";
    
    // FPS counter
    private int frameCount = 0;
    private long lastFpsTime = 0;
    private android.os.Handler fpsHandler = new android.os.Handler();
    private Runnable fpsRunnable;
    private boolean debugModeEnabled = false;
    
    // Settings state
    private boolean aspectRatio16by9 = false;  // false = 4:3, true = 16:9
    private int currentRenderer = RENDERER_VULKAN;
    private boolean nearestFiltering = false;  // false = linear, true = nearest
    private int antiAliasingMode = 0;          // 0=off,1=low,2=high
    private boolean crtShaderEnabled = false;
    private int resolutionScale = 0;
    private int outputResolutionPreset = 0;
    private boolean flipVertical = false;
    private SurfaceView emulatorSurfaceView;
    private String mGamePath = null;
    private final EmulatorAudioEngine audioEngine = new EmulatorAudioEngine();

    public static void start(Context context) {
        start(context, null);
    }

    public static void start(Context context, String gamePath) {
        Intent intent = new Intent(context, EmulatorActivity.class);
        if (gamePath != null) {
            intent.putExtra("game_path", gamePath);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindowAndLayout();
        bindViews();
        initializeUiState();
        setupActivityButtons();
        setupEmulatorSurface();
        handleStartupIntent(getIntent());
    }

    private void configureWindowAndLayout() {
        DeviceOrientationManager.setOptimalEmulatorOrientation(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_emulator);
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            appVersion = "?";
        }
    }

    private void bindViews() {
        pauseButton = findViewById(R.id.pause_button);
        resetButton = findViewById(R.id.reset_button);
        loadCdButton = findViewById(R.id.load_cd_button);
        renderOptionsButton = findViewById(R.id.render_options_button);
        backButton = findViewById(R.id.back_button);
        controllerMapButton = findViewById(R.id.controller_map_button);
        overlayMenu = findViewById(R.id.overlay_menu);
        cdButton = loadCdButton;
        aspectRatioButton = null;
        rendererButton = null;
        filteringButton = null;
        aaButton = null;
        crtButton = null;
        scaleButton = null;
        menuToggleButton = findViewById(R.id.menu_toggle_button);
        debugToggleButton = findViewById(R.id.debug_toggle_button);
        statusIndicator = findViewById(R.id.status_indicator);
        fpsCounter = findViewById(R.id.fps_counter);
        quickToolbar = null;
    }

    private void initializeUiState() {
        applyDebugModeUi();
        loadSettings();
        updateButtonLabels();
        startFpsCounter();
        setupQuickToolbar();
    }

    private void setupActivityButtons() {
        menuToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOverlayMenu();
            }
        });

        controllerMapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openControllerMap();
                hideOverlayMenu();
            }
        });

        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePause();
                hideOverlayMenu();
            }
        });

        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlayMenu();
                performHardReset();
            }
        });

        loadCdButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlayMenu();
                openLoadCdBrowser();
            }
        });

        renderOptionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenderOptionsDialog();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlayMenu();
                finish();
            }
        });
    }

    private void setupEmulatorSurface() {
        FrameLayout surfaceContainer = findViewById(R.id.emulator_surface);
        emulatorSurfaceView = new SurfaceView(this);
        surfaceContainer.addView(emulatorSurfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emulatorSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // Don't set surface yet - wait for surfaceChanged with proper size
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (width > 0 && height > 0) {
                    applyRendererDefaults(width, height, holder.getSurface());
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                setSurface(null);
            }
        });
    }

    private void applyRendererDefaults(int width, int height, Surface surface) {
        setRendererType(currentRenderer);
        setFiltering(nearestFiltering);
        setAspectRatio(aspectRatio16by9);
        setAntiAliasingMode(antiAliasingMode);
        setCrtShaderEnabled(crtShaderEnabled);
        setResolutionScale(resolutionScale);
        setOutputResolutionPreset(outputResolutionPreset);
        setFlipVertical(false);
        setFlipX(false);
        setFlipY(false);
        initRenderer(width, height);
        setSurface(surface);
    }

    private void handleStartupIntent(Intent intent) {
        if (intent != null && intent.hasExtra("game_path")) {
            mGamePath = intent.getStringExtra("game_path");
        }

        String biosPath = EmulatorPathStore.getSavedBiosPath(this);
        if (!EmulatorPathStore.isValidFilePath(biosPath)) {
            Toast.makeText(this, R.string.bios_required, Toast.LENGTH_SHORT).show();
            openBiosBrowser();
        } else if (mGamePath == null || mGamePath.isEmpty()) {
            initializeEmulator(null);
            emulatorSurfaceView.postDelayed(() -> {
                showOverlayMenu();
                Toast.makeText(this, "Select a game from the menu", Toast.LENGTH_LONG).show();
            }, 1000);
        } else {
            initializeEmulator(mGamePath);
        }
    }

    private void openBiosBrowser() {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        startActivityForResult(intent, REQUEST_BIOS_PICKER);
    }
    
    private void openFileBrowser() {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        startActivityForResult(intent, REQUEST_FILE_PICKER);
    }

    private void openLoadCdBrowser() {
        String libraryPath = EmulatorPathStore.getSavedLibraryFolder(this);
        if (EmulatorPathStore.isValidDirectoryPath(libraryPath)) {
            Intent intent = new Intent(this, GameLibraryActivity.class);
            intent.putExtra(GameLibraryActivity.EXTRA_LIBRARY_PATH, libraryPath);
            intent.putExtra(GameLibraryActivity.EXTRA_PICK_MODE, true);
            startActivityForResult(intent, REQUEST_LOAD_CD_PICKER);
        } else {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            startActivityForResult(intent, REQUEST_LOAD_CD_PICKER);
        }
    }

    private void ejectCdImage() {
        // Eject CD and clear NVRAM
        if (emulatorLoadCdImage(null)) {
            Toast.makeText(this, R.string.cd_ejected, Toast.LENGTH_SHORT).show();
        }
    }

    private void openControllerMap() {
        Intent intent = new Intent(this, NewControllerMapperActivity.class);
        startActivity(intent);
    }
    
    private void setupQuickToolbar() {
        // CD button - long press to load CD
        if (cdButton != null) {
            cdButton.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    ejectCdImage();
                    showStatusToast("CD ejected");
                    return true;
                }
            });
            cdButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLoadCdBrowser();
                    showStatusToast("Load CD...");
                }
            });
        }

        // Open options menu from any render-option toolbar button
        if (aspectRatioButton != null) {
            aspectRatioButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }

        if (rendererButton != null) {
            rendererButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }

        if (filteringButton != null) {
            filteringButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }

        // Options button opens dropdown menu directly
        if (aaButton != null) {
            aaButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }

        if (crtButton != null) {
            crtButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }

        if (scaleButton != null) {
            scaleButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showRenderOptionsDialog();
                }
            });
        }
        
        // Update status indicator
        updateStatusIndicator();
        
        // Debug toggle button
        debugToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDebugMode();
            }
        });
    }

    private String[] getResolutionScaleOptions() {
        return new String[] {
                "Auto (Based on Window Size)",
                "1x Native",
                "2x",
                "3x Native (for 720p)",
                "4x",
                "5x Native (for 1080p)",
                "6x Native (for 1440p)",
                "7x",
                "8x",
                "9x Native (for 4K)"
        };
    }

    private int getResolutionScaleIndex(int scale) {
        if (scale < 0) {
            return 0;
        }
        if (scale > MAX_RESOLUTION_SCALE) {
            return MAX_RESOLUTION_SCALE;
        }
        return scale;
    }

    private int getResolutionScaleByIndex(int index) {
        if (index < 0) {
            return 0;
        }
        if (index > MAX_RESOLUTION_SCALE) {
            return MAX_RESOLUTION_SCALE;
        }
        return index;
    }

    private boolean isHardwareRendererSelected() {
        return currentRenderer != RENDERER_SOFTWARE;
    }

    private void showRenderOptionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Render Options");

        // Wrap options in a ScrollView so long dialogs remain accessible
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 8);
        scroll.addView(root);

        TextView rendererLabel = new TextView(this);
        rendererLabel.setText("Renderer");
        root.addView(rendererLabel);
        Spinner rendererSpinner = new Spinner(this);
        String[] rendererOptions = {"Automatic", "OpenGL ES", "Vulkan", "Software"};
        rendererSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rendererOptions));
        int rendererSelection = 0;
        if (currentRenderer == RENDERER_OPENGL_ES) {
            rendererSelection = 1;
        } else if (currentRenderer == RENDERER_VULKAN) {
            rendererSelection = 2;
        } else if (currentRenderer == RENDERER_SOFTWARE) {
            rendererSelection = 3;
        }
        rendererSpinner.setSelection(rendererSelection);
        root.addView(rendererSpinner);

        TextView aspectLabel = new TextView(this);
        aspectLabel.setText("Aspect Ratio");
        root.addView(aspectLabel);
        Spinner aspectSpinner = new Spinner(this);
        String[] aspectOptions = {"4:3", "16:9"};
        aspectSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, aspectOptions));
        aspectSpinner.setSelection(aspectRatio16by9 ? 1 : 0);
        root.addView(aspectSpinner);

        TextView filterLabel = new TextView(this);
        filterLabel.setText("Texture Filter");
        root.addView(filterLabel);
        Spinner filterSpinner = new Spinner(this);
        String[] filterOptions = {"Linear (Smooth)", "Nearest (Sharp)"};
        filterSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, filterOptions));
        filterSpinner.setSelection(nearestFiltering ? 1 : 0);
        root.addView(filterSpinner);

        TextView aaLabel = new TextView(this);
        aaLabel.setText("Anti-Aliasing");
        root.addView(aaLabel);
        Spinner aaSpinner = new Spinner(this);
        String[] aaOptions = {"Off", "Low (FXAA)", "High (FXAA+)"};
        aaSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, aaOptions));
        aaSpinner.setSelection(antiAliasingMode);
        root.addView(aaSpinner);

        TextView crtLabel = new TextView(this);
        crtLabel.setText("CRT Shader");
        root.addView(crtLabel);
        Spinner crtSpinner = new Spinner(this);
        String[] crtOptions = {"Off", "On"};
        crtSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, crtOptions));
        crtSpinner.setSelection(crtShaderEnabled ? 1 : 0);
        root.addView(crtSpinner);

        TextView resLabel = new TextView(this);
        resLabel.setText("Resolution");
        root.addView(resLabel);
        Spinner resSpinner = new Spinner(this);
        String[] resOptions = getResolutionScaleOptions();
        resSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, resOptions));
        resSpinner.setSelection(getResolutionScaleIndex(resolutionScale));
        resSpinner.setEnabled(isHardwareRendererSelected());
        root.addView(resSpinner);

        final boolean[] ready = {false};

        rendererSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int selectedRenderer;
                if (position == 1) {
                    selectedRenderer = RENDERER_OPENGL_ES;
                } else if (position == 2) {
                    selectedRenderer = RENDERER_VULKAN;
                } else if (position == 3) {
                    selectedRenderer = RENDERER_SOFTWARE;
                } else {
                    selectedRenderer = RENDERER_AUTO;
                }
                if (!ready[0] || currentRenderer == selectedRenderer) return;
                currentRenderer = selectedRenderer;
                if (!isHardwareRendererSelected()) {
                    resolutionScale = 1;
                    outputResolutionPreset = 0;
                    setResolutionScale(resolutionScale);
                    setOutputResolutionPreset(0);
                    resSpinner.setSelection(getResolutionScaleIndex(resolutionScale));
                }
                resSpinner.setEnabled(isHardwareRendererSelected());
                setRendererType(currentRenderer);
                rebindRendererSurface();
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        aspectSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean newWide = (position == 1);
                if (!ready[0] || aspectRatio16by9 == newWide) return;
                aspectRatio16by9 = newWide;
                setAspectRatio(aspectRatio16by9);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        filterSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean newNearest = (position == 1);
                if (!ready[0] || nearestFiltering == newNearest) return;
                nearestFiltering = newNearest;
                setFiltering(nearestFiltering);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        aaSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!ready[0] || antiAliasingMode == position) return;
                antiAliasingMode = position;
                setAntiAliasingMode(antiAliasingMode);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        crtSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean newCrt = (position == 1);
                if (!ready[0] || crtShaderEnabled == newCrt) return;
                crtShaderEnabled = newCrt;
                setCrtShaderEnabled(crtShaderEnabled);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        resSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int newScale = getResolutionScaleByIndex(position);
                if (!ready[0] || resolutionScale == newScale) return;
                if (!isHardwareRendererSelected()) {
                    resSpinner.setSelection(getResolutionScaleIndex(resolutionScale));
                    showStatusToast("Upscaling is only available on hardware renderers");
                    return;
                }
                resolutionScale = newScale;
                outputResolutionPreset = 0;
                setResolutionScale(resolutionScale);
                setOutputResolutionPreset(0);
                rebindRendererSurface();
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        ready[0] = true;

        // Flip controls removed: renderer will remain unflipped at runtime

        builder.setView(scroll);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void rebindRendererSurface() {
        if (emulatorSurfaceView == null) {
            return;
        }

        final SurfaceHolder holder = emulatorSurfaceView.getHolder();
        if (holder == null) {
            return;
        }

        final Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }

        int width = emulatorSurfaceView.getWidth();
        int height = emulatorSurfaceView.getHeight();
        if (width > 0 && height > 0) {
            initRenderer(width, height);
        }

        setSurface(null);
        emulatorSurfaceView.post(new Runnable() {
            @Override
            public void run() {
                Surface reboundSurface = holder.getSurface();
                if (reboundSurface != null && reboundSurface.isValid()) {
                    setSurface(reboundSurface);
                }
            }
        });
    }
    
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        aspectRatio16by9 = prefs.getBoolean("aspect_ratio_16by9", false);
        currentRenderer = normalizeRendererType(prefs.getInt(SettingsActivity.KEY_RENDERER_TYPE, RENDERER_AUTO));
        nearestFiltering = prefs.getBoolean("nearest_filtering", false);
        antiAliasingMode = prefs.getInt("anti_aliasing_mode", 0);
        if (antiAliasingMode < 0 || antiAliasingMode > 2) {
            antiAliasingMode = 0;
        }
        crtShaderEnabled = prefs.getBoolean("crt_shader_enabled", false);
        resolutionScale = prefs.getInt("resolution_scale", 0);
        if (resolutionScale < 0 || resolutionScale > MAX_RESOLUTION_SCALE) {
            resolutionScale = 0;
        }
        outputResolutionPreset = prefs.getInt("output_resolution_preset", 0);
        if (!(outputResolutionPreset == 0 || outputResolutionPreset == 720 || outputResolutionPreset == 1080
                || outputResolutionPreset == 1440 || outputResolutionPreset == 2160)) {
            outputResolutionPreset = 0;
        }
        if (!isHardwareRendererSelected()) {
            resolutionScale = 1;
            outputResolutionPreset = 0;
        }
        // Ensure display uses no flips by default (restore original state)
        flipVertical = false;
        setFlipVertical(false);
        setFlipX(false);
        setFlipY(false);
    }
    
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putBoolean("aspect_ratio_16by9", aspectRatio16by9)
            .putInt(SettingsActivity.KEY_RENDERER_TYPE, currentRenderer)
            .putBoolean("nearest_filtering", nearestFiltering)
            .putInt("anti_aliasing_mode", antiAliasingMode)
            .putBoolean("crt_shader_enabled", crtShaderEnabled)
            .putInt("resolution_scale", resolutionScale)
                .putInt("output_resolution_preset", outputResolutionPreset)
            .apply();
    }

    private String getRendererShortName(int rendererType) {
        if (rendererType == RENDERER_VULKAN) {
            return "Vulkan";
        }
        if (rendererType == RENDERER_OPENGL_ES) {
            return "OpenGL";
        }
        if (rendererType == RENDERER_SOFTWARE) {
            return "Soft";
        }
        return "Auto";
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
    
    private void updateStatusIndicator() {
        if (statusIndicator == null) {
            return;
        }

        String aspect = aspectRatio16by9 ? "16:9" : "4:3";
        String renderer = getRendererShortName(currentRenderer);
        String nativeRenderer = getRendererName();
        if (nativeRenderer != null && !nativeRenderer.isEmpty() && !"None".equals(nativeRenderer)) {
            renderer = nativeRenderer;
        }
        String filter = nearestFiltering ? "Sharp" : "Smooth";
        String aa = getAaLabel(antiAliasingMode);
        String crt = crtShaderEnabled ? "CRT" : "NoCRT";
        String internalScale = getInternalScaleLabel(resolutionScale);
        String output = getEffectiveResolutionLabel();
        String targetInfo = getRenderTargetInfo();
        if (targetInfo == null || targetInfo.isEmpty()) {
            targetInfo = "Surface:?x? Viewport:?x? InternalRT:?x? Preset:? Scale:?x";
        }

        if (debugModeEnabled) {
            statusIndicator.setText(
                    "Renderer: " + renderer + "\n" +
                    "Aspect: " + aspect + "   Filter: " + filter + "   " + aa + "   " + crt + "\n" +
                    "Resolution: " + output + "\n" +
                    targetInfo
            );
        } else {
            statusIndicator.setText("v" + appVersion + " | " + renderer + " | " + output);
        }
    }

    private String getAaLabel(int mode) {
        if (mode == 1) return "AA-L";
        if (mode == 2) return "AA-H";
        return "AA-Off";
    }

    private String getResolutionPresetLabel(int presetHeight) {
        if (presetHeight == 720) return "720p";
        if (presetHeight == 1080) return "1080p";
        if (presetHeight == 1440) return "1440p";
        if (presetHeight == 2160) return "2160p";
        return "Auto";
    }

    private String getInternalScaleLabel(int scale) {
        if (scale <= 0) return "Auto";
        return scale + "x";
    }

    private String getEffectiveResolutionLabel() {
        if (!isHardwareRendererSelected()) {
            return "1x (Software)";
        }
        if (resolutionScale == 3) return "3x (720p)";
        if (resolutionScale == 5) return "5x (1080p)";
        if (resolutionScale == 6) return "6x (1440p)";
        if (resolutionScale == 9) return "9x (4K)";
        if (resolutionScale <= 0) return "Auto";
        return resolutionScale + "x";
    }
    
    private void showStatusToast(String message) {
        Toast toast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        View toastView = toast.getView();
        if (toastView != null) {
            TextView messageView = toastView.findViewById(android.R.id.message);
            if (messageView != null) {
                messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            }
        }
        toast.show();
    }
    
    private void updateButtonLabels() {
        // Update aspect ratio button
        if (aspectRatioButton != null) {
            aspectRatioButton.setText("📺");
        }
        
        // Update renderer button
        if (rendererButton != null) {
            rendererButton.setText("🎮");
        }
        
        // Update filtering button
        if (filteringButton != null) {
            filteringButton.setText("🔍");
        }

        // Update anti-aliasing button
        if (aaButton != null) {
            aaButton.setText("⚙");
        }

        // Update CRT and scale buttons
        if (crtButton != null) {
            crtButton.setText(crtShaderEnabled ? "📺" : "⬜");
        }
        if (scaleButton != null) {
            scaleButton.setText(getEffectiveResolutionLabel());
        }
    }
    
    private void startFpsCounter() {
        fpsRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (lastFpsTime > 0) {
                    long elapsed = now - lastFpsTime;
                    if (elapsed > 0) {
                        int renderedFrames = consumeRenderedFrames();
                        int fps = (int)(renderedFrames * 1000L / elapsed);
                        fpsCounter.setText("FPS " + fps);
                        if (debugModeEnabled) {
                            updateStatusIndicator();
                        }
                    }
                }
                lastFpsTime = now;
                fpsHandler.postDelayed(this, 1000);
            }
        };
        fpsHandler.post(fpsRunnable);
    }
    
    private void stopFpsCounter() {
        if (fpsRunnable != null) {
            fpsHandler.removeCallbacks(fpsRunnable);
        }
    }
    
    // Called from native code to count frames
    private void countFrame() {
        frameCount++;
    }

    private void toggleOverlayMenu() {
        if (menuVisible) {
            hideOverlayMenu();
        } else {
            showOverlayMenu();
        }
    }

    private void showOverlayMenu() {
        if (overlayMenu != null) {
            overlayMenu.setVisibility(View.VISIBLE);
            menuVisible = true;
        }
    }

    private void hideOverlayMenu() {
        if (overlayMenu != null) {
            overlayMenu.setVisibility(View.GONE);
            menuVisible = false;
        }
    }
    
    private void toggleDebugMode() {
        debugModeEnabled = !debugModeEnabled;
        applyDebugModeUi();
        if (debugModeEnabled) {
            updateStatusIndicator();
        }
    }

    private void applyDebugModeUi() {
        if (fpsCounter != null) {
            fpsCounter.setVisibility(debugModeEnabled ? View.VISIBLE : View.GONE);
        }
        if (statusIndicator != null) {
            statusIndicator.setVisibility(debugModeEnabled ? View.VISIBLE : View.GONE);
        }
        if (debugToggleButton != null) {
            debugToggleButton.setText(debugModeEnabled ? "Debug: ON" : "Debug: OFF");
        }
    }

    private void openLibraryGamePickerForStartup() {
        String libraryPath = EmulatorPathStore.getSavedLibraryFolder(this);
        if (EmulatorPathStore.isValidDirectoryPath(libraryPath)) {
            Intent intent = new Intent(this, GameLibraryActivity.class);
            intent.putExtra(GameLibraryActivity.EXTRA_LIBRARY_PATH, libraryPath);
            intent.putExtra(GameLibraryActivity.EXTRA_PICK_MODE, true);
            startActivityForResult(intent, REQUEST_LIBRARY_GAME_PICKER);
        } else {
            initializeEmulator(null);
        }
    }

    private boolean emulatorLoadCdImage(String gamePath) {
        try {
            Class<?> clazz = Class.forName("com.fourdo.android.EmulatorActivity");
            java.lang.reflect.Method method = clazz.getMethod("loadCdImage", String.class);
            return (boolean) method.invoke(this, gamePath);
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_BIOS_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    String biosPath = uri.getPath();
                    if (EmulatorPathStore.isValidFilePath(biosPath)) {
                        EmulatorPathStore.saveBiosPath(this, biosPath);
                        initializeEmulator(null);
                        return;
                    }
                }
            }
            Toast.makeText(this, R.string.bios_required, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        if (requestCode == REQUEST_FILE_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    mGamePath = uri.getPath();
                    EmulatorPathStore.saveLastGamePath(this, mGamePath);
                    initializeEmulator(mGamePath);
                    return;
                }
            }
            // If cancelled or error, show message and finish
            Toast.makeText(this, "No game selected", Toast.LENGTH_SHORT).show();
            finish();
        }

        if (requestCode == REQUEST_LIBRARY_GAME_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    String gamePath = uri.getPath();
                    if (EmulatorPathStore.isSupportedCdPath(gamePath)) {
                        mGamePath = gamePath;
                        EmulatorPathStore.saveLastGamePath(this, gamePath);
                        initializeEmulator(mGamePath);
                        return;
                    }
                }
            }
            initializeEmulator(null);
            return;
        }

        if (requestCode == REQUEST_LOAD_CD_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    String cdPath = uri.getPath();
                    if (EmulatorPathStore.isSupportedCdPath(cdPath)) {
                        mGamePath = cdPath;
                        EmulatorPathStore.saveLastGamePath(this, cdPath);
                        performHardReset(cdPath);
                        Toast.makeText(this, R.string.cd_loaded, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.cd_load_failed, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
            }
            Toast.makeText(this, R.string.no_cd_selected, Toast.LENGTH_SHORT).show();
        }
    }

    private void performHardReset() {
        performHardReset(null);
    }

    private void performHardReset(String preferredGamePath) {
        stopAudioPlayback();
        shutdownEmulator();

        String biosPath = EmulatorPathStore.getSavedBiosPath(this);
        if (!EmulatorPathStore.isValidFilePath(biosPath)) {
            Toast.makeText(this, R.string.bios_required, Toast.LENGTH_SHORT).show();
            openBiosBrowser();
            return;
        }

        String gameToLoad = preferredGamePath;
        if (!EmulatorPathStore.isSupportedCdPath(gameToLoad)) {
            gameToLoad = mGamePath;
        }
        if (!EmulatorPathStore.isSupportedCdPath(gameToLoad)) {
            String lastGamePath = EmulatorPathStore.getSavedLastGamePath(this);
            if (EmulatorPathStore.isSupportedCdPath(lastGamePath)) {
                gameToLoad = lastGamePath;
                mGamePath = lastGamePath;
            }
        }

        if (EmulatorPathStore.isSupportedCdPath(gameToLoad)) {
            mGamePath = gameToLoad;
        }

        initializeEmulator(gameToLoad);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Integer button = EmulatorInputRouter.resolveMappedButton(this, keyCode, event);
        if (button != null) {
            setInputState(button, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Integer button = EmulatorInputRouter.resolveMappedButton(this, keyCode, event);
        if (button != null) {
            setInputState(button, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    
    private void initializeEmulator(String gamePath) {
        String biosPath = EmulatorPathStore.getSavedBiosPath(this);

        // Apply region setting before init
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        int region = prefs.getInt(SettingsActivity.KEY_REGION, REGION_NTSC);
        setRegion(region);

        // Initialize emulator
        if (!initEmulator(gamePath, biosPath)) {
            Toast.makeText(this, "Failed to initialize emulator", Toast.LENGTH_LONG).show();
            finish();
        } else {
            startAudioPlayback();
            // Load NVRAM if available
            String nvramPath = getNVRAMPath();
            if (nvramPath != null && !nvramPath.isEmpty()) {
                File nvramFile = new File(nvramPath);
                if (nvramFile.exists()) {
                    boolean loaded = loadNVRAM(nvramPath);
                    if (loaded) {
                        Log.d("4DO-Emulator", "NVRAM loaded from: " + nvramPath);
                    }
                } else {
                    Log.d("4DO-Emulator", "No existing NVRAM file, using freshly initialized NVRAM");
                }
            }
        }
    }

    private String getNVRAMPath() {
        String gamePath = mGamePath;
        if (gamePath == null || gamePath.isEmpty()) {
            // Use a default NVRAM path in app data dir
            File nvramDir = new File(getFilesDir(), "nvram");
            if (!nvramDir.exists()) {
                nvramDir.mkdirs();
            }
            return new File(nvramDir, "default.nvram").getAbsolutePath();
        }
        // Store NVRAM in app's internal data directory, keyed by game name
        File gameFile = new File(gamePath);
        String gameName = gameFile.getName();
        int dotIndex = gameName.lastIndexOf('.');
        if (dotIndex != -1) {
            gameName = gameName.substring(0, dotIndex);
        }
        File nvramDir = new File(getFilesDir(), "nvram");
        if (!nvramDir.exists()) {
            nvramDir.mkdirs();
        }
        return new File(nvramDir, gameName + ".nvram").getAbsolutePath();
    }

    private void autoSaveNVRAM() {
        String nvramPath = getNVRAMPath();
        if (nvramPath != null && !nvramPath.isEmpty()) {
            boolean saved = saveNVRAM(nvramPath);
            if (saved) {
                Log.d("4DO-Emulator", "NVRAM auto-saved to: " + nvramPath);
            }
        }
    }

    private void startAudioPlayback() {
        audioEngine.start(this::drainAudioFrames);
    }

    private void stopAudioPlayback() {
        audioEngine.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoSaveNVRAM();
        pauseEmulator();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumeEmulator();
    }

    @Override
    protected void onDestroy() {
        autoSaveNVRAM();
        super.onDestroy();
        stopFpsCounter();
        stopAudioPlayback();
        shutdownEmulator();
        cleanupRenderer();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Handle orientation changes for emulator
        DeviceOrientationManager.handleEmulatorOrientationChange(this);
        
        // If we're in landscape and have a surface, reinitialize renderer
        if (DeviceOrientationManager.isLandscape(this) && emulatorSurfaceView != null) {
            SurfaceHolder holder = emulatorSurfaceView.getHolder();
            if (holder.getSurface() != null && !holder.getSurface().isValid()) {
                initRenderer(emulatorSurfaceView.getWidth(), emulatorSurfaceView.getHeight());
            }
        }
    }

    // Native methods
    private native boolean initEmulator(String gamePath, String biosPath);
    private native void shutdownEmulator();
    private native void pauseEmulator();
    private native void resumeEmulator();
    private native void togglePause();
    private native void resetEmulator();
    private native boolean loadCdImage(String gamePath);
    private native void setInputState(int button, boolean pressed);
    private native int drainAudioFrames(int[] packedFrames);
    private native boolean initRenderer(int width, int height);
    private native void cleanupRenderer();
    private native void setSurface(Surface surface);
    private native boolean saveNVRAM(String path);
    private native boolean loadNVRAM(String path);

    // Save state methods
    private native int getStateSize();
    private native boolean saveState(byte[] buf);
    private native boolean loadState(byte[] buf);

    // Region and CPU speed
    private native void setRegion(int region);
    private native int getRegion();
    private native void setCpuSpeed(float multiplier);

    // New GPU renderer methods
    private native void setRendererType(int type);
    private native void setFiltering(boolean nearest);
    private native String getRendererName();
    private native void setAspectRatio(boolean wide);
    private native boolean getAspectRatio();
    private native void setCrtShaderEnabled(boolean enabled);
    private native boolean getCrtShaderEnabled();
    private native void setResolutionScale(int scale);
    private native int getResolutionScale();
    private native void setAntiAliasingMode(int mode);
    private native int getAntiAliasingMode();
    private native void setOutputResolutionPreset(int targetHeight);
    private native int getOutputResolutionPreset();
    private native String getRenderTargetInfo();
    private native void setFlipVertical(boolean flip);
    private native void setFlipX(boolean flip);
    private native void setFlipY(boolean flip);
    private native int consumeRenderedFrames();
    
    // Renderer type constants
    public static final int RENDERER_AUTO = 0;
    public static final int RENDERER_OPENGL_ES = 1;
    public static final int RENDERER_VULKAN = 2;
    public static final int RENDERER_SOFTWARE = 3;

    // Region constants (0=NTSC, 1=PAL1, 2=PAL2) — must match native values
    public static final int REGION_NTSC = 0;
    public static final int REGION_PAL1 = 1;
    public static final int REGION_PAL2 = 2;
}