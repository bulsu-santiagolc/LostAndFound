package com.example.lostandfoundapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
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
import android.widget.TextView;
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

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SearchItemFragment extends Fragment {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAPTURE_IMAGE_REQUEST = 2;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int IMAGE_SIZE = 224; // Adjust this based on your model's input size
    private Uri imageUri;
    private EditText searchQuery;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private FirebaseFirestore db;
    private ItemAdapter itemAdapter;
    private List<Item> itemList;

    // TensorFlow Lite model and labels
    private Interpreter tflite;
    private List<String> labels;

    // New elements for displaying TensorFlow results and submitting search
    private TextView labelResults;
    private Button submitImageSearchButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search, container, false);

        db = FirebaseFirestore.getInstance();
        searchQuery = view.findViewById(R.id.searchQuery);
        Button searchButton = view.findViewById(R.id.searchButton);
        Button captureImageButton = view.findViewById(R.id.captureImageButton);
        Button uploadImageButton = view.findViewById(R.id.uploadImageButton);
        labelResults = view.findViewById(R.id.labelResults);
        submitImageSearchButton = view.findViewById(R.id.submitImageSearchButton);
        progressBar = view.findViewById(R.id.progressBar);
        recyclerView = view.findViewById(R.id.recyclerView);

        itemList = new ArrayList<>();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        itemAdapter = new ItemAdapter(itemList, getContext());
        recyclerView.setAdapter(itemAdapter);

        searchButton.setOnClickListener(v -> searchByText());
        captureImageButton.setOnClickListener(v -> checkPermissionsAndCapture());
        uploadImageButton.setOnClickListener(v -> openFileChooser());
        submitImageSearchButton.setOnClickListener(v -> submitImageSearch());

        // Initialize TensorFlow Lite model and labels
        tflite = ((MyApplication) getActivity().getApplication()).getTflite();
        labels = ((MyApplication) getActivity().getApplication()).getLabels();
        if (labels != null && !labels.isEmpty()) {
            Log.d("SearchItemFragment", "Labels loaded successfully: " + labels.toString());
        } else {
            Log.e("SearchItemFragment", "Labels failed to load or are empty.");
        }

        return view;
    }

    private void checkPermissionsAndCapture() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
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
                labelAndDisplayResults();
            } else if (requestCode == CAPTURE_IMAGE_REQUEST) {
                labelAndDisplayResults();
            }
        }
    }

    private void labelAndDisplayResults() {
        if (imageUri != null) {
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContext().getContentResolver(), imageUri);
                Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, false);
                String tfLiteLabel = classifyImage(resizedBitmap);

                if (tfLiteLabel != null && !tfLiteLabel.isEmpty()) {
                    labelResults.setText("Detected Label: " + tfLiteLabel.trim()); // Display the label result
                    labelResults.setVisibility(View.VISIBLE);
                } else {
                    Toast.makeText(getContext(), "No labels detected", Toast.LENGTH_SHORT).show();
                }

            } catch (IOException e) {
                Toast.makeText(getContext(), "Error processing image", Toast.LENGTH_SHORT).show();
                progressBar.setVisibility(View.GONE);
            }
        } else {
            Toast.makeText(getContext(), "No Image Selected", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitImageSearch() {
        String detectedLabel = labelResults.getText().toString().replace("Detected Label: ", "").trim();
        if (!detectedLabel.isEmpty()) {
            List<String> labelsToSearch = new ArrayList<>();
            labelsToSearch.add(detectedLabel);
            searchByLabels(labelsToSearch);
        } else {
            Toast.makeText(getContext(), "No valid label to search for", Toast.LENGTH_SHORT).show();
        }
    }

    private String classifyImage(Bitmap image) {
        try {
            if (labels == null || labels.isEmpty()) {
                throw new NullPointerException("Labels not initialized.");
            }

            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, IMAGE_SIZE, IMAGE_SIZE, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3);
            byteBuffer.order(ByteOrder.nativeOrder());

            int[] intValues = new int[IMAGE_SIZE * IMAGE_SIZE];
            image.getPixels(intValues, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());

            int pixel = 0;
            for (int i = 0; i < IMAGE_SIZE; i++) {
                for (int j = 0; j < IMAGE_SIZE; j++) {
                    int val = intValues[pixel++];
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
                }
            }

            inputFeature0.loadBuffer(byteBuffer);

            TensorBuffer outputFeature0 = TensorBuffer.createFixedSize(new int[]{1, labels.size()}, DataType.FLOAT32);
            tflite.run(inputFeature0.getBuffer(), outputFeature0.getBuffer());

            float[] confidences = outputFeature0.getFloatArray();
            int maxPos = 0;
            float maxConfidence = 0;
            for (int i = 0; i < confidences.length; i++) {
                if (confidences[i] > maxConfidence) {
                    maxConfidence = confidences[i];
                    maxPos = i;
                }
            }

            // Extract the label without the preceding number
            String labelWithNumber = labels.get(maxPos).trim().toLowerCase();
            String[] labelParts = labelWithNumber.split(" ", 2); // Split by first space
            return labelParts.length > 1 ? labelParts[1] : labelParts[0]; // Return only the class name

        } catch (Exception e) {
            Log.e("ModelError", "Error running TensorFlow Lite model", e);
            Toast.makeText(getContext(), "Model processing failed", Toast.LENGTH_SHORT).show();
            return null;
        }
    }


    private void searchByText() {
        String query = searchQuery.getText().toString().trim().toLowerCase(); // Normalize to lowercase
        if (!query.isEmpty()) {
            progressBar.setVisibility(View.VISIBLE);

            // Query by name, category, and labels
            db.collection("items")
                    .whereGreaterThanOrEqualTo("name", query)
                    .whereLessThanOrEqualTo("name", query + "\uf8ff")
                    .get()
                    .addOnCompleteListener(taskName -> {
                        List<Item> results = new ArrayList<>();
                        if (taskName.isSuccessful()) {
                            for (QueryDocumentSnapshot document : taskName.getResult()) {
                                results.add(document.toObject(Item.class));
                            }
                        }

                        // Continue with category search
                        db.collection("items")
                                .whereGreaterThanOrEqualTo("category", query)
                                .whereLessThanOrEqualTo("category", query + "\uf8ff")
                                .get()
                                .addOnCompleteListener(taskCategory -> {
                                    if (taskCategory.isSuccessful()) {
                                        for (QueryDocumentSnapshot document : taskCategory.getResult()) {
                                            Item item = document.toObject(Item.class);
                                            if (!results.contains(item)) { // Avoid duplicates
                                                results.add(item);
                                            }
                                        }
                                    }

                                    // Continue with labels search
                                    db.collection("items")
                                            .whereArrayContains("labels", query)
                                            .get()
                                            .addOnCompleteListener(taskLabels -> {
                                                progressBar.setVisibility(View.GONE);
                                                if (taskLabels.isSuccessful()) {
                                                    for (QueryDocumentSnapshot document : taskLabels.getResult()) {
                                                        Item item = document.toObject(Item.class);
                                                        if (!results.contains(item)) { // Avoid duplicates
                                                            results.add(item);
                                                        }
                                                    }
                                                    displaySearchResults(results);
                                                } else {
                                                    Log.e("SearchItemFragment", "Error getting documents: ", taskLabels.getException());
                                                    Toast.makeText(getContext(), "Search by labels failed", Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                });
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
                            Log.e("SearchItemFragment", "Error getting documents: ", task.getException());
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
