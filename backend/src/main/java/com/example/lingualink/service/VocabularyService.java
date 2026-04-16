package com.example.lingualink.service;

import com.example.lingualink.dto.AddVocabularyRequest;
import com.example.lingualink.model.VocabularyItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class VocabularyService {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path applicationRoot = Path.of("").toAbsolutePath();
    private final Path repositoryRoot = resolveRepositoryRoot(applicationRoot);
    private final Path vocabularyRoot = repositoryRoot.resolve("backend").resolve("runtime").resolve("vocabulary");

    public VocabularyService() throws IOException {
        Files.createDirectories(vocabularyRoot);
    }

    public synchronized List<VocabularyItem> listVocabulary(String userId) {
        return loadVocabulary(userId).stream()
                .sorted(Comparator.comparing(VocabularyItem::getCreatedAt).reversed())
                .toList();
    }

    public synchronized VocabularyItem addVocabulary(String userId, AddVocabularyRequest request) {
        List<VocabularyItem> items = new ArrayList<>(loadVocabulary(userId));
        boolean exists = items.stream().anyMatch(item ->
                item.getWord().equalsIgnoreCase(request.word().trim())
                        && normalize(item.getSentence()).equals(normalize(request.sentence()))
        );
        if (exists) {
            throw new IllegalArgumentException("这个词已经在生词本里了");
        }

        VocabularyItem item = new VocabularyItem();
        item.setId(UUID.randomUUID().toString());
        item.setWord(request.word().trim());
        item.setReading(normalizeNullable(request.reading()));
        item.setMeaning(request.meaning().trim());
        item.setUsage(normalizeNullable(request.usage()));
        item.setExample(normalizeNullable(request.example()));
        item.setSentence(normalizeNullable(request.sentence()));
        item.setLanguage(normalizeNullable(request.language(), "ja"));
        item.setCreatedAt(Instant.now());
        items.add(item);
        persistVocabulary(userId, items);
        return item;
    }

    public synchronized void deleteVocabulary(String userId, String itemId) {
        List<VocabularyItem> items = new ArrayList<>(loadVocabulary(userId));
        boolean removed = items.removeIf(item -> item.getId().equals(itemId));
        if (!removed) {
            throw new IllegalArgumentException("这个生词不存在");
        }
        persistVocabulary(userId, items);
    }

    private List<VocabularyItem> loadVocabulary(String userId) {
        Path userStore = vocabularyRoot.resolve(userId + ".json");
        if (!Files.exists(userStore)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(userStore.toFile(), new TypeReference<>() {});
        } catch (IOException exception) {
            throw new IllegalStateException("读取生词本失败", exception);
        }
    }

    private void persistVocabulary(String userId, List<VocabularyItem> items) {
        Path userStore = vocabularyRoot.resolve(userId + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(userStore.toFile(), items);
        } catch (IOException exception) {
            throw new IllegalStateException("保存生词本失败", exception);
        }
    }

    private String normalizeNullable(String value) {
        return normalizeNullable(value, "");
    }

    private String normalizeNullable(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private Path resolveRepositoryRoot(Path start) {
        Path current = start;
        while (current != null && !Files.exists(current.resolve("frontend")) && !Files.exists(current.resolve("backend"))) {
            current = current.getParent();
        }
        return current == null ? start : current;
    }
}
