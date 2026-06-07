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
import android.widget.ImageButton;
import android.widget.ImageView;
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

    private ImageButton pauseButton;
    private ImageButton quickSettingsButton;

    // Quick toolbar buttons
    private View loadingOverlay;
    private TextView loadingText;
    private ImageView emulatorBezel;
    private android.widget.TextView statusIndicator;
    private android.widget.TextView fpsCounter;
    private String appVersion = "";
    
    // FPS counter
    private int frameCount = 0;
    private long lastFpsTime = 0;
    private android.os.Handler fpsHandler = new android.os.Handler();
    private Runnable fpsRunnable;
    private boolean debugModeEnabled = false;
    private boolean manuallyPaused = false;
    private boolean emulatorInitializing = false;
    private boolean emulatorStarted = false;
    
    // Settings state
    private boolean aspectRatio16by9 = false;  // false = 4:3, true = 16:9
    private int currentRenderer = RENDERER_VULKAN;
    private boolean nearestFiltering = false;  // false = linear, true = nearest
    private int antiAliasingMode = 0;          // 0=off,1=low,2=high
    private boolean crtShaderEnabled = false;
    private boolean bezelEnabled = true;
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
        quickSettingsButton = findViewById(R.id.quick_settings_button);
        emulatorBezel = findViewById(R.id.emulator_bezel);
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingText = findViewById(R.id.loading_text);
        statusIndicator = findViewById(R.id.status_indicator);
        fpsCounter = findViewById(R.id.fps_counter);
    }

    private void initializeUiState() {
        loadSettings();
        applyBezelVisibility();
        applyDebugModeUi();
        updateButtonLabels();
        startFpsCounter();
        setupQuickToolbar();
    }

    private void setupActivityButtons() {
        pauseButton.setOnClickListener(v -> showPauseMenu());

        if (quickSettingsButton != null) {
            quickSettingsButton.setOnClickListener(v -> showRenderOptionsDialog());
        }
    }


    private void setupEmulatorSurface() {
        FrameLayout surfaceContainer = findViewById(R.id.emulator_surface);
        emulatorSurfaceView = new SurfaceView(this);
        surfaceContainer.addView(emulatorSurfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emulatorSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@androidx.annotation.NonNull SurfaceHolder holder) {
                // Don't set surface yet - wait for surfaceChanged with proper size
            }

            @Override
            public void surfaceChanged(@androidx.annotation.NonNull SurfaceHolder holder, int format, int width, int height) {
                if (width > 0 && height > 0) {
                    applyRendererDefaults(width, height, holder.getSurface());
                }
            }

            @Override
            public void surfaceDestroyed(@androidx.annotation.NonNull SurfaceHolder holder) {
                setSurface(null);
            }
        });
    }

    private void applyRendererDefaults(int width, int height, Surface surface) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String driverPath = prefs.getString(SettingsActivity.KEY_VULKAN_DRIVER_PATH, "");
        setVulkanDriverPath(driverPath);

        setRendererType(currentRenderer);
        setFiltering(nearestFiltering);
        setAspectRatio(aspectRatio16by9);
        setAntiAliasingMode(antiAliasingMode);
        setCrtShaderEnabled(crtShaderEnabled);
        setResolutionScale(resolutionScale);
        setOutputResolutionPreset(outputResolutionPreset);
        // No flips: the framebuffer is uploaded with its natural orientation and
        // the Vulkan IDENTITY pre-transform lets the compositor handle display
        // rotation. The old flipX=true was a workaround for the previous rotated
        // pipeline and now shows the image horizontally mirrored.
        setFlipVertical(false);
        setFlipX(false);
        setFlipY(false);
        // Apply display rotation. The user can pick a fixed rotation in
        // Settings, otherwise we follow the device orientation (which is
        // already the right answer for the GL renderer; the Vulkan renderer
        // needs the explicit value applied in the shader).
        setRotation(getEffectiveRotation());
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
            initializeEmulatorAsync(null, false);
            emulatorSurfaceView.postDelayed(() -> Toast.makeText(this, "Use the controller button to choose mappings", Toast.LENGTH_SHORT).show(), 1000);
        } else {
            initializeEmulatorAsync(mGamePath, true);
        }
    }

    private void openBiosBrowser() {
        Intent intent = SafFileImporter.createOpenDocumentIntent();
        startActivityForResult(intent, REQUEST_BIOS_PICKER);
    }
    
    private void openFileBrowser() {
        Intent intent = SafFileImporter.createOpenDocumentIntent();
        startActivityForResult(intent, REQUEST_FILE_PICKER);
    }


    private void openControllerMap() {
        Intent intent = new Intent(this, NewControllerMapperActivity.class);
        startActivity(intent);
    }
    
    private void setupQuickToolbar() {
        // Update status indicator
        updateStatusIndicator();
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
        String[] rendererOptions = {"Vulkan", "OpenGL ES", "Software"};
        rendererSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rendererOptions));
        
        int selection = 0;
        if (currentRenderer == RENDERER_OPENGL_ES) selection = 1;
        else if (currentRenderer == RENDERER_SOFTWARE) selection = 2;
        rendererSpinner.setSelection(selection);
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
        crtLabel.setText("CRT Filter");
        root.addView(crtLabel);
        androidx.appcompat.widget.SwitchCompat crtSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        crtSwitch.setChecked(crtShaderEnabled);
        crtSwitch.setText("CRT Scanlines & Curvature");
        root.addView(crtSwitch);

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
                int selectedRenderer = RENDERER_VULKAN;
                if (position == 1) selectedRenderer = RENDERER_OPENGL_ES;
                else if (position == 2) selectedRenderer = RENDERER_SOFTWARE;

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

        crtSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (!ready[0] || crtShaderEnabled == isChecked) return;
                crtShaderEnabled = isChecked;
                setCrtShaderEnabled(crtShaderEnabled);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
            }
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

        builder.setView(scroll);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Settings", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                Intent intent = new Intent(EmulatorActivity.this, SettingsActivity.class);
                startActivity(intent);
            }
        });
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
        debugModeEnabled = prefs.getBoolean(SettingsActivity.KEY_DEBUG_OVERLAY_ENABLED, false);
        aspectRatio16by9 = prefs.getBoolean("aspect_ratio_16by9", false);
        currentRenderer = normalizeRendererType(prefs.getInt(SettingsActivity.KEY_RENDERER_TYPE, RENDERER_VULKAN));
        nearestFiltering = prefs.getBoolean("nearest_filtering", false);
        antiAliasingMode = prefs.getInt("anti_aliasing_mode", 0);
        if (antiAliasingMode < 0 || antiAliasingMode > 2) {
            antiAliasingMode = 0;
        }
        crtShaderEnabled = prefs.getBoolean("crt_shader_enabled", false);
        bezelEnabled = prefs.getBoolean(SettingsActivity.KEY_BEZEL_ENABLED, true);
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

    private void applyBezelVisibility() {
        if (emulatorBezel != null) {
            emulatorBezel.setVisibility(bezelEnabled ? View.VISIBLE : View.GONE);
        }
    }

    private String getRendererShortName(int rendererType) {
        if (rendererType == RENDERER_VULKAN) {
            return "Vulkan";
        }
        if (rendererType == RENDERER_OPENGL_ES) {
            return "GL";
        }
        if (rendererType == RENDERER_SOFTWARE) {
            return "Soft";
        }
        return "Vulkan";
    }

    private int normalizeRendererType(int rendererType) {
        if (rendererType == RENDERER_VULKAN || rendererType == RENDERER_OPENGL_ES || rendererType == RENDERER_SOFTWARE) {
            return rendererType;
        }
        return RENDERER_VULKAN;
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
            statusIndicator.setText(getString(R.string.status_debug_info,
                    renderer, aspect, filter, aa, crt, output, targetInfo));
        } else {
            statusIndicator.setText(String.format("v%s | %s | %s", appVersion, renderer, output));
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

    private void applyDebugModeUi() {
        if (fpsCounter != null) {
            fpsCounter.setVisibility(debugModeEnabled ? View.VISIBLE : View.GONE);
        }
        if (statusIndicator != null) {
            statusIndicator.setVisibility(debugModeEnabled ? View.VISIBLE : View.GONE);
        }
        if (debugModeEnabled) {
            updateStatusIndicator();
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
            initializeEmulatorAsync(null, false);
        }
    }

    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_BIOS_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    try {
                        String biosPath = SafFileImporter.importBios(this, uri);
                        EmulatorPathStore.saveBiosPath(this, biosPath);
                        initializeEmulatorAsync(null, false);
                        return;
                    } catch (Exception ignored) {
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
                    try {
                        mGamePath = SafFileImporter.importGameFile(this, uri);
                        EmulatorPathStore.saveLastGamePath(this, mGamePath);
                        initializeEmulatorAsync(mGamePath, true);
                        return;
                    } catch (Exception ignored) {
                    }
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
                        initializeEmulatorAsync(mGamePath, true);
                        return;
                    }
                }
            }
            initializeEmulatorAsync(null, false);
            return;
        }

        if (requestCode == REQUEST_LOAD_CD_PICKER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    String cdPath = uri.getPath();
                    if (cdPath == null || !EmulatorPathStore.isSupportedCdPath(cdPath)) {
                        try {
                            cdPath = SafFileImporter.importGameFile(this, uri);
                        } catch (Exception ignored) {
                        }
                    }
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

        initializeEmulatorAsync(gameToLoad, gameToLoad != null && !gameToLoad.isEmpty());
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
    
    private void initializeEmulatorAsync(String gamePath, boolean loadingGame) {
        if (emulatorInitializing) {
            return;
        }

        emulatorInitializing = true;
        emulatorStarted = false;
        showLoadingOverlay(loadingGame ? "Loading game..." : "Starting emulator...");
        new Thread(() -> {
            boolean initialized;
            String biosPath = EmulatorPathStore.getSavedBiosPath(this);
            SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
            int region = prefs.getInt(SettingsActivity.KEY_REGION, REGION_NTSC);
            setRegion(region);
            initialized = initEmulator(gamePath, biosPath);

            runOnUiThread(() -> {
                emulatorInitializing = false;
                hideLoadingOverlay();
                if (!initialized) {
                    emulatorStarted = false;
                    String status = getStatus();
                    String message = "Failed to initialize emulator";
                    if (status != null && !status.isEmpty() && !"Not Running".equals(status)) {
                        message = status;
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                emulatorStarted = true;
                startAudioPlayback();
                String nvramPath = getNVRAMPath();
                if (nvramPath != null && !nvramPath.isEmpty()) {
                    File nvramFile = new File(nvramPath);
                    if (nvramFile.exists()) {
                        loadNVRAM(nvramPath);
                    }
                }
            });
        }, "emulator-init").start();
    }

    private void showLoadingOverlay(String message) {
        if (loadingText != null) {
            loadingText.setText(message);
        }
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void showPauseMenu() {
        manuallyPaused = true;
        pauseEmulator();

        new AlertDialog.Builder(this)
            .setTitle("Paused")
            .setItems(new CharSequence[]{"Resume", "Controller Mapping", "Return to Launcher"}, (dialog, which) -> {
                if (which == 0) {
                    manuallyPaused = false;
                    resumeEmulator();
                } else if (which == 1) {
                    manuallyPaused = false;
                    openControllerMap();
                } else if (which == 2) {
                    returnToLauncher();
                }
            })
            .setOnCancelListener(dialog -> {
                manuallyPaused = false;
                resumeEmulator();
            })
            .show();
    }

    private void returnToLauncher() {
        manuallyPaused = false;
        Intent intent = new Intent(this, SplashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
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
        if (!emulatorStarted) {
            return;
        }
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
        if (emulatorStarted && !emulatorInitializing) {
            pauseEmulator();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        debugModeEnabled = prefs.getBoolean(SettingsActivity.KEY_DEBUG_OVERLAY_ENABLED, false);
        bezelEnabled = prefs.getBoolean(SettingsActivity.KEY_BEZEL_ENABLED, true);
        crtShaderEnabled = prefs.getBoolean("crt_shader_enabled", false);
        nearestFiltering = prefs.getBoolean("nearest_filtering", false);
        
        applyDebugModeUi();
        applyBezelVisibility();
        
        // Update renderer if it's already initialized
        setFiltering(nearestFiltering);
        setCrtShaderEnabled(crtShaderEnabled);
        updateButtonLabels();
        updateStatusIndicator();

        if (emulatorStarted && !emulatorInitializing && !manuallyPaused) {
            resumeEmulator();
        }
    }

    @Override
    protected void onDestroy() {
        autoSaveNVRAM();
        super.onDestroy();
        stopFpsCounter();
        stopAudioPlayback();
        emulatorStarted = false;
        shutdownEmulator();
        cleanupRenderer();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Handle orientation changes for emulator
        DeviceOrientationManager.handleEmulatorOrientationChange(this);

        // Push the current screen rotation to the native renderer so the
        // Vulkan renderer (in particular) can orient its output correctly.
        // Respects the user-selected "Display Rotation" in Settings; falls
        // back to the device's current orientation when set to Auto.
        setRotation(getEffectiveRotation());

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
    private native String getStatus();
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
    private native void setVulkanDriverPath(String path);
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
    private native void setRotation(int degrees);
    private native int consumeRenderedFrames();

    /**
     * Always 0 — the user-facing rotate control was removed. Orientation is
     * handled correctly by the Vulkan IDENTITY pre-transform (compositor rotates
     * the landscape image to the display, like GLES) plus the no-flip fix, so no
     * shader rotation is ever needed. Applying any here would re-introduce the
     * old "sideways / mirrored" bug.
     */
    private int getEffectiveRotation() {
        return 0;
    }
    
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
