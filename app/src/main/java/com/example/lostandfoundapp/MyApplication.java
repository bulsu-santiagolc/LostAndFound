package com.example.lostandfoundapp;

import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MyApplication extends Application {

    private Interpreter tflite;
    private List<String> labels;

    @Override
    public void onCreate() {
        super.onCreate();

        // Load the TensorFlow Lite model
        try {
            tflite = new Interpreter(loadModelFile());
            labels = loadLabels();  // Ensure this method correctly initializes the labels list
        } catch (IOException e) {
            Log.e("MyApplication", "Error loading model or labels", e);
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd("model.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private List<String> loadLabels() throws IOException {
        List<String> labelList = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(this.getAssets().open("labels.txt")));
        String line;
        while ((line = reader.readLine()) != null) {
            labelList.add(line);
        }
        reader.close();
        return labelList;
    }

    public Interpreter getTflite() {
        return tflite;
    }

    public List<String> getLabels() {
        return labels;
    }
}
