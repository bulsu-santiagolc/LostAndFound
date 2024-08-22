package com.example.lostandfoundapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
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

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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

    // TensorFlow Lite Interpreter
    private Interpreter tflite;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_upload, container, false);

        db = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();

        // Initialize the TensorFlow Lite model
        loadModel();

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

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getContext().getAssets().openFd("ResNet50.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void loadModel() {
        try {
            MappedByteBuffer model = loadModelFile();
            if (model != null) {
                tflite = new Interpreter(model);
                Log.d("Model", "Model loaded successfully");
            } else {
                Log.e("Model", "Failed to load model");
                Toast.makeText(getContext(), "Failed to load model", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e("Model", "IOException during model loading", e);
            Toast.makeText(getContext(), "Model loading failed", Toast.LENGTH_SHORT).show();
        }
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted(grantResults)) {
                captureImage();
            } else {
                Toast.makeText(getContext(), "Permissions are required to use the camera", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void captureImage() {
        if (getActivity() == null) return;

        // Create a file to store the image
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File imageFile = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JPEG_" + timeStamp + ".jpg");

        // Get URI for the file
        imageUri = FileProvider.getUriForFile(getContext(), getActivity().getPackageName() + ".provider", imageFile);

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
            startActivityForResult(intent, CAPTURE_IMAGE_REQUEST);
        } else {
            Toast.makeText(getContext(), "Camera not available", Toast.LENGTH_SHORT).show();
            Log.e("UploadItemFragment", "Camera not available");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
                // Perform TensorFlow Lite inference
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContext().getContentResolver(), imageUri);
                float[][] resnetOutput = runInference(bitmap);
                List<String> resnetLabels = getTopKLabels(resnetOutput);

                // Perform Firebase ML Kit inference
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

                            // Combine results with TensorFlow Lite
                            List<String> combinedLabels = new ArrayList<>(resnetLabels);
                            for (ImageLabel label : labels) {
                                combinedLabels.add(label.getText());
                            }

                            // Update the UI with combined labels
                            labelResults.setText("Labels: " + combinedLabels);

                            // Upload image and combined labels to Firebase
                            uploadImageAndDataToFirebase(combinedLabels);
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

    private float[][] runInference(Bitmap bitmap) {
        // Manually convert Bitmap to TensorImage
        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(bitmap);

        // Preprocess the tensor image (resize and normalize manually)
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .build();

        tensorImage = imageProcessor.process(tensorImage);

        // Get the buffer from TensorImage and manually normalize it
        float[] normalizedBuffer = tensorImage.getTensorBuffer().getFloatArray();
        for (int i = 0; i < normalizedBuffer.length; i++) {
            normalizedBuffer[i] = normalizedBuffer[i] / 255.0f;
        }

        // Create a new TensorBuffer with the normalized data
        TensorBuffer tensorBuffer = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
        tensorBuffer.loadArray(normalizedBuffer);

        // Run inference
        float[][] output = new float[1][1000];  // Assuming ResNet50 outputs 1000 classes
        tflite.run(tensorBuffer.getBuffer(), output);
        return output;
    }

    private List<String> getTopKLabels(float[][] output) {
        List<String> labels = loadLabels();

        // Create a map to store label-confidence pairs
        List<Pair<String, Float>> labelConfidencePairs = new ArrayList<>();

        // Iterate over the model output and map each output to its corresponding label
        for (int i = 0; i < output[0].length; i++) {
            labelConfidencePairs.add(new Pair<>(labels.get(i), output[0][i]));
        }

        // Sort the label-confidence pairs by confidence (descending)
        Collections.sort(labelConfidencePairs, (pair1, pair2) -> Float.compare(pair2.second, pair1.second));

        // Extract the top K labels (e.g., top 3)
        List<String> topKLabels = new ArrayList<>();
        for (int i = 0; i < Math.min(3, labelConfidencePairs.size()); i++) {
            topKLabels.add(labelConfidencePairs.get(i).first + " (Confidence: " + labelConfidencePairs.get(i).second + ")");
        }

        return topKLabels;
    }

    private List<String> loadLabels() {
        List<String> labels = new ArrayList<>();
        try {
            // Open the labels file
            InputStream inputStream = getContext().getAssets().open("labels.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line);
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return labels;
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
        if (name.isEmpty() || category.isEmpty() || imageUri == null) {
            Toast.makeText(getContext(), "Please fill all fields and select an image", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void resetFields() {
        itemName.setText("");
        itemCategory.setText("");
        imageView.setImageResource(android.R.color.transparent);
        imageUri = null;
        labelResults.setText("Labels: None");
    }

    private void updateStatus(String message, boolean success) {
        uploadStatus.setText(message);
        if (success) {
            uploadStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            uploadStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    static class Item {
        private String name;
        private String category;
        private String imageUrl;
        private Date dateAdded;
        private List<String> labels;

        public Item() {
        }

        public Item(String name, String category, String imageUrl, Date dateAdded, List<String> labels) {
            this.name = name;
            this.category = category;
            this.imageUrl = imageUrl;
            this.dateAdded = dateAdded;
            this.labels = labels;
        }

        // Getter for the name field
        public String getName() {
            return name;
        }

        // Setter for the name field
        public void setName(String name) {
            this.name = name;
        }

        // Getter for the category field
        public String getCategory() {
            return category;
        }

        // Setter for the category field
        public void setCategory(String category) {
            this.category = category;
        }

        // Getter for the imageUrl field
        public String getImageUrl() {
            return imageUrl;
        }

        // Setter for the imageUrl field
        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        // Getter for the dateAdded field
        public Date getDateAdded() {
            return dateAdded;
        }

        // Setter for the dateAdded field
        public void setDateAdded(Date dateAdded) {
            this.dateAdded = dateAdded;
        }

        // Getter for the list of labels
        public List<String> getLabels() {
            return labels;
        }

        // Setter for the list of labels
        public void setLabels(List<String> labels) {
            this.labels = labels;
        }

    }
}
