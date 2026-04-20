package com.example.lingualink.model;

public record WordExplanation(
        String word,
        String reading,
        String meaning,
        String usage,
        String example,
        String source
) {
}
