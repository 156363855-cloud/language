package com.example.lingualink.controller;

import com.example.lingualink.dto.AddVocabularyRequest;
import com.example.lingualink.dto.ExplainWordRequest;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.model.VocabularyItem;
import com.example.lingualink.model.WordExplanation;
import com.example.lingualink.service.AuthService;
import com.example.lingualink.service.VocabularyService;
import com.example.lingualink.service.WordAssistantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class VocabularyController {

    private final AuthService authService;
    private final VocabularyService vocabularyService;
    private final WordAssistantService wordAssistantService;

    public VocabularyController(
            AuthService authService,
            VocabularyService vocabularyService,
            WordAssistantService wordAssistantService
    ) {
        this.authService = authService;
        this.vocabularyService = vocabularyService;
        this.wordAssistantService = wordAssistantService;
    }

    @GetMapping("/vocabulary")
    public List<VocabularyItem> listVocabulary(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        UserAccount user = authService.requireUser(authorizationHeader);
        return vocabularyService.listVocabulary(user.getId());
    }

    @PostMapping("/vocabulary")
    @ResponseStatus(HttpStatus.CREATED)
    public VocabularyItem addVocabulary(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @Valid @RequestBody AddVocabularyRequest request
    ) {
        UserAccount user = authService.requireUser(authorizationHeader);
        return vocabularyService.addVocabulary(user.getId(), request);
    }

    @DeleteMapping("/vocabulary/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVocabulary(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable String itemId
    ) {
        UserAccount user = authService.requireUser(authorizationHeader);
        vocabularyService.deleteVocabulary(user.getId(), itemId);
    }

    @PostMapping("/words/explain")
    public WordExplanation explainWord(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @Valid @RequestBody ExplainWordRequest request
    ) {
        authService.requireUser(authorizationHeader);
        return wordAssistantService.explain(request);
    }
}
