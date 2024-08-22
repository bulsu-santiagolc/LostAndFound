package com.example.lostandfoundapp;

import java.util.Date;
import java.util.List;

public class Item {
    private String name;
    private String category;
    private String imageUrl;
    private Date dateAdded;
    private List<String> labels;  // Ensure this field is present

    public Item() {
        // No-arg constructor required for Firestore
    }

    public Item(String name, String category, String imageUrl, Date dateAdded, List<String> labels) {
        this.name = name;
        this.category = category;
        this.imageUrl = imageUrl;
        this.dateAdded = dateAdded;
        this.labels = labels;
    }

    // Getters and Setters for all fields, including labels
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Date getDateAdded() {
        return dateAdded;
    }

    public void setDateAdded(Date dateAdded) {
        this.dateAdded = dateAdded;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
}

