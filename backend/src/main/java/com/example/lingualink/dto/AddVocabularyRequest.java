package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;

public record AddVocabularyRequest(
        @NotBlank(message = "词语不能为空")
        String word,
        String reading,
        @NotBlank(message = "词义不能为空")
        String meaning,
        String usage,
        String example,
        String sentence,
        String language
) {
}
