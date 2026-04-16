package com.example.lingualink.model;

import java.util.Map;

public record SubtitleSegment(
        String id,
        double startSeconds,
        double endSeconds,
        String originalText,
        Map<String, String> translations
) {
}
