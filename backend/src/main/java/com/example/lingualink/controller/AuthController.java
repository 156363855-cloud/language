package com.example.lingualink.controller;

import com.example.lingualink.dto.AuthRequest;
import com.example.lingualink.dto.AuthResponse;
import com.example.lingualink.dto.UpdateProfileRequest;
import com.example.lingualink.dto.UserProfileResponse;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody AuthRequest request) {
        AuthService.SessionResult result = authService.register(request);
        return new AuthResponse(result.token(), toProfile(result.user()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest request) {
        AuthService.SessionResult result = authService.login(request);
        return new AuthResponse(result.token(), toProfile(result.user()));
    }

    @GetMapping("/me")
    public UserProfileResponse me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        return toProfile(authService.requireUser(authorizationHeader));
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        authService.logout(authorizationHeader);
        return Map.of("message", "已退出登录");
    }

    @PatchMapping("/profile")
    public UserProfileResponse updateProfile(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestBody UpdateProfileRequest request
    ) {
        return toProfile(authService.updateProfile(authorizationHeader, request));
    }

    private UserProfileResponse toProfile(UserAccount user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getAvatarDataUrl(),
                user.getPreferredContentLanguage() == null || user.getPreferredContentLanguage().isBlank()
                        ? "en"
                        : user.getPreferredContentLanguage()
        );
    }
}
