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

public class UploadItemFragment extends Fragment {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAPTURE_IMAGE_REQUEST = 2;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int IMAGE_SIZE = 224; // Adjust this based on your model's input size
    private Uri imageUri;
    private Bitmap resizedBitmap; // Store resized bitmap
    private ImageView imageView;
    private EditText itemName, itemCategory;
    private TextView uploadStatus, labelResults;
    private ProgressBar progressBar;
    private FirebaseFirestore db;
    private StorageReference storageRef;

    // TensorFlow Lite model and labels
    private Interpreter tflite;
    private List<String> labels;

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

        // Initialize TensorFlow Lite model and labels
        tflite = ((MyApplication) getActivity().getApplication()).getTflite();
        labels = ((MyApplication) getActivity().getApplication()).getLabels();
        if (labels != null && !labels.isEmpty()) {
            Log.d("UploadItemFragment", "Labels loaded successfully: " + labels.toString());
        } else {
            Log.e("UploadItemFragment", "Labels failed to load or are empty.");
        }

        return view;
    }

    private void checkPermissionsAndCapture() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
        } else {
            launchCamera();
        }
    }

    private void launchCamera() {
        Log.d("UploadItemFragment", "Launching Camera");
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getActivity().getPackageManager()) != null) {
            startActivityForResult(cameraIntent, CAPTURE_IMAGE_REQUEST);
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
            try {
                if (requestCode == PICK_IMAGE_REQUEST && data != null) {
                    imageUri = data.getData();
                } else if (requestCode == CAPTURE_IMAGE_REQUEST && data != null) {
                    // Get the captured image as a Bitmap
                    Bitmap capturedImage = (Bitmap) data.getExtras().get("data");
                    if (capturedImage != null) {
                        imageUri = Uri.parse(MediaStore.Images.Media.insertImage(getContext().getContentResolver(), capturedImage, "Captured Image", null));
                    } else {
                        Toast.makeText(getContext(), "Error capturing image", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                // Load the image and resize it immediately after selection or capture
                if (imageUri != null) {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContext().getContentResolver(), imageUri);
                    resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, false);
                    imageView.setImageBitmap(resizedBitmap); // Display the resized image
                }

            } catch (IOException e) {
                Log.e("ImageProcessingError", "Error processing image", e);
                Toast.makeText(getContext(), "Error processing image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void labelAndUploadImage() {
        if (resizedBitmap != null) {
            try {
                progressBar.setVisibility(View.VISIBLE);

                // Run TensorFlow Lite model directly on the resized bitmap
                String tfLiteLabel = classifyImage(resizedBitmap);

                // Extract just the label text, removing any leading numbers
                String labelText = tfLiteLabel.replaceAll("^[0-9]+\\s+", "").toLowerCase(); // Remove numbers and trim

                // Display the TensorFlow Lite result in the labelResults TextView
                labelResults.setText("TensorFlow Lite Label: " + labelText);

                // Use the TensorFlow Lite result as the label to upload to Firestore
                List<String> tfLiteLabels = new ArrayList<>();
                tfLiteLabels.add(labelText);

                // Upload image and data to Firebase using TensorFlow Lite label
                uploadImageAndDataToFirebase(tfLiteLabels);

            } catch (Exception e) {
                Log.e("ModelError", "Error running TensorFlow Lite model", e);
                Toast.makeText(getContext(), "Model processing failed", Toast.LENGTH_SHORT).show();
                progressBar.setVisibility(View.GONE);
            }
        } else {
            updateStatus("No Image Selected", false);
        }
    }

    private String classifyImage(Bitmap image) {
        try {
            // Ensure the labels are initialized
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

            // Return the label with the highest confidence
            return labels.get(maxPos);

        } catch (Exception e) {
            Log.e("ModelError", "Error running TensorFlow Lite model", e);
            Toast.makeText(getContext(), "Model processing failed", Toast.LENGTH_SHORT).show();
            return null;
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
        String name = itemName.getText().toString().trim().toLowerCase(); // Normalize to lowercase
        String category = itemCategory.getText().toString().trim().toLowerCase(); // Normalize to lowercase
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
                launchCamera();
            } else {
                Toast.makeText(getContext(), "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
