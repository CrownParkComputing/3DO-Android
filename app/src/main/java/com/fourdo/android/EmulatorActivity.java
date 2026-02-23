package com.fourdo.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class EmulatorActivity extends AppCompatActivity {

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
    private Button backButton;
    private Button controllerMapButton;
    private LinearLayout overlayMenu;
    private boolean menuVisible = false;
    
    // Quick toolbar buttons
    private Button cdButton;
    private Button aspectRatioButton;
    private Button rendererButton;
    private Button filteringButton;
    private android.widget.ImageButton menuToggleButton;
    private android.widget.TextView statusIndicator;
    private android.widget.TextView fpsCounter;
    private LinearLayout quickToolbar;
    
    // FPS counter
    private int frameCount = 0;
    private long lastFpsTime = 0;
    private android.os.Handler fpsHandler = new android.os.Handler();
    private Runnable fpsRunnable;
    
    // Settings state
    private boolean aspectRatio16by9 = false;  // false = 4:3, true = 16:9
    private int currentRenderer = RENDERER_OPENGL_ES;
    private boolean nearestFiltering = false;  // false = linear, true = nearest
    private SurfaceView emulatorSurfaceView;
    private String mGamePath = null;
    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean audioRunning;

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
        
        // Set optimal orientation based on device type
        DeviceOrientationManager.setOptimalEmulatorOrientation(this);
        
        // Keep screen on during emulation
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_emulator);

        pauseButton = findViewById(R.id.pause_button);
        resetButton = findViewById(R.id.reset_button);
        loadCdButton = findViewById(R.id.load_cd_button);
        backButton = findViewById(R.id.back_button);
        controllerMapButton = findViewById(R.id.controller_map_button);
        overlayMenu = findViewById(R.id.overlay_menu);
        
        // Quick toolbar buttons
        cdButton = findViewById(R.id.cd_button);
        aspectRatioButton = findViewById(R.id.aspect_ratio_button);
        rendererButton = findViewById(R.id.renderer_button);
        filteringButton = findViewById(R.id.filtering_button);
        menuToggleButton = findViewById(R.id.menu_toggle_button);
        statusIndicator = findViewById(R.id.status_indicator);
        fpsCounter = findViewById(R.id.fps_counter);
        quickToolbar = findViewById(R.id.quick_toolbar);
        
        // Load saved settings
        loadSettings();
        
        // Initialize button labels
        updateButtonLabels();
        
        // Start FPS counter
        startFpsCounter();
        
        // Setup quick toolbar button listeners
        setupQuickToolbar();

        // Menu toggle button
        menuToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOverlayMenu();
            }
        });

        // Controller map button
        controllerMapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openControllerMap();
                hideOverlayMenu();
            }
        });

        FrameLayout surfaceContainer = findViewById(R.id.emulator_surface);
        emulatorSurfaceView = new SurfaceView(this);
        surfaceContainer.addView(emulatorSurfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        emulatorSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            private boolean surfaceReady = false;

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // Don't set surface yet - wait for surfaceChanged with proper size
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (width > 0 && height > 0) {
                    initRenderer(width, height);
                    setSurface(holder.getSurface());
                    surfaceReady = true;
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                setSurface(null);
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

        // Controller map button is now directly accessible, no need for overlay menu

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlayMenu();
                finish();
            }
        });

        // Get game path from intent
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("game_path")) {
            mGamePath = intent.getStringExtra("game_path");
        }
        
        // If no game path, open file browser
        String biosPath = getSavedBiosPath();
        if (!isValidFilePath(biosPath)) {
            Toast.makeText(this, R.string.bios_required, Toast.LENGTH_SHORT).show();
            openBiosBrowser();
        } else if (mGamePath == null || mGamePath.isEmpty()) {
            // Boot into BIOS - no game selected, show menu after boot
            initializeEmulator(null);
            // Show the game selection menu after a short delay
            emulatorSurfaceView.postDelayed(() -> {
                showOverlayMenu();
                Toast.makeText(this, "Select a game from the menu", Toast.LENGTH_LONG).show();
            }, 1000);
        } else {
            // Initialize emulator with the game path
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
        String libraryPath = getSavedLibraryFolder();
        if (isValidDirectoryPath(libraryPath)) {
            Intent intent = new Intent(this, GameLibraryActivity.class);
            intent.putExtra(GameLibraryActivity.EXTRA_LIBRARY_PATH, libraryPath);
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
        cdButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openLoadCdBrowser();
                showStatusToast("Load CD...");
                return true;
            }
        });
        cdButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStatusToast("Long press to load CD");
            }
        });
        
        // Aspect ratio toggle
        aspectRatioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aspectRatio16by9 = !aspectRatio16by9;
                setAspectRatio(aspectRatio16by9);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
                showStatusToast(aspectRatio16by9 ? "16:9" : "4:3");
            }
        });
        
        // Renderer toggle (skip Vulkan - not implemented)
        rendererButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Cycle: OpenGL ES -> Software -> OpenGL ES (skip Vulkan)
                if (currentRenderer == RENDERER_OPENGL_ES) {
                    currentRenderer = RENDERER_SOFTWARE;
                } else {
                    currentRenderer = RENDERER_OPENGL_ES;
                }
                setRendererType(currentRenderer);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
                String name = currentRenderer == RENDERER_OPENGL_ES ? "OpenGL ES" : "Software";
                showStatusToast("Renderer: " + name);
            }
        });
        
        // Filtering toggle
        filteringButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nearestFiltering = !nearestFiltering;
                setFiltering(nearestFiltering);
                saveSettings();
                updateButtonLabels();
                updateStatusIndicator();
                showStatusToast(nearestFiltering ? "Nearest (Sharp)" : "Linear (Smooth)");
            }
        });
        
        // Update status indicator
        updateStatusIndicator();
    }
    
    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        aspectRatio16by9 = prefs.getBoolean("aspect_ratio_16by9", false);
        currentRenderer = prefs.getInt("renderer_type", RENDERER_OPENGL_ES);
        nearestFiltering = prefs.getBoolean("nearest_filtering", false);
    }
    
    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putBoolean("aspect_ratio_16by9", aspectRatio16by9)
            .putInt("renderer_type", currentRenderer)
            .putBoolean("nearest_filtering", nearestFiltering)
            .apply();
    }
    
    private void updateStatusIndicator() {
        String aspect = aspectRatio16by9 ? "16:9" : "4:3";
        String renderer = currentRenderer == RENDERER_OPENGL_ES ? "OpenGL" :
                          currentRenderer == RENDERER_VULKAN ? "Vulkan" : "Soft";
        String filter = nearestFiltering ? "Sharp" : "Smooth";
        statusIndicator.setText(aspect + " | " + renderer + " | " + filter);
    }
    
    private void showStatusToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    
    private void updateButtonLabels() {
        // Update aspect ratio button
        aspectRatioButton.setText(aspectRatio16by9 ? "16:9" : "4:3");
        
        // Update renderer button
        rendererButton.setText(currentRenderer == RENDERER_OPENGL_ES ? "OpenGL" : "Soft");
        
        // Update filtering button
        filteringButton.setText(nearestFiltering ? "Sharp" : "Smooth");
    }
    
    private void startFpsCounter() {
        fpsRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (lastFpsTime > 0) {
                    long elapsed = now - lastFpsTime;
                    if (elapsed > 0) {
                        int fps = (int)(frameCount * 1000 / elapsed);
                        fpsCounter.setText("FPS: " + fps);
                    }
                }
                lastFpsTime = now;
                frameCount = 0;
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

    private void openLibraryGamePickerForStartup() {
        String libraryPath = getSavedLibraryFolder();
        if (isValidDirectoryPath(libraryPath)) {
            Intent intent = new Intent(this, GameLibraryActivity.class);
            intent.putExtra(GameLibraryActivity.EXTRA_LIBRARY_PATH, libraryPath);
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
                    if (isValidFilePath(biosPath)) {
                        saveBiosPath(biosPath);
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
                    saveLastGamePath(mGamePath);
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
                    if (isSupportedCdPath(gamePath)) {
                        mGamePath = gamePath;
                        saveLastGamePath(gamePath);
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
                    if (isSupportedCdPath(cdPath)) {
                        mGamePath = cdPath;
                        saveLastGamePath(cdPath);
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

    private String getSavedBiosPath() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_BIOS_PATH, "");
    }

    private void saveBiosPath(String biosPath) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(SettingsActivity.KEY_BIOS_PATH, biosPath).apply();
    }

    private String getSavedLastGamePath() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_LAST_GAME_PATH, "");
    }

    private String getSavedLibraryFolder() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_LIBRARY_FOLDER, "");
    }

    private void saveLastGamePath(String gamePath) {
        if (gamePath == null || gamePath.isEmpty()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(SettingsActivity.KEY_LAST_GAME_PATH, gamePath).apply();
    }

    private boolean isValidFilePath(String path) {
        return path != null && !path.isEmpty() && new File(path).isFile();
    }

    private boolean isSupportedCdPath(String path) {
        if (!isValidFilePath(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".cue") || lower.endsWith(".bin") || lower.endsWith(".iso") || lower.endsWith(".chd");
    }

    private boolean isValidDirectoryPath(String path) {
        return path != null && !path.isEmpty() && new File(path).isDirectory();
    }

    private void performHardReset() {
        performHardReset(null);
    }

    private void performHardReset(String preferredGamePath) {
        stopAudioPlayback();
        shutdownEmulator();

        String biosPath = getSavedBiosPath();
        if (!isValidFilePath(biosPath)) {
            Toast.makeText(this, R.string.bios_required, Toast.LENGTH_SHORT).show();
            openBiosBrowser();
            return;
        }

        String gameToLoad = preferredGamePath;
        if (!isSupportedCdPath(gameToLoad)) {
            gameToLoad = mGamePath;
        }
        if (!isSupportedCdPath(gameToLoad)) {
            String lastGamePath = getSavedLastGamePath();
            if (isSupportedCdPath(lastGamePath)) {
                gameToLoad = lastGamePath;
                mGamePath = lastGamePath;
            }
        }

        if (isSupportedCdPath(gameToLoad)) {
            mGamePath = gameToLoad;
        }

        initializeEmulator(gameToLoad);
    }

    private boolean isGameControllerEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);
    }

    private Integer mapKeyCodeToInputButton(int keyCode) {
        int mapped = ControllerMappingManager.getMappedButtonForKeyCode(this, keyCode);
        if (mapped < 0) {
            return null;
        }
        return mapped;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isGameControllerEvent(event)) {
            Integer button = mapKeyCodeToInputButton(keyCode);
            if (button != null) {
                setInputState(button, true);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isGameControllerEvent(event)) {
            Integer button = mapKeyCodeToInputButton(keyCode);
            if (button != null) {
                setInputState(button, false);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }
    
    private void initializeEmulator(String gamePath) {
        String biosPath = getSavedBiosPath();

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
        if (audioRunning) {
            return;
        }

        int sampleRate = 44100;
        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minBufferSize <= 0) {
            return;
        }

        audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                Math.max(minBufferSize * 8, sampleRate),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        audioTrack.play();
        audioRunning = true;

        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

                int[] packedFrames = new int[4096];
                short[] pcm = new short[packedFrames.length * 2];

                while (audioRunning) {
                    int frameCount = drainAudioFrames(packedFrames);
                    if (frameCount > 0) {
                        for (int i = 0; i < frameCount; i++) {
                            int sample = packedFrames[i];
                            pcm[i * 2] = (short) (sample & 0xFFFF);
                            pcm[i * 2 + 1] = (short) ((sample >> 16) & 0xFFFF);
                        }
                        audioTrack.write(pcm, 0, frameCount * 2);
                    } else {
                        // Short busy-wait to avoid Android's coarse sleep granularity
                        try {
                            Thread.sleep(0, 500000); // 0.5ms
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }, "4DO-AudioThread");
        audioThread.start();
    }

    private void stopAudioPlayback() {
        audioRunning = false;

        if (audioThread != null) {
            try {
                audioThread.join(250);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
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
    
    // New GPU renderer methods
    private native void setRendererType(int type);
    private native void setFiltering(boolean nearest);
    private native String getRendererName();
    private native void setAspectRatio(boolean wide);
    private native boolean getAspectRatio();
    
    // Renderer type constants
    public static final int RENDERER_AUTO = 0;
    public static final int RENDERER_OPENGL_ES = 1;
    public static final int RENDERER_VULKAN = 2;
    public static final int RENDERER_SOFTWARE = 3;
}