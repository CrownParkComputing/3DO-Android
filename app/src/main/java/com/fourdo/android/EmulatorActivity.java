package com.fourdo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class EmulatorActivity extends AppCompatActivity {

    private static final int MAX_RESOLUTION_SCALE = 9;
    private static final int MAX_CONCURRENT_IGDB_REQUESTS = 3;
    private static final int MAX_IGDB_LOOKUPS_PER_SCAN = 120;
    private static final long TOP_CONTROLS_AUTO_HIDE_MS = 3000L;
    private static final String KEY_VIRTUAL_PAD_VISIBLE = "virtual_pad_visible";
    private static final int[] VIRTUAL_PAD_BUTTONS = {
            ControllerMappingManager.BUTTON_A,
            ControllerMappingManager.BUTTON_B,
            ControllerMappingManager.BUTTON_C,
            ControllerMappingManager.BUTTON_PLAY_PAUSE,
            ControllerMappingManager.BUTTON_STOP,
            ControllerMappingManager.BUTTON_DPAD_UP,
            ControllerMappingManager.BUTTON_DPAD_DOWN,
            ControllerMappingManager.BUTTON_DPAD_LEFT,
            ControllerMappingManager.BUTTON_DPAD_RIGHT,
            ControllerMappingManager.BUTTON_L1,
            ControllerMappingManager.BUTTON_R1
    };

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
    private View settingsPanel;
    private FrameLayout rootView;
    private FrameLayout screenContainer;
    private Button topRendererButton;
    private Button topAspectButton;
    private Button topCrtButton;
    private Button topLibraryButton;
    private Button topPadButton;
    private Button topSettingsButton;
    private View topControlsBar;
    private View virtualPadOverlay;
    private View gameLibraryScreen;
    private EditText gameLibrarySearch;
    private GridLayout gameLibraryGrid;
    private HorizontalScrollView gameLibraryCarouselScroll;
    private LinearLayout gameLibraryCarousel;
    private TextView gameLibraryStatus;
    private Button gameLibraryViewButton;
    private final List<GameLibraryEntry> currentGameLibrary = new ArrayList<>();
    private IgdbService igdbService;
    private int igdbNextIndex;
    private int igdbInFlight;
    private boolean gameLibraryCarouselMode;
    private Button sideCloseButton;
    private View sideLibraryButton;
    private View sideDownloadBezelsButton;
    private View sideControllerButton;
    private View sideVirtualPadButton;
    private View sideResetButton;
    private TextView sideStatusText;

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
    private final Runnable hideTopControlsRunnable = () -> {
        if (topControlsBar == null) {
            return;
        }
        if (hasVisibleRuntimeOverlay()) {
            scheduleTopControlsAutoHide();
            return;
        }
        topControlsBar.setVisibility(View.GONE);
    };
    private boolean debugModeEnabled = false;
    private boolean manuallyPaused = false;
    private boolean overlayPaused = false;
    private boolean emulatorInitializing = false;
    private boolean emulatorStarted = false;
    
    // Settings state
    private boolean aspectRatio16by9 = false;  // false = 4:3, true = 16:9
    private int currentRenderer = RENDERER_VULKAN;
    private boolean nearestFiltering = false;  // false = linear, true = nearest
    private int antiAliasingMode = 0;          // 0=off,1=low,2=high
    private boolean crtShaderEnabled = false;
    private boolean bezelEnabled = true;
    private boolean virtualPadVisible = false;
    private String currentBezelPath = "";
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
        initializeUiState();
        setupActivityButtons();
        setupEmulatorSurface();
        handleStartupIntent(getIntent());
    }

    private void configureWindowAndLayout() {
        DeviceOrientationManager.setOptimalEmulatorOrientation(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            appVersion = "?";
        }
        setContentView(createContentView());
    }

    private void initializeUiState() {
        loadSettings();
        applyVirtualPadVisibility();
        applyBezelVisibility();
        applyDebugModeUi();
        updateButtonLabels();
        startFpsCounter();
        setupQuickToolbar();
    }

    private void setupActivityButtons() {
        if (pauseButton != null) {
            pauseButton.setOnClickListener(v -> showPauseMenu());
        }

        if (quickSettingsButton != null) {
            quickSettingsButton.setOnClickListener(v -> toggleSettingsPanel());
        }
        if (topRendererButton != null) topRendererButton.setOnClickListener(v -> {
            cycleRenderer();
            scheduleTopControlsAutoHide();
        });
        if (topAspectButton != null) topAspectButton.setOnClickListener(v -> {
            toggleAspectMode();
            scheduleTopControlsAutoHide();
        });
        if (topCrtButton != null) topCrtButton.setOnClickListener(v -> {
            toggleCrtShader();
            scheduleTopControlsAutoHide();
        });
        if (topLibraryButton != null) topLibraryButton.setOnClickListener(v -> {
            openGameLibrary();
            scheduleTopControlsAutoHide();
        });
        if (topPadButton != null) topPadButton.setOnClickListener(v -> {
            toggleVirtualPad();
            scheduleTopControlsAutoHide();
        });
        if (topSettingsButton != null) topSettingsButton.setOnClickListener(v -> {
            toggleSettingsPanel();
            scheduleTopControlsAutoHide();
        });
        if (sideCloseButton != null) sideCloseButton.setOnClickListener(v -> toggleSettingsPanel());
        if (sideLibraryButton != null) sideLibraryButton.setOnClickListener(v -> openGameLibrary());
        if (sideDownloadBezelsButton != null) sideDownloadBezelsButton.setOnClickListener(v -> showDownloadBezelsDialog());
        if (sideControllerButton != null) sideControllerButton.setOnClickListener(v -> openControllerMap());
        if (sideVirtualPadButton != null) sideVirtualPadButton.setOnClickListener(v -> toggleVirtualPad());
        if (sideResetButton != null) sideResetButton.setOnClickListener(v -> bootBios());
    }


    private void setupEmulatorSurface() {
        FrameLayout surfaceContainer = screenContainer;
        emulatorSurfaceView = new SurfaceView(this);
        emulatorSurfaceView.setClickable(true);
        emulatorSurfaceView.setOnClickListener(v -> showTopControlsTemporarily());
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

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(0xFF050607);

        screenContainer = new FrameLayout(this);
        screenContainer.setBackgroundColor(0xFF000000);
        root.addView(screenContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        emulatorBezel = new ImageView(this);
        emulatorBezel.setScaleType(ImageView.ScaleType.FIT_XY);
        emulatorBezel.setAdjustViewBounds(false);
        emulatorBezel.setClickable(false);
        emulatorBezel.setFocusable(false);
        emulatorBezel.setImageResource(R.drawable.bezel_fz10);
        emulatorBezel.setVisibility(bezelEnabled ? View.VISIBLE : View.GONE);
        root.addView(emulatorBezel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        virtualPadOverlay = createVirtualPadOverlay();
        virtualPadOverlay.setVisibility(virtualPadVisible ? View.VISIBLE : View.GONE);
        root.addView(virtualPadOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(0xAA181B1F);
        topControlsBar = bar;

        fpsCounter = new TextView(this);
        fpsCounter.setTextColor(0xFFE8EAED);
        fpsCounter.setTextSize(12.0f);
        fpsCounter.setPadding(dp(8), 0, dp(8), 0);
        fpsCounter.setText("FPS 0");
        bar.addView(fpsCounter);

        topRendererButton = makeCompactButton(getRendererShortName(currentRenderer), v -> cycleRenderer());
        bar.addView(topRendererButton);
        topAspectButton = makeCompactButton(aspectRatio16by9 ? "16:9" : "4:3", v -> toggleAspectMode());
        bar.addView(topAspectButton);
        topCrtButton = makeCompactButton(crtShaderEnabled ? "CRT" : "No CRT", v -> toggleCrtShader());
        bar.addView(topCrtButton);
        topLibraryButton = makeCompactButton("Library", v -> openLibraryGamePickerForStartup());
        bar.addView(topLibraryButton);
        topPadButton = makeCompactButton(virtualPadVisible ? "Pad" : "No Pad", v -> toggleVirtualPad());
        bar.addView(topPadButton);
        topSettingsButton = makeCompactButton("Settings", v -> toggleSettingsPanel());
        bar.addView(topSettingsButton);

        FrameLayout.LayoutParams playBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        playBarParams.setMargins(0, dp(8), dp(8), 0);
        root.addView(bar, playBarParams);
        scheduleTopControlsAutoHide();

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setVisibility(View.GONE);
        settingsScroll.setFillViewport(false);
        settingsScroll.setBackgroundColor(0xEE181B1F);
        settingsPanel = settingsScroll;

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(14));
        settingsScroll.addView(controls);

        LinearLayout settingsHeader = new LinearLayout(this);
        settingsHeader.setGravity(Gravity.CENTER_VERTICAL);
        settingsHeader.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("4DO Settings");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16.0f);
        settingsHeader.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        sideCloseButton = makeCompactButton("X", v -> settingsPanel.setVisibility(View.GONE));
        settingsHeader.addView(sideCloseButton);
        controls.addView(settingsHeader);

        sideLibraryButton = makeSettingsActionCard("▦", "Game Library", "Browse cards, bezels, and IGDB", v -> openGameLibrary());
        controls.addView(sideLibraryButton);
        sideDownloadBezelsButton = makeSettingsActionCard("⇩", "Download Bezels", "Pull 3DO bezel pack from GitHub", v -> showDownloadBezelsDialog());
        controls.addView(sideDownloadBezelsButton);
        sideControllerButton = makeSettingsActionCard("PAD", "Controller Mapping", "Map device buttons", v -> openControllerMap());
        controls.addView(sideControllerButton);
        sideVirtualPadButton = makeSettingsActionCard("▣", "Virtual Pad", "Show translucent 3DO controls", v -> toggleVirtualPad());
        controls.addView(sideVirtualPadButton);
        sideResetButton = makeSettingsActionCard("↻", "Reset / Boot BIOS", "Restart to BIOS without a disc", v -> bootBios());
        controls.addView(sideResetButton);

        sideStatusText = new TextView(this);
        sideStatusText.setTextColor(0xFFE8EAED);
        sideStatusText.setTextSize(12.0f);
        sideStatusText.setPadding(0, dp(10), 0, 0);
        controls.addView(sideStatusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                dp(360),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.RIGHT);
        root.addView(settingsScroll, settingsParams);

        gameLibraryScreen = createGameLibraryScreen();
        gameLibraryScreen.setVisibility(View.GONE);
        root.addView(gameLibraryScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        loadingOverlay = new LinearLayout(this);
        ((LinearLayout) loadingOverlay).setGravity(Gravity.CENTER);
        ((LinearLayout) loadingOverlay).setOrientation(LinearLayout.VERTICAL);
        loadingOverlay.setBackgroundColor(0x99000000);
        loadingOverlay.setVisibility(View.GONE);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this);
        ((LinearLayout) loadingOverlay).addView(progress);
        loadingText = new TextView(this);
        loadingText.setText("Starting emulator...");
        loadingText.setTextColor(0xFFFFFFFF);
        loadingText.setTextSize(16.0f);
        loadingText.setPadding(0, dp(12), 0, 0);
        ((LinearLayout) loadingOverlay).addView(loadingText);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusIndicator = new TextView(this);
        statusIndicator.setTextColor(0xFFFFFFFF);
        statusIndicator.setTextSize(12.0f);
        statusIndicator.setBackgroundColor(0xAA181B1F);
        statusIndicator.setPadding(dp(8), dp(6), dp(8), dp(6));
        statusIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        statusParams.setMargins(0, 0, dp(8), dp(8));
        root.addView(statusIndicator, statusParams);

        return root;
    }

    private View createGameLibraryScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(18), dp(12), dp(18), dp(12));
        screen.setBackgroundColor(0xFA0B0D10);
        screen.setClickable(true);
        screen.setFocusable(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Game Library");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18.0f);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));

        gameLibraryViewButton = makeCompactButton("Carousel", v -> toggleGameLibraryView());
        header.addView(gameLibraryViewButton);
        header.addView(makeCompactButton("Refresh", v -> refreshGameLibraryScreen()));
        header.addView(makeCompactButton("Bezels", v -> showDownloadBezelsDialog()));
        header.addView(makeCompactButton("X", v -> closeGameLibraryScreen()));
        screen.addView(header);

        gameLibrarySearch = new EditText(this);
        gameLibrarySearch.setSingleLine(true);
        gameLibrarySearch.setHint("Search games");
        gameLibrarySearch.setTextColor(0xFFFFFFFF);
        gameLibrarySearch.setHintTextColor(0xFF8C939D);
        gameLibrarySearch.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42));
        searchParams.setMargins(0, dp(10), 0, dp(8));
        screen.addView(gameLibrarySearch, searchParams);

        gameLibraryStatus = new TextView(this);
        gameLibraryStatus.setTextColor(0xFFBAC2CC);
        gameLibraryStatus.setTextSize(12.0f);
        gameLibraryStatus.setPadding(0, 0, 0, dp(8));
        screen.addView(gameLibraryStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        gameLibraryGrid = new GridLayout(this);
        gameLibraryGrid.setColumnCount(4);
        gameLibraryGrid.setUseDefaultMargins(false);
        scroll.addView(gameLibraryGrid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        gameLibraryCarouselScroll = new HorizontalScrollView(this);
        gameLibraryCarouselScroll.setFillViewport(false);
        gameLibraryCarouselScroll.setVisibility(View.GONE);
        gameLibraryCarousel = new LinearLayout(this);
        gameLibraryCarousel.setOrientation(LinearLayout.HORIZONTAL);
        gameLibraryCarousel.setGravity(Gravity.CENTER_VERTICAL);
        gameLibraryCarouselScroll.addView(gameLibraryCarousel, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        screen.addView(gameLibraryCarouselScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        gameLibrarySearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderGameLibraryCards();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        return screen;
    }

    private View createVirtualPadOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setFocusable(false);
        overlay.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                showTopControlsTemporarily();
            }
            return true;
        });

        placePadButton(overlay, makeVirtualPadButton("L", ControllerMappingManager.BUTTON_L1, dp(118), dp(42)),
                Gravity.LEFT | Gravity.TOP, dp(82), dp(58), 0, 0);
        placePadButton(overlay, makeVirtualPadButton("R", ControllerMappingManager.BUTTON_R1, dp(118), dp(42)),
                Gravity.RIGHT | Gravity.TOP, 0, dp(58), dp(82), 0);

        int dpadLeft = dp(82);
        int dpadBottom = dp(36);
        int pad = dp(52);
        placePadButton(overlay, makeVirtualPadButton("↑", ControllerMappingManager.BUTTON_DPAD_UP, pad, pad),
                Gravity.LEFT | Gravity.BOTTOM, dpadLeft + pad, 0, 0, dpadBottom + pad);
        placePadButton(overlay, makeVirtualPadButton("←", ControllerMappingManager.BUTTON_DPAD_LEFT, pad, pad),
                Gravity.LEFT | Gravity.BOTTOM, dpadLeft, 0, 0, dpadBottom);
        placePadButton(overlay, makeVirtualPadButton("↓", ControllerMappingManager.BUTTON_DPAD_DOWN, pad, pad),
                Gravity.LEFT | Gravity.BOTTOM, dpadLeft + pad, 0, 0, dpadBottom);
        placePadButton(overlay, makeVirtualPadButton("→", ControllerMappingManager.BUTTON_DPAD_RIGHT, pad, pad),
                Gravity.LEFT | Gravity.BOTTOM, dpadLeft + pad * 2, 0, 0, dpadBottom);

        placePadButton(overlay, makeVirtualPadButton("P", ControllerMappingManager.BUTTON_PLAY_PAUSE, dp(64), dp(38)),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, dp(42), dp(32));
        placePadButton(overlay, makeVirtualPadButton("X", ControllerMappingManager.BUTTON_STOP, dp(64), dp(38)),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, dp(42), 0, 0, dp(32));

        int faceRight = dp(82);
        int faceBottom = dp(36);
        int face = dp(60);
        int gap = dp(6);
        placePadButton(overlay, makeVirtualPadButton("C", ControllerMappingManager.BUTTON_C, face, face),
                Gravity.RIGHT | Gravity.BOTTOM, 0, 0, faceRight, faceBottom + (face + gap) * 2);
        placePadButton(overlay, makeVirtualPadButton("B", ControllerMappingManager.BUTTON_B, face, face),
                Gravity.RIGHT | Gravity.BOTTOM, 0, 0, faceRight + face + gap, faceBottom + face + gap);
        placePadButton(overlay, makeVirtualPadButton("A", ControllerMappingManager.BUTTON_A, face, face),
                Gravity.RIGHT | Gravity.BOTTOM, 0, 0, faceRight + (face + gap) * 2, faceBottom);
        return overlay;
    }

    private void placePadButton(FrameLayout overlay, Button button, int gravity,
                                int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity);
        params.setMargins(left, top, right, bottom);
        overlay.addView(button, params);
    }

    private Button makeVirtualPadButton(String label, int inputButton, int width, int height) {
        Button button = makeCompactButton(label, null);
        button.setMinWidth(width);
        button.setMinimumWidth(width);
        button.setMinHeight(height);
        button.setMinimumHeight(height);
        button.setTextColor(0xEEFFFFFF);
        button.setTextSize(height >= dp(54) ? 18.0f : 13.0f);
        button.setBackground(virtualPadButtonBackground(false));
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    setInputState(inputButton, true);
                    view.setPressed(true);
                    view.setBackground(virtualPadButtonBackground(true));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    setInputState(inputButton, false);
                    view.setPressed(false);
                    view.setBackground(virtualPadButtonBackground(false));
                    return true;
                default:
                    return true;
            }
        });
        return button;
    }

    private GradientDrawable virtualPadButtonBackground(boolean pressed) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(999));
        drawable.setColor(pressed ? 0x99C5CBD3 : 0x665F6670);
        drawable.setStroke(dp(1), pressed ? 0xEEFFFFFF : 0x99D6DADF);
        return drawable;
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setMinHeight(48);
        return button;
    }

    private Button makeCompactButton(String label, View.OnClickListener listener) {
        Button button = makeButton(label, listener);
        button.setTextSize(12.0f);
        button.setMinWidth(dp(64));
        button.setMinimumWidth(dp(64));
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private View makeSettingsActionCard(String icon, String title, String subtitle, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBackground(0xFF22272E, 0xFF3D4652));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextColor(0xFFFFFFFF);
        iconView.setTextSize(18.0f);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(cardBackground(0xFF303844, 0xFF596474));
        card.addView(iconView, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        textColumn.setPadding(dp(12), 0, 0, 0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(14.0f);
        titleView.setMaxLines(1);
        textColumn.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(0xFFBAC2CC);
        subtitleView.setTextSize(11.0f);
        subtitleView.setMaxLines(2);
        textColumn.addView(subtitleView);

        card.addView(textColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76));
        params.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private GradientDrawable cardBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        if (!EmulatorPathStore.isSupportedCdPath(mGamePath)) {
            String lastGamePath = EmulatorPathStore.getSavedLastGamePath(this);
            if (EmulatorPathStore.isSupportedCdPath(lastGamePath)) {
                mGamePath = lastGamePath;
            } else {
                mGamePath = null;
            }
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

    private void showTopControlsTemporarily() {
        if (topControlsBar == null) {
            return;
        }
        topControlsBar.setVisibility(View.VISIBLE);
        topControlsBar.bringToFront();
        scheduleTopControlsAutoHide();
    }

    private void scheduleTopControlsAutoHide() {
        fpsHandler.removeCallbacks(hideTopControlsRunnable);
        fpsHandler.postDelayed(hideTopControlsRunnable, TOP_CONTROLS_AUTO_HIDE_MS);
    }

    private void toggleSettingsPanel() {
        if (settingsPanel == null) {
            return;
        }
        setSettingsPanelVisible(settingsPanel.getVisibility() != View.VISIBLE);
        updateButtonLabels();
    }

    private void toggleVirtualPad() {
        virtualPadVisible = !virtualPadVisible;
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VIRTUAL_PAD_VISIBLE, virtualPadVisible)
                .apply();
        applyVirtualPadVisibility();
        updateButtonLabels();
    }

    private void applyVirtualPadVisibility() {
        if (virtualPadOverlay == null) {
            return;
        }
        if (!virtualPadVisible) {
            releaseVirtualPadButtons();
        }
        virtualPadOverlay.setVisibility(virtualPadVisible ? View.VISIBLE : View.GONE);
    }

    private void releaseVirtualPadButtons() {
        if (!emulatorStarted) {
            return;
        }
        for (int button : VIRTUAL_PAD_BUTTONS) {
            setInputState(button, false);
        }
    }

    private void setSettingsPanelVisible(boolean visible) {
        if (settingsPanel == null) {
            return;
        }
        if (visible) {
            pauseForOverlay();
            settingsPanel.setVisibility(View.VISIBLE);
            settingsPanel.bringToFront();
        } else {
            settingsPanel.setVisibility(View.GONE);
            resumeAfterOverlayIfIdle();
        }
    }

    private void setGameLibraryVisible(boolean visible) {
        if (gameLibraryScreen == null) {
            return;
        }
        if (visible) {
            pauseForOverlay();
            gameLibraryScreen.setVisibility(View.VISIBLE);
            gameLibraryScreen.bringToFront();
        } else {
            gameLibraryScreen.setVisibility(View.GONE);
            resumeAfterOverlayIfIdle();
        }
    }

    private void pauseForOverlay() {
        if (!overlayPaused && emulatorStarted && !emulatorInitializing && !manuallyPaused) {
            pauseEmulator();
            overlayPaused = true;
        }
    }

    private void resumeAfterOverlayIfIdle() {
        if (!overlayPaused || hasVisibleRuntimeOverlay()) {
            return;
        }
        overlayPaused = false;
        if (emulatorStarted && !emulatorInitializing && !manuallyPaused) {
            resumeEmulator();
        }
    }

    private boolean hasVisibleRuntimeOverlay() {
        return (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE)
                || (gameLibraryScreen != null && gameLibraryScreen.getVisibility() == View.VISIBLE);
    }

    private void openGameLibrary() {
        String libraryPath = EmulatorPathStore.getSavedLibraryFolder(this);
        if (!EmulatorPathStore.isValidDirectoryPath(libraryPath)) {
            startActivity(new Intent(this, SettingsActivity.class));
            Toast.makeText(this, "Set a game library folder first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
        }
        setGameLibraryVisible(true);
        if (gameLibrarySearch != null) {
            gameLibrarySearch.setText("");
        }
        refreshGameLibraryScreen();
    }

    private void showDownloadBezelsDialog() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(12), dp(24), 0);
        layout.setBackgroundColor(0xFF101418);

        TextView hint = new TextView(this);
        hint.setText("Download per-game 3DO bezel PNGs into app storage.");
        hint.setTextColor(0xFFE8EAED);
        hint.setTextSize(13.0f);
        layout.addView(hint);

        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF8C939D);
        input.setText(prefs.getString(SettingsActivity.KEY_BEZEL_GITHUB_URL, BezelDownloader.DEFAULT_GITHUB_ZIP_URL));
        layout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Download Bezels")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "GitHub URL is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(SettingsActivity.KEY_BEZEL_GITHUB_URL, url).apply();
                    downloadBezelsFromGithub(url);
                })
                .show();
    }

    private void downloadBezelsFromGithub(String url) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(18), dp(24), dp(8));
        layout.setBackgroundColor(0xFF101418);

        TextView status = new TextView(this);
        status.setText("Starting download...");
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(14.0f);
        layout.addView(status);

        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(18));
        progressParams.setMargins(0, dp(14), 0, dp(8));
        layout.addView(progress, progressParams);

        TextView detail = new TextView(this);
        detail.setTextColor(0xFFBAC2CC);
        detail.setTextSize(12.0f);
        layout.addView(detail);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("Downloading Bezels")
                .setView(layout)
                .setCancelable(false)
                .create();
        progressDialog.setOnShowListener(dialog -> {
            if (progressDialog.getWindow() != null) {
                progressDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        progressDialog.show();

        new Thread(() -> {
            try {
                BezelDownloader.Result result = BezelDownloader.downloadGithubZip(this, url, (phase, current, total, indeterminate) ->
                        runOnUiThread(() -> updateBezelDownloadProgress(status, detail, progress, phase, current, total, indeterminate)));
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    currentBezelPath = "";
                    applyBezelVisibility();
                    renderGameLibraryCards();
                    updateButtonLabels();
                    Toast.makeText(this, "Downloaded " + result.pngCount + " bezels", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "Bezel download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "emulator-bezel-download").start();
    }

    private void updateBezelDownloadProgress(TextView status, TextView detail, android.widget.ProgressBar progress,
                                             String phase, long current, long total, boolean indeterminate) {
        status.setText(phase);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate && total > 0) {
            progress.setMax((int) Math.min(total, Integer.MAX_VALUE));
            progress.setProgress((int) Math.min(current, Integer.MAX_VALUE));
            detail.setText(formatProgressValue(current, total));
        } else if (current > 0) {
            detail.setText(formatBytes(current));
        } else {
            detail.setText("Working...");
        }
    }

    private String formatProgressValue(long current, long total) {
        if (total <= 0) {
            return formatBytes(current);
        }
        if (total < 2048) {
            return current + " / " + total;
        }
        return formatBytes(current) + " / " + formatBytes(total);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private void refreshGameLibraryScreen() {
        if (gameLibraryStatus != null) {
            gameLibraryStatus.setText("Scanning 4DO library...");
        }
        currentGameLibrary.clear();
        renderGameLibraryCards();

        new Thread(() -> {
            List<GameLibraryEntry> games = scanGameLibrary();
            runOnUiThread(() -> {
                currentGameLibrary.clear();
                currentGameLibrary.addAll(games);
                renderGameLibraryCards();
                beginIgdbMatching();
            });
        }, "4do-game-library-scan").start();
    }

    private void closeGameLibraryScreen() {
        setGameLibraryVisible(false);
    }

    private void renderGameLibraryCards() {
        if (gameLibraryGrid == null || gameLibraryCarousel == null || gameLibraryStatus == null) {
            return;
        }

        gameLibraryGrid.removeAllViews();
        gameLibraryCarousel.removeAllViews();
        if (gameLibraryCarouselScroll != null) {
            gameLibraryCarouselScroll.setVisibility(gameLibraryCarouselMode ? View.VISIBLE : View.GONE);
        }
        gameLibraryGrid.setVisibility(gameLibraryCarouselMode ? View.GONE : View.VISIBLE);
        if (gameLibraryViewButton != null) {
            gameLibraryViewButton.setText(gameLibraryCarouselMode ? "Grid" : "Carousel");
        }

        String query = gameLibrarySearch == null ? "" : gameLibrarySearch.getText().toString();
        int shown = 0;
        for (GameLibraryEntry game : currentGameLibrary) {
            if (!matchesSearch(displayBaseName(game.displayName), query)) {
                continue;
            }
            if (gameLibraryCarouselMode) {
                gameLibraryCarousel.addView(createGameCard(game, true));
            } else {
                gameLibraryGrid.addView(createGameCard(game, false));
            }
            shown++;
        }

        if (currentGameLibrary.isEmpty()) {
            gameLibraryStatus.setText("No 3DO disc images found. | " + BezelResolver.describeDownloadedBezels(this));
        } else if (shown == currentGameLibrary.size()) {
            gameLibraryStatus.setText(shown + " 3DO games | " + BezelResolver.describeDownloadedBezels(this));
        } else {
            gameLibraryStatus.setText(shown + " of " + currentGameLibrary.size() + " 3DO games | "
                    + BezelResolver.describeDownloadedBezels(this));
        }
    }

    private void toggleGameLibraryView() {
        gameLibraryCarouselMode = !gameLibraryCarouselMode;
        renderGameLibraryCards();
    }

    private View createGameCard(GameLibraryEntry entry, boolean carousel) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBackground(0xFF191D22, 0xFF353B44));
        card.setOnClickListener(v -> showGameDetails(entry));
        card.setOnLongClickListener(v -> {
            showGameDetails(entry);
            return true;
        });
        card.setLongClickable(true);

        FrameLayout coverSlot = new FrameLayout(this);
        coverSlot.setBackground(cardBackground(0xFF262C34, 0xFF404853));
        if (entry.coverBitmap != null) {
            ImageView cover = new ImageView(this);
            cover.setImageBitmap(entry.coverBitmap);
            cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.setAdjustViewBounds(false);
            coverSlot.addView(cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            TextView coverLabel = new TextView(this);
            coverLabel.setText(entry.igdbLookupStarted ? "IGDB" : "3DO");
            coverLabel.setTextColor(0xFFB9C2CE);
            coverLabel.setTextSize(12.0f);
            coverLabel.setGravity(Gravity.CENTER);
            coverSlot.addView(coverLabel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        card.addView(coverSlot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                carousel ? dp(228) : dp(184)));

        TextView title = new TextView(this);
        title.setText(entry.igdbGame != null && entry.igdbGame.name != null && !entry.igdbGame.name.isEmpty()
                ? entry.igdbGame.name
                : displayBaseName(entry.displayName));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12.0f);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setPadding(0, dp(4), 0, 0);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)));

        TextView detail = new TextView(this);
        detail.setText(gameCardDetail(entry));
        detail.setTextColor(0xFF9AA3AF);
        detail.setTextSize(10.0f);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(1);
        card.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(16)));

        if (carousel) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(192), dp(300));
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
            card.setLayoutParams(params);
        } else {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(168);
            params.height = dp(256);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.setLayoutParams(params);
        }
        return card;
    }

    private String gameCardDetail(GameLibraryEntry entry) {
        String bezelLabel = BezelResolver.findBezelForGame(this, entry.file.getAbsolutePath()) == null ? "" : "Bezel";
        if (entry.igdbGame == null) {
            String format = fileExtensionLabel(entry.displayName);
            return bezelLabel.isEmpty() ? format : format + " | " + bezelLabel;
        }
        StringBuilder detail = new StringBuilder();
        if (entry.igdbGame.releaseDate != null && !entry.igdbGame.releaseDate.isEmpty()) {
            detail.append(entry.igdbGame.releaseDate);
        }
        if (entry.igdbGame.publisher != null && !entry.igdbGame.publisher.isEmpty()) {
            if (detail.length() > 0) {
                detail.append(" | ");
            }
            detail.append(entry.igdbGame.publisher);
        }
        if (!bezelLabel.isEmpty()) {
            if (detail.length() > 0) {
                detail.append(" | ");
            }
            detail.append(bezelLabel);
        }
        return detail.length() == 0 ? fileExtensionLabel(entry.displayName) : detail.toString();
    }

    private List<GameLibraryEntry> scanGameLibrary() {
        List<GameLibraryEntry> games = new ArrayList<>();
        String libraryPath = EmulatorPathStore.getSavedLibraryFolder(this);
        File root = new File(libraryPath);
        if (!root.isDirectory()) {
            return games;
        }
        scanGameDirectory(root, games, 0);
        games.sort(Comparator.comparing(entry -> entry.displayName.toLowerCase(Locale.US)));
        return games;
    }

    private void scanGameDirectory(File directory, List<GameLibraryEntry> games, int depth) {
        if (directory == null || depth > 6 || games.size() >= 1000) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (games.size() >= 1000) {
                return;
            }
            if (file.isDirectory() && !file.getName().startsWith(".")) {
                scanGameDirectory(file, games, depth + 1);
            } else if (isSupportedLibraryGame(file)) {
                games.add(new GameLibraryEntry(file.getName(), file));
            }
        }
    }

    private boolean isSupportedLibraryGame(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".cue")
                || name.endsWith(".iso")
                || name.endsWith(".chd")
                || (name.endsWith(".bin") && !hasCueForBin(file));
    }

    private boolean hasCueForBin(File binFile) {
        File parent = binFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return false;
        }

        String binName = binFile.getName();
        int dot = binName.lastIndexOf('.');
        String baseName = dot > 0 ? binName.substring(0, dot) : binName;
        File sameBaseCue = new File(parent, baseName + ".cue");
        if (sameBaseCue.isFile()) {
            return true;
        }

        File[] cueFiles = parent.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".cue"));
        if (cueFiles == null) {
            return false;
        }

        for (File cueFile : cueFiles) {
            if (cueReferencesFile(cueFile, binName)) {
                return true;
            }
        }
        return false;
    }

    private boolean cueReferencesFile(File cueFile, String fileName) {
        try (BufferedReader reader = new BufferedReader(new FileReader(cueFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.regionMatches(true, 0, "FILE", 0, 4)) {
                    continue;
                }
                String referencedName = parseCueFileReference(trimmed);
                if (referencedName != null && new File(referencedName).getName().equalsIgnoreCase(fileName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            Log.w("4DO", "Failed reading cue file for BIN pairing", e);
        }
        return false;
    }

    private String parseCueFileReference(String trimmedLine) {
        if (trimmedLine == null || !trimmedLine.regionMatches(true, 0, "FILE", 0, 4)) {
            return null;
        }

        String rest = trimmedLine.substring(4).trim();
        if (rest.isEmpty()) {
            return null;
        }

        if (rest.charAt(0) == '"') {
            int secondQuote = rest.indexOf('"', 1);
            if (secondQuote <= 1) {
                return null;
            }
            return rest.substring(1, secondQuote);
        }

        int lastSpace = rest.lastIndexOf(' ');
        return lastSpace > 0 ? rest.substring(0, lastSpace).trim() : rest;
    }

    private void loadGameFromLibrary(GameLibraryEntry entry) {
        if (entry == null || entry.file == null || !entry.file.isFile()) {
            Toast.makeText(this, "Game file not found", Toast.LENGTH_SHORT).show();
            return;
        }
        mGamePath = entry.file.getAbsolutePath();
        EmulatorPathStore.saveLastGamePath(this, mGamePath);
        currentBezelPath = "";
        applyBezelVisibility();
        if (gameLibraryScreen != null) {
            gameLibraryScreen.setVisibility(View.GONE);
        }
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
        }
        overlayPaused = false;
        performHardReset(mGamePath);
    }

    private void beginIgdbMatching() {
        if (currentGameLibrary.isEmpty()) {
            return;
        }
        if (igdbService == null) {
            igdbService = IgdbService.getInstance(this);
        }
        igdbNextIndex = 0;
        igdbInFlight = 0;
        pumpIgdbQueue();
    }

    private void pumpIgdbQueue() {
        while (igdbInFlight < MAX_CONCURRENT_IGDB_REQUESTS
                && igdbNextIndex < currentGameLibrary.size()
                && igdbNextIndex < MAX_IGDB_LOOKUPS_PER_SCAN) {
            GameLibraryEntry entry = currentGameLibrary.get(igdbNextIndex++);
            if (entry.igdbLookupStarted) {
                continue;
            }
            entry.igdbLookupStarted = true;
            igdbInFlight++;
            searchGameOnIgdb(entry);
        }
    }

    private void searchGameOnIgdb(GameLibraryEntry entry) {
        String queryName = buildIgdbQueryName(entry.displayName);
        igdbService.lookupGame(queryName, game -> {
            entry.igdbGame = game;
            if (game != null && game.coverUrl != null && !game.coverUrl.isEmpty()) {
                igdbService.loadCover(game.coverUrl, game.id, (cover, localPath) -> {
                    entry.coverBitmap = cover;
                    igdbInFlight--;
                    renderGameLibraryCards();
                    pumpIgdbQueue();
                });
            } else {
                igdbInFlight--;
                renderGameLibraryCards();
                pumpIgdbQueue();
            }
        });
    }

    private void showGameDetails(GameLibraryEntry entry) {
        if (igdbService == null) {
            igdbService = IgdbService.getInstance(this);
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        int padding = dp(12);
        container.setPadding(padding, padding, padding, padding);
        container.setBackgroundColor(0xF8101418);

        LinearLayout leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        leftColumn.setPadding(0, 0, dp(10), 0);
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(false);
        leftScroll.setScrollbarFadingEnabled(false);
        leftScroll.addView(leftColumn, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.setPadding(dp(10), 0, 0, 0);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, 0, 0, dp(8));
        Button loadButton = makeCompactButton("Load", null);
        actionRow.addView(loadButton, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        Button searchMissingButton = null;
        if (entry.igdbGame == null) {
            searchMissingButton = makeCompactButton("Search IGDB", null);
            LinearLayout.LayoutParams searchMissingParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f);
            searchMissingParams.setMargins(dp(8), 0, 0, 0);
            actionRow.addView(searchMissingButton, searchMissingParams);
        }
        Button matchBezelButton = null;
        if (BezelResolver.findBezelForGame(this, entry.file.getAbsolutePath()) == null) {
            matchBezelButton = makeCompactButton("Match Bezel", null);
            LinearLayout.LayoutParams matchParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f);
            matchParams.setMargins(dp(8), 0, 0, 0);
            actionRow.addView(matchBezelButton, matchParams);
        }
        leftColumn.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView bezelStatus = new TextView(this);
        bezelStatus.setText(BezelResolver.describeBezelDiagnostic(this, entry.file.getAbsolutePath()));
        bezelStatus.setTextColor(0xFFBAC2CC);
        bezelStatus.setTextSize(11.0f);
        bezelStatus.setPadding(0, 0, 0, dp(8));
        leftColumn.addView(bezelStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView details = new TextView(this);
        details.setText(formatIgdbDetails(entry));
        details.setTextColor(0xFFE8EAED);
        details.setTextSize(13.0f);
        details.setPadding(0, 0, 0, dp(10));
        leftColumn.addView(details, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Manual IGDB search");
        search.setText(buildIgdbQueryName(entry.displayName));
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0xFF8C939D);
        search.setSelectAllOnFocus(true);
        leftColumn.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button searchButton = makeCompactButton("Search 3DO IGDB", null);
        leftColumn.addView(searchButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView resultsList = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView text = row.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setTextColor(0xFFE8EAED);
                    text.setTextSize(13.0f);
                }
                row.setBackgroundColor(0xFF101418);
                return row;
            }
        };
        List<IgdbService.IgdbGame> results = new ArrayList<>();
        resultsList.setAdapter(adapter);
        resultsList.setBackgroundColor(0xFF101418);
        resultsList.setCacheColorHint(0xFF101418);
        leftColumn.addView(resultsList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)));

        TextView mediaTitle = new TextView(this);
        mediaTitle.setText("Gameplay Images");
        mediaTitle.setTextColor(0xFFFFFFFF);
        mediaTitle.setTextSize(13.0f);
        mediaTitle.setPadding(0, 0, 0, dp(6));
        rightColumn.addView(mediaTitle);

        ScrollView mediaScroll = new ScrollView(this);
        mediaScroll.setFillViewport(false);
        LinearLayout mediaStrip = new LinearLayout(this);
        mediaStrip.setOrientation(LinearLayout.VERTICAL);
        mediaScroll.addView(mediaStrip, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        rightColumn.addView(mediaScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));
        populateIgdbMediaStrip(entry, mediaStrip);

        TextView mediaHint = new TextView(this);
        mediaHint.setText("Use IGDB search to refresh missing images");
        mediaHint.setTextColor(0xFFBAC2CC);
        mediaHint.setTextSize(11.0f);
        mediaHint.setPadding(0, dp(8), 0, 0);
        rightColumn.addView(mediaHint);

        container.addView(leftScroll, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.05f));
        container.addView(rightColumn, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                0.95f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(wrapGameDetailsWithBezel(entry, container))
                .setNegativeButton("Close", null)
                .create();

        loadButton.setOnClickListener(v -> {
            dialog.dismiss();
            loadGameFromLibrary(entry);
        });
        searchButton.setOnClickListener(v -> {
            if (!igdbService.hasCredentials()) {
                adapter.clear();
                adapter.add("IGDB credentials missing");
                adapter.notifyDataSetChanged();
                return;
            }
            String query = search.getText().toString().trim();
            adapter.clear();
            adapter.add("Searching 3DO IGDB...");
            adapter.notifyDataSetChanged();
            igdbService.searchGames(query, games -> {
                results.clear();
                adapter.clear();
                if (games.isEmpty()) {
                    adapter.add("No 3DO IGDB matches");
                } else {
                    results.addAll(games);
                    for (IgdbService.IgdbGame game : games) {
                        adapter.add(igdbResultLabel(game));
                    }
                }
                adapter.notifyDataSetChanged();
            });
        });
        Button finalSearchMissingButton = searchMissingButton;
        if (finalSearchMissingButton != null) {
            finalSearchMissingButton.setOnClickListener(v -> searchButton.performClick());
        }
        Button finalMatchBezelButton = matchBezelButton;
        if (finalMatchBezelButton != null) {
            finalMatchBezelButton.setOnClickListener(v -> {
                dialog.dismiss();
                showBezelMatchDialog(entry);
            });
        }
        resultsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= results.size()) {
                return;
            }
            IgdbService.IgdbGame game = results.get(position);
            entry.igdbGame = game;
            entry.igdbLookupStarted = true;
            igdbService.cacheGame(buildIgdbQueryName(entry.displayName), game);
            if (game.coverUrl != null && !game.coverUrl.isEmpty()) {
                igdbService.loadCover(game.coverUrl, game.id, (cover, localPath) -> {
                    entry.coverBitmap = cover;
                    renderGameLibraryCards();
                    populateIgdbMediaStrip(entry, mediaStrip);
                });
            }
            dialog.dismiss();
        });
        dialog.setOnShowListener(d -> {
            search.clearFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                dialog.getWindow().setLayout(
                        Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(980)),
                        Math.min(getResources().getDisplayMetrics().heightPixels - dp(24), dp(640)));
            }
            Button closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (closeButton != null) {
                closeButton.setTextColor(0xFFFFFFFF);
            }
        });
        dialog.show();
    }

    private void showBezelMatchDialog(GameLibraryEntry entry) {
        List<File> allBezels = BezelResolver.listDownloadedBezels(this);
        if (allBezels.isEmpty()) {
            Toast.makeText(this, "No downloaded bezels found", Toast.LENGTH_LONG).show();
            showGameDetails(entry);
            return;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackgroundColor(0xFF101418);

        TextView target = new TextView(this);
        target.setText("Game key: " + BezelResolver.expectedKeyForGame(entry.file.getAbsolutePath()));
        target.setTextColor(0xFFBAC2CC);
        target.setTextSize(12.0f);
        target.setPadding(0, 0, 0, dp(8));
        panel.addView(target);

        EditText filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("Filter downloaded bezels");
        filter.setText(buildIgdbQueryName(entry.displayName));
        filter.setSelectAllOnFocus(true);
        filter.setTextColor(0xFFFFFFFF);
        filter.setHintTextColor(0xFF8C939D);
        panel.addView(filter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        list.setBackgroundColor(0xFF101418);
        list.setCacheColorHint(0xFF101418);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView text = row.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setTextColor(0xFFE8EAED);
                    text.setTextSize(13.0f);
                }
                row.setBackgroundColor(0xFF101418);
                return row;
            }
        };
        List<File> filtered = new ArrayList<>();
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        renderBezelMatchCandidates(allBezels, filtered, adapter, filter.getText().toString());
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderBezelMatchCandidates(allBezels, filtered, adapter, s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Match Bezel")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) {
                return;
            }
            File selected = filtered.get(position);
            BezelResolver.saveManualBezelMatch(this, entry.file.getAbsolutePath(), selected);
            Toast.makeText(this, "Matched bezel: " + selected.getName(), Toast.LENGTH_SHORT).show();
            currentBezelPath = "";
            applyBezelVisibility();
            renderGameLibraryCards();
            dialog.dismiss();
            showGameDetails(entry);
        });

        dialog.setOnShowListener(d -> {
            filter.clearFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF101418));
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                dialog.getWindow().setLayout(
                        Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(820)),
                        Math.min(getResources().getDisplayMetrics().heightPixels - dp(36), dp(620)));
            }
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) {
                cancel.setTextColor(0xFFFFFFFF);
            }
        });
        dialog.setOnDismissListener(d -> {
            if (filter.hasFocus()) {
                filter.clearFocus();
            }
        });
        dialog.show();
    }

    private void renderBezelMatchCandidates(List<File> allBezels,
                                            List<File> filtered,
                                            ArrayAdapter<String> adapter,
                                            String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        filtered.clear();
        adapter.clear();
        for (File bezel : allBezels) {
            String name = bezel.getName();
            if (needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(bezel);
                adapter.add(displayBaseName(name));
            }
            if (filtered.size() >= 200) {
                break;
            }
        }
        if (filtered.isEmpty()) {
            adapter.add("No matching downloaded bezels");
        }
        adapter.notifyDataSetChanged();
    }

    private View wrapGameDetailsWithBezel(GameLibraryEntry entry, View content) {
        File bezelFile = BezelResolver.findBezelForGame(this, entry.file.getAbsolutePath());
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF050607);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        if (bezelFile != null && bezelFile.isFile()) {
            Log.d("4DO-Bezel", "Details bezel matched "
                    + entry.file.getName() + " -> " + bezelFile.getAbsolutePath());
            ImageView bezel = new ImageView(this);
            bezel.setImageURI(Uri.fromFile(bezelFile));
            bezel.setScaleType(ImageView.ScaleType.FIT_XY);
            frame.addView(bezel, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            contentParams.setMargins(dp(92), dp(18), dp(92), dp(18));
            content.setBackgroundColor(0xEA101418);
        } else {
            Log.d("4DO-Bezel", "Details bezel missing for "
                    + entry.file.getName() + " | " + BezelResolver.describeBezelDiagnostic(this, entry.file.getAbsolutePath()));
            TextView noMatch = new TextView(this);
            noMatch.setText(BezelResolver.describeBezelDiagnostic(this, entry.file.getAbsolutePath()));
            noMatch.setTextColor(0xFFFFD166);
            noMatch.setTextSize(12.0f);
            noMatch.setGravity(Gravity.CENTER);
            noMatch.setPadding(dp(10), dp(6), dp(10), dp(6));
            noMatch.setBackgroundColor(0xCC1B2028);
            FrameLayout.LayoutParams noMatchParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            frame.addView(noMatch, noMatchParams);
            contentParams.setMargins(dp(10), dp(38), dp(10), dp(10));
            content.setBackgroundColor(0xF0101418);
        }

        frame.addView(content, contentParams);

        frame.setMinimumHeight(dp(560));
        return frame;
    }

    private void populateIgdbMediaStrip(GameLibraryEntry entry, LinearLayout mediaStrip) {
        mediaStrip.removeAllViews();
        if (entry.igdbGame == null) {
            addMediaLabel(mediaStrip, "No IGDB media");
            return;
        }

        List<String> urls = new ArrayList<>();
        urls.addAll(entry.igdbGame.screenshots);
        urls.addAll(entry.igdbGame.artworks);
        if (entry.igdbGame.coverUrl != null && !entry.igdbGame.coverUrl.isEmpty()) {
            urls.add(entry.igdbGame.coverUrl);
        }
        if (urls.isEmpty()) {
            addMediaLabel(mediaStrip, "No IGDB media");
            return;
        }

        int max = Math.min(urls.size(), 7);
        for (int i = 0; i < max; i++) {
            String url = urls.get(i);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(cardBackground(0xFF20262D, 0xFF3A424C));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(132));
            params.setMargins(0, 0, 0, dp(8));
            mediaStrip.addView(image, params);
            final int index = i;
            if (url.equals(entry.igdbGame.coverUrl)) {
                igdbService.loadCover(url, entry.igdbGame.id, (bitmap, localPath) -> {
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                        entry.coverBitmap = bitmap;
                        renderGameLibraryCards();
                    }
                });
            } else {
                igdbService.loadMediaImage(url, entry.igdbGame.id + "_" + index, bitmap -> {
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                    }
                });
            }
        }
    }

    private void addMediaLabel(LinearLayout mediaStrip, String label) {
        TextView empty = new TextView(this);
        empty.setText(label);
        empty.setTextColor(0xFF9AA3AF);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(cardBackground(0xFF20262D, 0xFF3A424C));
        mediaStrip.addView(empty, new LinearLayout.LayoutParams(dp(180), dp(108)));
    }

    private String formatIgdbDetails(GameLibraryEntry entry) {
        StringBuilder details = new StringBuilder();
        details.append(displayBaseName(entry.displayName));
        details.append("\n").append(BezelResolver.describeBezelDiagnostic(this, entry.file.getAbsolutePath()));
        if (entry.igdbGame == null) {
            details.append("\nIGDB: no match yet");
            return details.toString();
        }
        IgdbService.IgdbGame game = entry.igdbGame;
        details.append("\nIGDB: ").append(emptyFallback(game.name, "Unknown"));
        if (game.releaseDate != null && !game.releaseDate.isEmpty()) {
            details.append("\nYear: ").append(game.releaseDate);
        }
        if (game.publisher != null && !game.publisher.isEmpty()) {
            details.append("\nPublisher: ").append(game.publisher);
        }
        if (game.summary != null && !game.summary.isEmpty()) {
            details.append("\n\n").append(game.summary);
        }
        return details.toString();
    }

    private String igdbResultLabel(IgdbService.IgdbGame game) {
        StringBuilder label = new StringBuilder(emptyFallback(game.name, "Unknown"));
        if (game.releaseDate != null && !game.releaseDate.isEmpty()) {
            label.append(" (").append(game.releaseDate).append(")");
        }
        if (game.publisher != null && !game.publisher.isEmpty()) {
            label.append(" - ").append(game.publisher);
        }
        return label.toString();
    }

    private String emptyFallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private boolean matchesSearch(String value, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.US).contains(query.trim().toLowerCase(Locale.US));
    }

    private String displayBaseName(String name) {
        if (name == null) {
            return "";
        }
        String value = new File(name).getName();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String buildIgdbQueryName(String rawName) {
        String value = displayBaseName(rawName).trim();
        int cut = value.length();
        int paren = value.indexOf('(');
        int bracket = value.indexOf('[');
        int brace = value.indexOf('{');
        if (paren >= 0) cut = Math.min(cut, paren);
        if (bracket >= 0) cut = Math.min(cut, bracket);
        if (brace >= 0) cut = Math.min(cut, brace);
        value = value.substring(0, cut).replace('_', ' ').trim();
        value = value.replaceAll("(?i)\\bv\\d+(?:\\.\\d+)*\\b", "").trim();
        return value.isEmpty() ? displayBaseName(rawName) : value;
    }

    private String fileExtensionLabel(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "3DO";
        }
        return name.substring(dot + 1).toUpperCase(Locale.US);
    }

    private void cycleRenderer() {
        if (currentRenderer == RENDERER_VULKAN) {
            currentRenderer = RENDERER_OPENGL_ES;
        } else if (currentRenderer == RENDERER_OPENGL_ES) {
            currentRenderer = RENDERER_SOFTWARE;
        } else {
            currentRenderer = RENDERER_VULKAN;
        }
        if (!isHardwareRendererSelected()) {
            resolutionScale = 1;
            outputResolutionPreset = 0;
            setResolutionScale(resolutionScale);
            setOutputResolutionPreset(0);
        }
        setRendererType(currentRenderer);
        rebindRendererSurface();
        saveSettings();
        updateButtonLabels();
        updateStatusIndicator();
    }

    private void toggleAspectMode() {
        aspectRatio16by9 = !aspectRatio16by9;
        setAspectRatio(aspectRatio16by9);
        saveSettings();
        updateButtonLabels();
        updateStatusIndicator();
    }

    private void toggleTextureFilter() {
        nearestFiltering = !nearestFiltering;
        setFiltering(nearestFiltering);
        saveSettings();
        updateButtonLabels();
        updateStatusIndicator();
    }

    private void toggleBezelOverlay() {
        bezelEnabled = !bezelEnabled;
        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(SettingsActivity.KEY_BEZEL_ENABLED, bezelEnabled)
                .apply();
        applyBezelVisibility();
        updateButtonLabels();
    }

    private void toggleCrtShader() {
        crtShaderEnabled = !crtShaderEnabled;
        setCrtShaderEnabled(crtShaderEnabled);
        saveSettings();
        updateButtonLabels();
        updateStatusIndicator();
    }

    private void bootBios() {
        mGamePath = null;
        stopAudioPlayback();
        shutdownEmulator();
        initializeEmulatorAsync(null, false);
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.GONE);
        }
        if (gameLibraryScreen != null) {
            gameLibraryScreen.setVisibility(View.GONE);
        }
        overlayPaused = false;
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
        virtualPadVisible = prefs.getBoolean(KEY_VIRTUAL_PAD_VISIBLE, false);
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
            applyGameBezelImage();
            emulatorBezel.setVisibility(bezelEnabled ? View.VISIBLE : View.GONE);
        }
    }

    private void applyGameBezelImage() {
        if (emulatorBezel == null) {
            return;
        }

        File bezelFile = BezelResolver.findBezelForGame(this, mGamePath);
        String newPath = bezelFile == null ? "" : bezelFile.getAbsolutePath();
        if (newPath.equals(currentBezelPath)) {
            return;
        }

        currentBezelPath = newPath;
        if (bezelFile != null && bezelFile.isFile()) {
            emulatorBezel.setImageURI(Uri.fromFile(bezelFile));
        } else {
            emulatorBezel.setImageResource(R.drawable.bezel_fz10);
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
        statusIndicator.setVisibility(View.GONE);
        statusIndicator.setText("");
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
        String renderer = getRendererShortName(currentRenderer);
        String aspect = aspectRatio16by9 ? "16:9" : "4:3";
        String crt = crtShaderEnabled ? "CRT" : "No CRT";
        String filter = nearestFiltering ? "Nearest" : "Linear";
        if (topRendererButton != null) topRendererButton.setText(renderer);
        if (topAspectButton != null) topAspectButton.setText(aspect);
        if (topCrtButton != null) topCrtButton.setText(crt);
        if (topPadButton != null) topPadButton.setText(virtualPadVisible ? "Pad" : "No Pad");
        if (sideStatusText != null) {
            String game = mGamePath == null || mGamePath.isEmpty() ? "BIOS" : new File(mGamePath).getName();
            String currentBezel = mGamePath == null || mGamePath.isEmpty()
                    ? BezelResolver.describeDownloadedBezels(this)
                    : BezelResolver.describeBezel(this, mGamePath);
            sideStatusText.setText("Running: " + game + "\n" + renderer + " | " + aspect + " | " + filter + " | " + crt
                    + "\n" + currentBezel + "\nVirtual pad: " + (virtualPadVisible ? "shown" : "hidden"));
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

    private void applyDebugModeUi() {
        if (fpsCounter != null) {
            fpsCounter.setVisibility(View.VISIBLE);
        }
        if (statusIndicator != null) {
            statusIndicator.setVisibility(View.GONE);
            statusIndicator.setText("");
        }
    }

    private void openLibraryGamePickerForStartup() {
        openGameLibrary();
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
                    if (loadingGame && gamePath != null && !gamePath.isEmpty()) {
                        EmulatorPathStore.clearLastGamePath(this);
                        mGamePath = null;
                        currentBezelPath = "";
                        applyBezelVisibility();
                        Toast.makeText(this, message + "; booting BIOS", Toast.LENGTH_LONG).show();
                        initializeEmulatorAsync(null, false);
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        setSettingsPanelVisible(true);
                    }
                    return;
                }

                emulatorStarted = true;
                currentBezelPath = "";
                applyBezelVisibility();
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
        releaseVirtualPadButtons();
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
        virtualPadVisible = prefs.getBoolean(KEY_VIRTUAL_PAD_VISIBLE, virtualPadVisible);
        crtShaderEnabled = prefs.getBoolean("crt_shader_enabled", false);
        nearestFiltering = prefs.getBoolean("nearest_filtering", false);
        
        applyVirtualPadVisibility();
        applyDebugModeUi();
        applyBezelVisibility();
        
        // Update renderer if it's already initialized
        setFiltering(nearestFiltering);
        setCrtShaderEnabled(crtShaderEnabled);
        updateButtonLabels();
        updateStatusIndicator();

        if (emulatorStarted && !emulatorInitializing && !manuallyPaused && !hasVisibleRuntimeOverlay()) {
            resumeEmulator();
        } else if (hasVisibleRuntimeOverlay()) {
            pauseForOverlay();
        }
    }

    @Override
    protected void onDestroy() {
        autoSaveNVRAM();
        releaseVirtualPadButtons();
        fpsHandler.removeCallbacks(hideTopControlsRunnable);
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

    private static final class GameLibraryEntry {
        final String displayName;
        final File file;
        IgdbService.IgdbGame igdbGame;
        Bitmap coverBitmap;
        boolean igdbLookupStarted;

        GameLibraryEntry(String displayName, File file) {
            this.displayName = displayName;
            this.file = file;
        }
    }
}
