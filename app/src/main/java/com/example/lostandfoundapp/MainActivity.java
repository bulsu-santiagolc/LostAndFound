package com.example.lostandfoundapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private Button buttonShowUpload;
    private Button buttonShowSearch;
    private FragmentManager fragmentManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonShowUpload = findViewById(R.id.buttonShowUpload);
        buttonShowSearch = findViewById(R.id.buttonShowSearch);
        fragmentManager = getSupportFragmentManager();

        buttonShowUpload.setOnClickListener(v -> showFragment(new UploadItemFragment()));
        buttonShowSearch.setOnClickListener(v -> showFragment(new SearchItemFragment()));

        // Show the upload fragment by default
        if (savedInstanceState == null) {
            showFragment(new UploadItemFragment());
        }
    }

    private void showFragment(Fragment fragment) {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragmentContainer, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }
}
