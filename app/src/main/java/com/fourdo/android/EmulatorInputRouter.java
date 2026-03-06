package com.fourdo.android;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

final class EmulatorInputRouter {

    private EmulatorInputRouter() {
    }

    static Integer resolveMappedButton(Context context, int keyCode, KeyEvent event) {
        if (!isGameControllerEvent(event)) {
            return null;
        }
        int mapped = ControllerMappingManager.getMappedButtonForKeyCode(context, keyCode);
        if (mapped < 0) {
            return null;
        }
        return mapped;
    }

    private static boolean isGameControllerEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);
    }
}
