package com.example.lingualink.service;

import com.example.lingualink.dto.AuthRequest;
import com.example.lingualink.dto.UpdateProfileRequest;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.model.UserSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final Map<String, UserAccount> usersById = new ConcurrentHashMap<>();
    private final Map<String, String> userIdByEmail = new ConcurrentHashMap<>();
    private final Map<String, UserSession> sessionsByToken = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path applicationRoot = Path.of("").toAbsolutePath();
    private final Path repositoryRoot = resolveRepositoryRoot(applicationRoot);
    private final Path runtimeRoot = repositoryRoot.resolve("backend").resolve("runtime");
    private final Path usersStore = runtimeRoot.resolve("users.json");
    private final Path sessionsStore = runtimeRoot.resolve("sessions.json");

    public AuthService() throws IOException {
        Files.createDirectories(runtimeRoot);
        loadUsers();
        loadSessions();
    }

    public synchronized SessionResult register(AuthRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userIdByEmail.containsKey(normalizedEmail)) {
            throw new IllegalArgumentException("这个邮箱已经注册过了");
        }

        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID().toString());
        account.setEmail(normalizedEmail);
        account.setPasswordHash(hashPassword(request.password()));
        account.setPreferredContentLanguage("en");
        account.setCreatedAt(Instant.now());

        usersById.put(account.getId(), account);
        userIdByEmail.put(account.getEmail(), account.getId());
        persistUsers();

        UserSession session = createSession(account.getId());
        return new SessionResult(session.getToken(), account);
    }

    public synchronized SessionResult login(AuthRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        String userId = userIdByEmail.get(normalizedEmail);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }

        UserAccount account = usersById.get(userId);
        if (account == null || !account.getPasswordHash().equals(hashPassword(request.password()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }

        UserSession session = createSession(account.getId());
        return new SessionResult(session.getToken(), account);
    }

    public synchronized UserAccount requireUser(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        UserSession session = sessionsByToken.get(token);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }

        UserAccount user = usersById.get(session.getUserId());
        if (user == null) {
            sessionsByToken.remove(token);
            persistSessions();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        session.setLastUsedAt(Instant.now());
        persistSessions();
        return user;
    }

    public synchronized void logout(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        sessionsByToken.remove(token);
        persistSessions();
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
        persistUsers();
        return user;
    }

    private synchronized UserSession createSession(String userId) {
        UserSession session = new UserSession();
        session.setToken(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setCreatedAt(Instant.now());
        session.setLastUsedAt(Instant.now());
        sessionsByToken.put(session.getToken(), session);
        persistSessions();
        return session;
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

    private void loadUsers() throws IOException {
        if (!Files.exists(usersStore)) {
            persistUsers();
            return;
        }

        List<UserAccount> users = objectMapper.readValue(usersStore.toFile(), new TypeReference<>() {});
        for (UserAccount user : users) {
            usersById.put(user.getId(), user);
            userIdByEmail.put(user.getEmail(), user.getId());
        }
    }

    private void loadSessions() throws IOException {
        if (!Files.exists(sessionsStore)) {
            persistSessions();
            return;
        }

        List<UserSession> sessions = objectMapper.readValue(sessionsStore.toFile(), new TypeReference<>() {});
        for (UserSession session : sessions) {
            sessionsByToken.put(session.getToken(), session);
        }
    }

    private synchronized void persistUsers() {
        try {
            List<UserAccount> users = new ArrayList<>(usersById.values()).stream()
                    .sorted(Comparator.comparing(UserAccount::getCreatedAt))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(usersStore.toFile(), users);
        } catch (IOException exception) {
            throw new IllegalStateException("保存用户失败", exception);
        }
    }

    private synchronized void persistSessions() {
        try {
            List<UserSession> sessions = new ArrayList<>(sessionsByToken.values()).stream()
                    .sorted(Comparator.comparing(UserSession::getCreatedAt))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(sessionsStore.toFile(), sessions);
        } catch (IOException exception) {
            throw new IllegalStateException("保存登录状态失败", exception);
        }
    }

    private Path resolveRepositoryRoot(Path start) {
        Path current = start;
        while (current != null && !Files.exists(current.resolve("frontend")) && !Files.exists(current.resolve("backend"))) {
            current = current.getParent();
        }
        return current == null ? start : current;
    }

    public record SessionResult(String token, UserAccount user) {
    }
}
