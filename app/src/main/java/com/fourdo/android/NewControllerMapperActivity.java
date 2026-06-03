package com.fourdo.android;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class NewControllerMapperActivity extends Activity {

    private static final int BUTTON_BACKGROUND_DEFAULT = R.drawable.mapper_button_background;
    private static final int BUTTON_BACKGROUND_SELECTED = R.drawable.mapper_button_selected_background;

    private Button buttonA;
    private Button buttonB;
    private Button buttonC;
    private Button buttonPlayPause;
    private Button buttonStop;
    private Button buttonDpadUp;
    private Button buttonDpadDown;
    private Button buttonDpadLeft;
    private Button buttonDpadRight;
    private Button buttonL1;
    private Button buttonR1;
    private Button backButton;
    private TextView mapperStatusText;
    private int waitingForButton = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_controller_mapper);
        
        // Use landscape orientation since emulator runs in landscape
        DeviceOrientationManager.setLandscapeOrientation(this);

        buttonA = findViewById(R.id.button_a);
        buttonB = findViewById(R.id.button_b);
        buttonC = findViewById(R.id.button_c);
        buttonPlayPause = findViewById(R.id.button_play_pause);
        buttonStop = findViewById(R.id.button_stop);
        buttonDpadUp = findViewById(R.id.button_dpad_up);
        buttonDpadDown = findViewById(R.id.button_dpad_down);
        buttonDpadLeft = findViewById(R.id.button_dpad_left);
        buttonDpadRight = findViewById(R.id.button_dpad_right);
        buttonL1 = findViewById(R.id.button_l1);
        buttonR1 = findViewById(R.id.button_r1);
        backButton = findViewById(R.id.back_button);
        mapperStatusText = findViewById(R.id.mapper_status_text);

        // Add touch feedback + assignment handler
        addTouchFeedback(buttonA, ControllerMappingManager.BUTTON_A);
        addTouchFeedback(buttonB, ControllerMappingManager.BUTTON_B);
        addTouchFeedback(buttonC, ControllerMappingManager.BUTTON_C);
        addTouchFeedback(buttonPlayPause, ControllerMappingManager.BUTTON_PLAY_PAUSE);
        addTouchFeedback(buttonStop, ControllerMappingManager.BUTTON_STOP);
        addTouchFeedback(buttonDpadUp, ControllerMappingManager.BUTTON_DPAD_UP);
        addTouchFeedback(buttonDpadDown, ControllerMappingManager.BUTTON_DPAD_DOWN);
        addTouchFeedback(buttonDpadLeft, ControllerMappingManager.BUTTON_DPAD_LEFT);
        addTouchFeedback(buttonDpadRight, ControllerMappingManager.BUTTON_DPAD_RIGHT);
        addTouchFeedback(buttonL1, ControllerMappingManager.BUTTON_L1);
        addTouchFeedback(buttonR1, ControllerMappingManager.BUTTON_R1);

        refreshButtonLabels();

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void addTouchFeedback(final Button button, final int buttonIndex) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        if (waitingForButton != buttonIndex) {
                            button.setScaleX(0.96f);
                            button.setScaleY(0.96f);
                            button.setAlpha(0.92f);
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        button.setScaleX(1.0f);
                        button.setScaleY(1.0f);
                        button.setAlpha(1.0f);
                        break;
                }
                return false;
            }
        });

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                waitingForButton = buttonIndex;
                updateWaitingState();
                Toast.makeText(NewControllerMapperActivity.this,
                        "Press a controller button for 3DO " + ControllerMappingManager.buttonName(buttonIndex),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean isGameControllerEvent(KeyEvent event) {
        int source = event.getSource();
        return ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                || ((source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (waitingForButton >= 0 && isGameControllerEvent(event)) {
            ControllerMappingManager.assignKeyCode(this, waitingForButton, keyCode);
            Toast.makeText(this,
                    "Assigned " + ControllerMappingManager.buttonName(waitingForButton)
                            + " to " + ControllerMappingManager.keyName(keyCode),
                    Toast.LENGTH_SHORT).show();
            waitingForButton = -1;
            refreshButtonLabels();
                updateWaitingState();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Ensure we stay in landscape mode for controller mapping
        DeviceOrientationManager.setLandscapeOrientation(this);
    }

    private void refreshButtonLabels() {
        setButtonLabel(buttonA, ControllerMappingManager.BUTTON_A);
        setButtonLabel(buttonB, ControllerMappingManager.BUTTON_B);
        setButtonLabel(buttonC, ControllerMappingManager.BUTTON_C);
        setButtonLabel(buttonPlayPause, ControllerMappingManager.BUTTON_PLAY_PAUSE);
        setButtonLabel(buttonStop, ControllerMappingManager.BUTTON_STOP);
        setButtonLabel(buttonDpadUp, ControllerMappingManager.BUTTON_DPAD_UP);
        setButtonLabel(buttonDpadDown, ControllerMappingManager.BUTTON_DPAD_DOWN);
        setButtonLabel(buttonDpadLeft, ControllerMappingManager.BUTTON_DPAD_LEFT);
        setButtonLabel(buttonDpadRight, ControllerMappingManager.BUTTON_DPAD_RIGHT);
        setButtonLabel(buttonL1, ControllerMappingManager.BUTTON_L1);
        setButtonLabel(buttonR1, ControllerMappingManager.BUTTON_R1);
        updateWaitingState();
    }

    private void setButtonLabel(Button view, int buttonIndex) {
        int keyCode = ControllerMappingManager.getMappedKeyCode(this, buttonIndex);
        view.setText(ControllerMappingManager.buttonName(buttonIndex)
                + "\n"
                + ControllerMappingManager.keyName(keyCode));
    }

    private void updateWaitingState() {
        if (mapperStatusText != null) {
            if (waitingForButton >= 0) {
                mapperStatusText.setText("Press a controller button for " + ControllerMappingManager.buttonName(waitingForButton));
            } else {
                mapperStatusText.setText("Tap a button below, then press a controller button to map it.");
            }
        }

        updateButtonSelection(buttonA, ControllerMappingManager.BUTTON_A);
        updateButtonSelection(buttonB, ControllerMappingManager.BUTTON_B);
        updateButtonSelection(buttonC, ControllerMappingManager.BUTTON_C);
        updateButtonSelection(buttonPlayPause, ControllerMappingManager.BUTTON_PLAY_PAUSE);
        updateButtonSelection(buttonStop, ControllerMappingManager.BUTTON_STOP);
        updateButtonSelection(buttonDpadUp, ControllerMappingManager.BUTTON_DPAD_UP);
        updateButtonSelection(buttonDpadDown, ControllerMappingManager.BUTTON_DPAD_DOWN);
        updateButtonSelection(buttonDpadLeft, ControllerMappingManager.BUTTON_DPAD_LEFT);
        updateButtonSelection(buttonDpadRight, ControllerMappingManager.BUTTON_DPAD_RIGHT);
        updateButtonSelection(buttonL1, ControllerMappingManager.BUTTON_L1);
        updateButtonSelection(buttonR1, ControllerMappingManager.BUTTON_R1);
    }

    private void updateButtonSelection(Button button, int buttonIndex) {
        if (button == null) {
            return;
        }
        if (waitingForButton == buttonIndex) {
            button.setBackgroundResource(BUTTON_BACKGROUND_SELECTED);
            button.setAlpha(1.0f);
        } else {
            button.setBackgroundResource(BUTTON_BACKGROUND_DEFAULT);
            button.setAlpha(0.88f);
        }
    }

}