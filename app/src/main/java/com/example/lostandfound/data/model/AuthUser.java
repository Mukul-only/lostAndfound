package com.example.lostandfound.data.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public class AuthUser {
    @SerializedName("id")
    private String id;

    @SerializedName("email")
    private String email;

    @SerializedName("user_metadata")
    private Map<String, Object> userMetadata;

    public AuthUser() {}

    public AuthUser(String id, String email) {
        this.id = id;
        this.email = email;
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Map<String, Object> getUserMetadata() {
        return userMetadata;
    }
}
