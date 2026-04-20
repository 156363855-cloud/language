package com.example.lingualink.repository;

import com.example.lingualink.model.VocabularyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VocabularyItemRepository extends JpaRepository<VocabularyItem, String> {
    List<VocabularyItem> findByUserId(String userId);
}
