package com.fourdo.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "fourdo_prefs";
    public static final String KEY_BIOS_PATH = "bios_path";
    public static final String KEY_LAST_GAME_PATH = "last_game_path";
    public static final String KEY_LIBRARY_FOLDER = "library_folder";
    private static final int REQUEST_BIOS_PICKER = 1;
    private static final int REQUEST_LIBRARY_PICKER = 2;

    private Button backButton;
    private Button selectBiosButton;
    private Button selectLibraryButton;
    private TextView biosPathText;
    private TextView libraryPathText;

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
        biosPathText = findViewById(R.id.bios_path_text);
        libraryPathText = findViewById(R.id.library_path_text);

        refreshBiosPathText();
        refreshLibraryPathText();

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

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
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
            biosPathText.setText(getString(R.string.bios_path_value, biosPath));
        }
    }

    private void refreshLibraryPathText() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String libraryPath = prefs.getString(KEY_LIBRARY_FOLDER, "");
        if (libraryPath == null || libraryPath.isEmpty()) {
            libraryPathText.setText(getString(R.string.library_not_set));
        } else {
            libraryPathText.setText(getString(R.string.library_path_value, libraryPath));
        }
    }
}