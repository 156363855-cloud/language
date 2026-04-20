package com.example.lingualink.service;

import com.example.lingualink.dto.AuthRequest;
import com.example.lingualink.dto.UpdateProfileRequest;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.model.UserSession;
import com.example.lingualink.repository.UserAccountRepository;
import com.example.lingualink.repository.UserSessionRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final UserSessionRepository userSessionRepository;

    public AuthService(
            RuntimeJsonMigrationService runtimeJsonMigrationService,
            UserAccountRepository userAccountRepository,
            UserSessionRepository userSessionRepository
    ) throws java.io.IOException {
        runtimeJsonMigrationService.migrateIfNeeded();
        this.userAccountRepository = userAccountRepository;
        this.userSessionRepository = userSessionRepository;
    }

    public synchronized SessionResult register(AuthRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userAccountRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("这个邮箱已经注册过了");
        }

        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID().toString());
        account.setEmail(normalizedEmail);
        account.setPasswordHash(hashPassword(request.password()));
        account.setPreferredContentLanguage("en");
        account.setCreatedAt(Instant.now());

        userAccountRepository.save(account);

        UserSession session = createSession(account.getId());
        return new SessionResult(session.getToken(), account);
    }

    public synchronized SessionResult login(AuthRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        UserAccount account = userAccountRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确"));
        if (!account.getPasswordHash().equals(hashPassword(request.password()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }

        UserSession session = createSession(account.getId());
        return new SessionResult(session.getToken(), account);
    }

    public synchronized UserAccount requireUser(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        UserSession session = userSessionRepository.findById(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));

        UserAccount user = userAccountRepository.findById(session.getUserId()).orElse(null);
        if (user == null) {
            userSessionRepository.deleteById(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        session.setLastUsedAt(Instant.now());
        userSessionRepository.save(session);
        return user;
    }

    public synchronized void logout(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        userSessionRepository.deleteById(token);
    }

    public synchronized UserAccount updateProfile(String authorizationHeader, UpdateProfileRequest request) {
        UserAccount user = requireUser(authorizationHeader);
        if (request.avatarDataUrl() != null) {
          user.setAvatarDataUrl(request.avatarDataUrl().isBlank() ? "" : request.avatarDataUrl().trim());
        }
        if (request.preferredContentLanguage() != null && !request.preferredContentLanguage().isBlank()) {
            String normalized = request.preferredContentLanguage().trim().toLowerCase();
            if (!List.of("zh", "en", "ja").contains(normalized)) {
                throw new IllegalArgumentException("内容语言只支持 zh、en 或 ja");
            }
            user.setPreferredContentLanguage(normalized);
        }
        return userAccountRepository.save(user);
    }

    private synchronized UserSession createSession(String userId) {
        UserSession session = new UserSession();
        session.setToken(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setCreatedAt(Instant.now());
        session.setLastUsedAt(Instant.now());
        return userSessionRepository.save(session);
    }

    private String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录凭证格式不正确");
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录凭证不能为空");
        }
        return token;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String hashPassword(String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("密码加密失败", exception);
        }
    }

    public record SessionResult(String token, UserAccount user) {
    }
}
