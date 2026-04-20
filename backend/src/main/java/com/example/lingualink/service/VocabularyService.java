package com.example.lingualink.service;

import com.example.lingualink.dto.AddVocabularyRequest;
import com.example.lingualink.model.VocabularyItem;
import com.example.lingualink.repository.VocabularyItemRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class VocabularyService {

    private final VocabularyItemRepository vocabularyItemRepository;

    public VocabularyService(
            RuntimeJsonMigrationService runtimeJsonMigrationService,
            VocabularyItemRepository vocabularyItemRepository
    ) throws IOException {
        runtimeJsonMigrationService.migrateIfNeeded();
        this.vocabularyItemRepository = vocabularyItemRepository;
    }

    public synchronized List<VocabularyItem> listVocabulary(String userId) {
        return vocabularyItemRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(VocabularyItem::getCreatedAt).reversed())
                .toList();
    }

    public synchronized VocabularyItem addVocabulary(String userId, AddVocabularyRequest request) {
        List<VocabularyItem> items = new ArrayList<>(vocabularyItemRepository.findByUserId(userId));
        boolean exists = items.stream().anyMatch(item ->
                item.getWord().equalsIgnoreCase(request.word().trim())
                        && normalize(item.getSentence()).equals(normalize(request.sentence()))
        );
        if (exists) {
            throw new IllegalArgumentException("这个词已经在生词本里了");
        }

        VocabularyItem item = new VocabularyItem();
        item.setId(UUID.randomUUID().toString());
        item.setUserId(userId);
        item.setWord(request.word().trim());
        item.setReading(normalizeNullable(request.reading()));
        item.setMeaning(request.meaning().trim());
        item.setUsage(normalizeNullable(request.usage()));
        item.setExample(normalizeNullable(request.example()));
        item.setSentence(normalizeNullable(request.sentence()));
        item.setLanguage(normalizeNullable(request.language(), "ja"));
        item.setCreatedAt(Instant.now());
        return vocabularyItemRepository.save(item);
    }

    public synchronized void deleteVocabulary(String userId, String itemId) {
        VocabularyItem item = vocabularyItemRepository.findById(itemId).orElse(null);
        if (item == null || !userId.equals(item.getUserId())) {
            throw new IllegalArgumentException("这个生词不存在");
        }
        vocabularyItemRepository.delete(item);
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

}
