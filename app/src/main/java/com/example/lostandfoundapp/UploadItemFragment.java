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
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
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

public class UploadItemFragment extends Fragment {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAPTURE_IMAGE_REQUEST = 2;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private Uri imageUri;
    private ImageView imageView;
    private EditText itemName, itemCategory;
    private TextView uploadStatus, labelResults;
    private ProgressBar progressBar;
    private FirebaseFirestore db;
    private StorageReference storageRef;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_upload, container, false);

        db = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();

        imageView = view.findViewById(R.id.itemImage);
        itemName = view.findViewById(R.id.itemName);
        itemCategory = view.findViewById(R.id.itemCategory);
        uploadStatus = view.findViewById(R.id.uploadStatus);
        labelResults = view.findViewById(R.id.labelResults);
        progressBar = view.findViewById(R.id.progressBar);
        Button btnUploadImage = view.findViewById(R.id.buttonUploadImage);
        Button btnCaptureImage = view.findViewById(R.id.buttonCaptureImage);
        Button btnSubmit = view.findViewById(R.id.buttonSubmit);

        btnUploadImage.setOnClickListener(v -> openFileChooser());
        btnCaptureImage.setOnClickListener(v -> checkPermissionsAndCapture());
        btnSubmit.setOnClickListener(v -> {
            if (validateInput()) {
                labelAndUploadImage();
            }
        });

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

            Log.d("UploadItemFragment", "Starting camera intent");
            startActivityForResult(intent, CAPTURE_IMAGE_REQUEST);
        } else {
            Toast.makeText(getContext(), "Camera not available", Toast.LENGTH_SHORT).show();
            Log.e("UploadItemFragment", "Camera not available");
        }
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d("UploadItemFragment", "onActivityResult: requestCode = " + requestCode + ", resultCode = " + resultCode);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == PICK_IMAGE_REQUEST && data != null) {
                imageUri = data.getData();
                imageView.setImageURI(imageUri);
            } else if (requestCode == CAPTURE_IMAGE_REQUEST) {
                imageView.setImageURI(imageUri);
            }
        }
    }

    private void labelAndUploadImage() {
        if (imageUri != null) {
            try {
                InputImage image = InputImage.fromFilePath(getContext(), imageUri);
                ImageLabeler labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);

                progressBar.setVisibility(View.VISIBLE);

                labeler.process(image)
                        .addOnSuccessListener(labels -> {
                            if (labels.isEmpty()) {
                                labelResults.setText("No labels detected.");
                                updateStatus("No labels detected", false);
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

                            labelResults.setText("Top Labels: " + topLabels);
                            uploadImageAndDataToFirebase(topLabels);
                        })
                        .addOnFailureListener(e -> {
                            Log.e("LabelError", "Labeling failed", e);
                            updateStatus("Failed to label image", false);
                            progressBar.setVisibility(View.GONE);
                        });
            } catch (IOException e) {
                Log.e("ImageProcessingError", "Error processing image", e);
                Toast.makeText(getContext(), "Error processing image", Toast.LENGTH_SHORT).show();
                progressBar.setVisibility(View.GONE);
            }
        } else {
            updateStatus("No Image Selected", false);
        }
    }


    private void uploadImageAndDataToFirebase(List<String> labels) {
        StorageReference fileReference = storageRef.child("uploads/" + System.currentTimeMillis() + ".jpg");
        fileReference.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot -> fileReference.getDownloadUrl().addOnSuccessListener(uri -> {
                    String downloadUrl = uri.toString();
                    saveItemData(downloadUrl, labels);
                    updateStatus("Image Uploaded Successfully", true);
                    progressBar.setVisibility(View.GONE);
                    resetFields();
                }).addOnFailureListener(e -> {
                    updateStatus("Failed to get download URL", false);
                    progressBar.setVisibility(View.GONE);
                }))
                .addOnFailureListener(e -> {
                    updateStatus("Failed to Upload Image", false);
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Network issue, please try again", Toast.LENGTH_SHORT).show();
                });
    }

    private void saveItemData(String imageUrl, List<String> labels) {
        String name = itemName.getText().toString().trim();
        String category = itemCategory.getText().toString().trim();
        Date dateAdded = new Date();

        Item item = new Item(name, category, imageUrl, dateAdded, labels);
        db.collection("items").add(item)
                .addOnSuccessListener(documentReference -> updateStatus("Item Added", true))
                .addOnFailureListener(e -> updateStatus("Error adding document", false));
    }

    private boolean validateInput() {
        String name = itemName.getText().toString().trim();
        String category = itemCategory.getText().toString().trim();
        if (name.isEmpty() || category.isEmpty()) {
            Toast.makeText(getContext(), "Please fill all fields", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void resetFields() {
        imageView.setImageURI(null);
        itemName.setText("");
        itemCategory.setText("");
        labelResults.setText("");
    }

    private void updateStatus(String message, boolean success) {
        uploadStatus.setText(message);
        uploadStatus.setTextColor(success ? getResources().getColor(android.R.color.holo_green_dark) : getResources().getColor(android.R.color.holo_red_dark));
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
