package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;

public record ExplainWordRequest(
        @NotBlank(message = "词语不能为空")
        String word,
        String sentence,
        String language,
        String interfaceLanguage
) {
}
