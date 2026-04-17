package com.fourdo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

public final class ControllerMappingManager {

    public static final int BUTTON_A = 0;
    public static final int BUTTON_B = 1;
    public static final int BUTTON_C = 2;
    public static final int BUTTON_PLAY_PAUSE = 3;
    public static final int BUTTON_STOP = 4;
    public static final int BUTTON_DPAD_UP = 5;
    public static final int BUTTON_DPAD_DOWN = 6;
    public static final int BUTTON_DPAD_LEFT = 7;
    public static final int BUTTON_DPAD_RIGHT = 8;
    public static final int BUTTON_L1 = 9;
    public static final int BUTTON_R1 = 10;

    private static final int BUTTON_MAX = 11;
    private static final String PREF_KEY_PREFIX = "controller_map_";

    private ControllerMappingManager() {
    }

    public static int getMappedButtonForKeyCode(Context context, int keyCode) {
        for (int button = 0; button < BUTTON_MAX; button++) {
            if (getMappedKeyCode(context, button) == keyCode) {
                return button;
            }
        }
        return -1;
    }

    public static int getMappedKeyCode(Context context, int button) {
        if (!isValidButton(button)) {
            return -1;
        }

        SharedPreferences prefs = getPrefs(context);
        String key = prefKey(button);
        if (prefs.contains(key)) {
            return prefs.getInt(key, -1);
        }
        return defaultKeyCodeForButton(button);
    }

    public static void assignKeyCode(Context context, int button, int keyCode) {
        if (!isValidButton(button) || keyCode <= 0) {
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();

        for (int i = 0; i < BUTTON_MAX; i++) {
            if (i == button) {
                continue;
            }
            if (getMappedKeyCode(context, i) == keyCode) {
                editor.putInt(prefKey(i), -1);
            }
        }

        editor.putInt(prefKey(button), keyCode);
        editor.apply();
    }

    public static String buttonName(int button) {
        switch (button) {
            case BUTTON_A:
                return "A";
            case BUTTON_B:
                return "B";
            case BUTTON_C:
                return "C";
            case BUTTON_PLAY_PAUSE:
                return "P";
            case BUTTON_STOP:
                return "X";
            case BUTTON_DPAD_UP:
                return "UP";
            case BUTTON_DPAD_DOWN:
                return "DOWN";
            case BUTTON_DPAD_LEFT:
                return "LEFT";
            case BUTTON_DPAD_RIGHT:
                return "RIGHT";
            case BUTTON_L1:
                return "L1";
            case BUTTON_R1:
                return "R1";
            default:
                return "UNKNOWN";
        }
    }

    public static String keyName(int keyCode) {
        if (keyCode <= 0) {
            return "UNASSIGNED";
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return "A";
            case KeyEvent.KEYCODE_BUTTON_B:
                return "B";
            case KeyEvent.KEYCODE_BUTTON_X:
                return "X";
            case KeyEvent.KEYCODE_BUTTON_Y:
                return "Y";
            case KeyEvent.KEYCODE_BUTTON_START:
                return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_L1:
                return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1:
                return "R1";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "RIGHT";
            default:
                break;
        }
        String value = KeyEvent.keyCodeToString(keyCode);
        if (value == null) {
            return "KEY_" + keyCode;
        }
        return value.replace("KEYCODE_", "");
    }

    private static boolean isValidButton(int button) {
        return button >= 0 && button < BUTTON_MAX;
    }

    private static String prefKey(int button) {
        return PREF_KEY_PREFIX + button;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int defaultKeyCodeForButton(int button) {
        switch (button) {
            case BUTTON_A:
                return KeyEvent.KEYCODE_BUTTON_A;
            case BUTTON_B:
                return KeyEvent.KEYCODE_BUTTON_B;
            case BUTTON_C:
                return KeyEvent.KEYCODE_BUTTON_X;
            case BUTTON_PLAY_PAUSE:
                return KeyEvent.KEYCODE_BUTTON_START;
            case BUTTON_STOP:
                return KeyEvent.KEYCODE_BUTTON_SELECT;
            case BUTTON_DPAD_UP:
                return KeyEvent.KEYCODE_DPAD_UP;
            case BUTTON_DPAD_DOWN:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            case BUTTON_DPAD_LEFT:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case BUTTON_DPAD_RIGHT:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case BUTTON_L1:
                return KeyEvent.KEYCODE_BUTTON_L1;
            case BUTTON_R1:
                return KeyEvent.KEYCODE_BUTTON_R1;
            default:
                return -1;
        }
    }
}