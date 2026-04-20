package com.example.lingualink.model;

import com.example.lingualink.persistence.StringListJsonConverter;
import com.example.lingualink.persistence.SubtitleSegmentListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transcription_tasks")
public class TranscriptionTask {

    @Id
    private String id;

    @Lob
    @Column(columnDefinition = "longtext")
    private String mediaUrl;
    private String folderId;
    private String sourceLanguage;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "json", nullable = false)
    private List<String> targetLanguages = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private TaskStatus status;
    private int progress;
    private Instant createdAt;
    private Instant updatedAt;

    @Lob
    @Column(columnDefinition = "longtext")
    private String mediaTitle;

    @Lob
    @Column(columnDefinition = "longtext")
    private String errorMessage;

    @Convert(converter = SubtitleSegmentListJsonConverter.class)
    @Column(columnDefinition = "json", nullable = false)
    private List<SubtitleSegment> segments = new ArrayList<>();
    private boolean audioAvailable;

    @Lob
    @Column(columnDefinition = "longtext")
    private String audioUrl;

    @Lob
    @Column(columnDefinition = "longtext")
    private String subtitleUrl;

    @Lob
    @Column(columnDefinition = "longtext")
    private String coverUrl;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    public void setSourceLanguage(String sourceLanguage) {
        this.sourceLanguage = sourceLanguage;
    }

    public List<String> getTargetLanguages() {
        return targetLanguages;
    }

    public void setTargetLanguages(List<String> targetLanguages) {
        this.targetLanguages = targetLanguages;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getMediaTitle() {
        return mediaTitle;
    }

    public void setMediaTitle(String mediaTitle) {
        this.mediaTitle = mediaTitle;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<SubtitleSegment> getSegments() {
        return segments;
    }

    public void setSegments(List<SubtitleSegment> segments) {
        this.segments = segments;
    }

    public boolean isAudioAvailable() {
        return audioAvailable;
    }

    public void setAudioAvailable(boolean audioAvailable) {
        this.audioAvailable = audioAvailable;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public String getSubtitleUrl() {
        return subtitleUrl;
    }

    public void setSubtitleUrl(String subtitleUrl) {
        this.subtitleUrl = subtitleUrl;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }
}
