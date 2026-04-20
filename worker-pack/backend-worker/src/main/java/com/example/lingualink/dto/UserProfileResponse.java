package com.example.lingualink.dto;

public record UserProfileResponse(
        String id,
        String email,
        String avatarDataUrl,
        String preferredContentLanguage
) {
}
