package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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
}
