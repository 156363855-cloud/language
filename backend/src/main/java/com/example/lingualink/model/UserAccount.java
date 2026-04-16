package com.example.lingualink.model;

import java.time.Instant;

public class UserAccount {
    private String id;
    private String email;
    private String passwordHash;
    private String avatarDataUrl;
    private String preferredContentLanguage;
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getAvatarDataUrl() {
        return avatarDataUrl;
    }

    public void setAvatarDataUrl(String avatarDataUrl) {
        this.avatarDataUrl = avatarDataUrl;
    }

    public String getPreferredContentLanguage() {
        return preferredContentLanguage;
    }

    public void setPreferredContentLanguage(String preferredContentLanguage) {
        this.preferredContentLanguage = preferredContentLanguage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
