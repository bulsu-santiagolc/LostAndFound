package com.example.lostandfoundapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SearchItemFragment extends Fragment {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAPTURE_IMAGE_REQUEST = 2;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private Uri imageUri;
    private EditText searchQuery;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private FirebaseFirestore db;
    private ItemAdapter itemAdapter;
    private List<Item> itemList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);

        db = FirebaseFirestore.getInstance();
        searchQuery = view.findViewById(R.id.searchQuery);
        Button searchButton = view.findViewById(R.id.searchButton);
        Button captureImageButton = view.findViewById(R.id.captureImageButton);
        Button uploadImageButton = view.findViewById(R.id.uploadImageButton);
        progressBar = view.findViewById(R.id.progressBar);
        recyclerView = view.findViewById(R.id.recyclerView);

        itemList = new ArrayList<>();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        itemAdapter = new ItemAdapter(itemList, getContext());
        recyclerView.setAdapter(itemAdapter);

        searchButton.setOnClickListener(v -> searchByText());
        captureImageButton.setOnClickListener(v -> checkPermissionsAndCapture());
        uploadImageButton.setOnClickListener(v -> openFileChooser());

        return view;
    }

    private void checkPermissionsAndCapture() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(getActivity(), new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        } else {
            captureImage();
        }
    }

    private void captureImage() {
        if (getActivity() == null) return;

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File imageFile = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JPEG_" + timeStamp + ".jpg");
            imageUri = FileProvider.getUriForFile(getContext(), getActivity().getPackageName() + ".provider", imageFile);

            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            Log.d("SearchItemFragment", "Starting camera intent");
            startActivityForResult(intent, CAPTURE_IMAGE_REQUEST);
        } else {
            Toast.makeText(getContext(), "Camera not available", Toast.LENGTH_SHORT).show();
            Log.e("SearchItemFragment", "Camera not available");
        }
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == PICK_IMAGE_REQUEST && data != null) {
                imageUri = data.getData();
                labelAndSearchImage();
            } else if (requestCode == CAPTURE_IMAGE_REQUEST) {
                labelAndSearchImage();
            }
        }
    }

    private void labelAndSearchImage() {
        if (imageUri != null) {
            try {
                InputImage image = InputImage.fromFilePath(getContext(), imageUri);
                ImageLabeler labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);
                progressBar.setVisibility(View.VISIBLE);

                labeler.process(image)
                        .addOnSuccessListener(labels -> {
                            if (labels.isEmpty()) {
                                Toast.makeText(getContext(), "No labels detected", Toast.LENGTH_SHORT).show();
                                progressBar.setVisibility(View.GONE);
                                return;
                            }

                            // Sort labels by confidence score in descending order
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                labels.sort((l1, l2) -> Float.compare(l2.getConfidence(), l1.getConfidence()));
                            }

                            // Get the top 2 labels
                            List<String> topLabels = new ArrayList<>();
                            for (int i = 0; i < Math.min(2, labels.size()); i++) {
                                topLabels.add(labels.get(i).getText());
                            }

                            // Log the top labels
                            Log.d("Label", "Top labels: " + topLabels);

                            // Use the top 2 labels to search in Firestore
                            searchByLabels(topLabels);
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(getContext(), "Failed to label image", Toast.LENGTH_SHORT).show();
                            progressBar.setVisibility(View.GONE);
                        });
            } catch (IOException e) {
                Toast.makeText(getContext(), "Error processing image", Toast.LENGTH_SHORT).show();
                progressBar.setVisibility(View.GONE);
            }
        } else {
            Toast.makeText(getContext(), "No Image Selected", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchByText() {
        String query = searchQuery.getText().toString().trim();
        if (!query.isEmpty()) {
            progressBar.setVisibility(View.VISIBLE);
            db.collection("items")
                    .whereEqualTo("name", query)
                    .get()
                    .addOnCompleteListener(task -> {
                        progressBar.setVisibility(View.GONE);
                        if (task.isSuccessful()) {
                            List<Item> itemList = new ArrayList<>();
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                itemList.add(document.toObject(Item.class));
                            }
                            displaySearchResults(itemList);
                        } else {
                            Toast.makeText(getContext(), "Search failed", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            Toast.makeText(getContext(), "Please enter a search query", Toast.LENGTH_SHORT).show();
        }
    }

    private void searchByLabels(List<String> labels) {
        if (labels != null && !labels.isEmpty()) {
            progressBar.setVisibility(View.VISIBLE);
            db.collection("items")
                    .whereArrayContainsAny("labels", labels)  // Assuming Firestore stores labels as arrays
                    .get()
                    .addOnCompleteListener(task -> {
                        progressBar.setVisibility(View.GONE);
                        if (task.isSuccessful()) {
                            List<Item> itemList = new ArrayList<>();
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                itemList.add(document.toObject(Item.class));
                            }
                            displaySearchResults(itemList);
                        } else {
                            Toast.makeText(getContext(), "Search by image failed", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            Toast.makeText(getContext(), "No labels found for image", Toast.LENGTH_SHORT).show();
        }
    }

    private void displaySearchResults(List<Item> itemList) {
        itemAdapter.setItemList(itemList);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                captureImage();
            } else {
                Toast.makeText(getContext(), "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
