package com.example.lingualink.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.ArrayList;
import java.util.List;

public record CreateTaskRequest(
        @NotBlank(message = "媒体链接不能为空")
        String mediaUrl,
        @NotBlank(message = "源语言不能为空")
        String sourceLanguage,
        @NotBlank(message = "翻译语言不能为空")
        @Pattern(regexp = "([a-z]{2})(,[a-z]{2})*", message = "翻译语言格式应为 zh,en,ja")
        String targetLanguages,
        String folderId
) {
    @JsonCreator
    public static CreateTaskRequest create(
            @JsonProperty("mediaUrl") JsonNode mediaUrl,
            @JsonProperty("sourceLanguage") JsonNode sourceLanguage,
            @JsonProperty("targetLanguages") JsonNode targetLanguages,
            @JsonProperty("folderId") JsonNode folderId
    ) {
        return new CreateTaskRequest(
                asText(mediaUrl),
                asText(sourceLanguage),
                normalizeTargetLanguages(targetLanguages),
                asText(folderId)
        );
    }

    private static String normalizeTargetLanguages(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode child : node) {
                String value = asText(child);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
            return String.join(",", values);
        }
        return asText(node);
    }

    private static String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null ? null : value.trim();
    }
}
