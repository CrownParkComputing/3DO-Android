package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

public class CDLoadingActivity extends Activity {

    private ImageView cdIcon;
    private ProgressBar progressBar;
    private TextView loadingText;
    private TextView percentageText;
    private Handler handler;
    private Runnable updateProgressRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cd_loading);

        cdIcon = findViewById(R.id.cd_icon);
        progressBar = findViewById(R.id.progress_bar);
        loadingText = findViewById(R.id.loading_text);
        percentageText = findViewById(R.id.percentage_text);

        // Start CD spinning animation
        startCDAnimation();

        // Initialize progress update
        handler = new Handler(Looper.getMainLooper());
        updateProgressRunnable = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                handler.postDelayed(this, 100);
            }
        };
        handler.post(updateProgressRunnable);
    }

    private void startCDAnimation() {
        RotateAnimation rotate = new RotateAnimation(
                0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        rotate.setDuration(2000);
        rotate.setRepeatCount(Animation.INFINITE);
        rotate.setInterpolator(new LinearInterpolator());
        cdIcon.startAnimation(rotate);
    }

    private void updateProgress() {
        int currentProgress = progressBar.getProgress();
        if (currentProgress < 100) {
            currentProgress += 5;
            progressBar.setProgress(currentProgress);
            percentageText.setText(currentProgress + "%");
        } else {
            // Loading complete, finish activity
            handler.removeCallbacks(updateProgressRunnable);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacks(updateProgressRunnable);
        }
    }
}