package com.example.lingualink.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "folders")
public class TaskFolder {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;
    private String parentId;
    private String kind;
    private String contentLanguage;

    @Lob
    @Column(columnDefinition = "longtext")
    private String coverImageDataUrl;
    private Integer coverOpacity;
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getContentLanguage() {
        return contentLanguage;
    }

    public void setContentLanguage(String contentLanguage) {
        this.contentLanguage = contentLanguage;
    }

    public String getCoverImageDataUrl() {
        return coverImageDataUrl;
    }

    public void setCoverImageDataUrl(String coverImageDataUrl) {
        this.coverImageDataUrl = coverImageDataUrl;
    }

    public Integer getCoverOpacity() {
        return coverOpacity;
    }

    public void setCoverOpacity(Integer coverOpacity) {
        this.coverOpacity = coverOpacity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
