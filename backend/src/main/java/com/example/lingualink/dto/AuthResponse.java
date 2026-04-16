package com.example.lingualink.dto;

public record AuthResponse(
        String token,
        UserProfileResponse user
) {
}
