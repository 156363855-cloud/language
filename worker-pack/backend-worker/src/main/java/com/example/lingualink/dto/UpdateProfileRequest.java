package com.example.lingualink.dto;

public record UpdateProfileRequest(
        String avatarDataUrl,
        String preferredContentLanguage
) {
}
