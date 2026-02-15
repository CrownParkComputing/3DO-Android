package com.fourdo.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_SELECT_FOLDER_MODE = "select_folder_mode";

    private ListView fileListView;
    private TextView currentPathTextView;
    private Button backButton;
    private Button selectFolderButton;
    private List<String> fileList;
    private List<String> filePathList;
    private File currentDir;
    private boolean selectFolderMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        fileListView = findViewById(R.id.file_list);
        currentPathTextView = findViewById(R.id.current_path);
        backButton = findViewById(R.id.back_button);
        selectFolderButton = findViewById(R.id.select_folder_button);

        selectFolderMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SELECT_FOLDER_MODE, false);
        selectFolderButton.setVisibility(selectFolderMode ? View.VISIBLE : View.GONE);

        currentDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath());
        loadFileList(currentDir);

        fileListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String item = fileList.get(position);
                File file = new File(filePathList.get(position));

                if (file.isDirectory()) {
                    currentDir = file;
                    loadFileList(currentDir);
                } else {
                    // File selected, return result
                    Intent resultIntent = new Intent();
                    resultIntent.setData(Uri.fromFile(file));
                    setResult(Activity.RESULT_OK, resultIntent);
                    finish();
                }
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir.getParentFile() != null) {
                    currentDir = currentDir.getParentFile();
                    loadFileList(currentDir);
                } else {
                    finish();
                }
            }
        });

        selectFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent resultIntent = new Intent();
                resultIntent.setData(Uri.fromFile(currentDir));
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void loadFileList(File dir) {
        currentPathTextView.setText(dir.getAbsolutePath());

        File[] files = dir.listFiles();
        fileList = new ArrayList<>();
        filePathList = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                fileList.add(file.getName());
                filePathList.add(file.getAbsolutePath());
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, fileList);
        fileListView.setAdapter(adapter);
    }
}