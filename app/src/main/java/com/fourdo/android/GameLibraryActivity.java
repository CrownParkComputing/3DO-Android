package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameLibraryActivity extends AppCompatActivity {

    public static final String EXTRA_LIBRARY_PATH = "library_path";

    private TextView libraryPathText;
    private ListView gameListView;
    private Button backButton;

    private final List<File> gameFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_library);

        libraryPathText = findViewById(R.id.library_path);
        gameListView = findViewById(R.id.game_list);
        backButton = findViewById(R.id.back_button);

        String libraryPath = getIntent() != null ? getIntent().getStringExtra(EXTRA_LIBRARY_PATH) : null;
        if (libraryPath == null || libraryPath.isEmpty()) {
            Toast.makeText(this, R.string.library_not_set, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File root = new File(libraryPath);
        if (!root.isDirectory()) {
            Toast.makeText(this, R.string.library_not_set, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        libraryPathText.setText(getString(R.string.library_path_value, root.getAbsolutePath()));
        scanGames(root);
        bindGameList();

        gameListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= gameFiles.size()) {
                    return;
                }
                File selected = gameFiles.get(position);
                Intent result = new Intent();
                result.setData(Uri.fromFile(selected));
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void scanGames(File root) {
        gameFiles.clear();
        scanRecursive(root);
        Collections.sort(gameFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    private void scanRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanRecursive(file);
            } else if (isSupportedGameFile(file)) {
                gameFiles.add(file);
            }
        }
    }

    private boolean isSupportedGameFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".cue") || name.endsWith(".iso") || name.endsWith(".chd") || name.endsWith(".bin");
    }

    private void bindGameList() {
        List<String> names = new ArrayList<>();
        for (File game : gameFiles) {
            names.add(game.getName());
        }

        if (names.isEmpty()) {
            names.add(getString(R.string.no_games_found));
            gameListView.setEnabled(false);
        } else {
            gameListView.setEnabled(true);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        gameListView.setAdapter(adapter);
    }
}
