package com.example.lingualink.dto;

import com.example.lingualink.model.SubtitleSegment;
import com.example.lingualink.model.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public record ImportTaskRequest(
        String id,
        @NotBlank(message = "媒体链接不能为空")
        String mediaUrl,
        String folderId,
        @NotBlank(message = "源语言不能为空")
        String sourceLanguage,
        @NotBlank(message = "翻译语言不能为空")
        @Pattern(regexp = "([a-z]{2})(,[a-z]{2})*", message = "翻译语言格式应为 zh,en,ja")
        String targetLanguages,
        String mediaTitle,
        List<SubtitleSegment> segments,
        String audioUrl,
        String subtitleUrl,
        String coverUrl,
        Instant createdAt,
        TaskStatus status,
        Integer progress,
        String errorMessage,
        Boolean audioAvailable
) {
}
