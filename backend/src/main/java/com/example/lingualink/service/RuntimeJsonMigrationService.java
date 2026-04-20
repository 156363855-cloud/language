package com.example.lingualink.service;

import com.example.lingualink.model.SubtitleSegment;
import com.example.lingualink.model.TaskFolder;
import com.example.lingualink.model.TaskStatus;
import com.example.lingualink.model.TranscriptionTask;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.model.UserSession;
import com.example.lingualink.model.VocabularyItem;
import com.example.lingualink.repository.TaskFolderRepository;
import com.example.lingualink.repository.TranscriptionTaskRepository;
import com.example.lingualink.repository.UserAccountRepository;
import com.example.lingualink.repository.UserSessionRepository;
import com.example.lingualink.repository.VocabularyItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
public class RuntimeJsonMigrationService {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path applicationRoot = Path.of("").toAbsolutePath();
    private final Path repositoryRoot = resolveRepositoryRoot(applicationRoot);
    private final Path runtimeRoot = repositoryRoot.resolve("backend").resolve("runtime");
    private final Path taskRoot = runtimeRoot.resolve("tasks");
    private final Path vocabularyRoot = runtimeRoot.resolve("vocabulary");
    private final Path usersStore = runtimeRoot.resolve("users.json");
    private final Path sessionsStore = runtimeRoot.resolve("sessions.json");
    private final Path folderStore = runtimeRoot.resolve("folders.json");

    private final UserAccountRepository userAccountRepository;
    private final UserSessionRepository userSessionRepository;
    private final TaskFolderRepository taskFolderRepository;
    private final TranscriptionTaskRepository transcriptionTaskRepository;
    private final VocabularyItemRepository vocabularyItemRepository;

    public RuntimeJsonMigrationService(
            UserAccountRepository userAccountRepository,
            UserSessionRepository userSessionRepository,
            TaskFolderRepository taskFolderRepository,
            TranscriptionTaskRepository transcriptionTaskRepository,
            VocabularyItemRepository vocabularyItemRepository
    ) {
        this.userAccountRepository = userAccountRepository;
        this.userSessionRepository = userSessionRepository;
        this.taskFolderRepository = taskFolderRepository;
        this.transcriptionTaskRepository = transcriptionTaskRepository;
        this.vocabularyItemRepository = vocabularyItemRepository;
    }

    public synchronized void migrateIfNeeded() throws IOException {
        importUsersIfNeeded();
        importSessionsIfNeeded();
        importFoldersIfNeeded();
        importTasksIfNeeded();
        importVocabularyIfNeeded();
    }

    private void importUsersIfNeeded() throws IOException {
        if (userAccountRepository.count() > 0 || !Files.exists(usersStore)) {
            return;
        }
        List<UserAccount> users = objectMapper.readValue(usersStore.toFile(), new TypeReference<>() {
        });
        userAccountRepository.saveAll(users);
    }

    private void importSessionsIfNeeded() throws IOException {
        if (userSessionRepository.count() > 0 || !Files.exists(sessionsStore)) {
            return;
        }
        List<UserSession> sessions = objectMapper.readValue(sessionsStore.toFile(), new TypeReference<>() {
        });
        userSessionRepository.saveAll(sessions);
    }

    private void importFoldersIfNeeded() throws IOException {
        if (taskFolderRepository.count() > 0 || !Files.exists(folderStore)) {
            return;
        }
        List<TaskFolder> folders = objectMapper.readValue(folderStore.toFile(), new TypeReference<>() {
        });
        taskFolderRepository.saveAll(folders);
    }

    private void importTasksIfNeeded() throws IOException {
        if (transcriptionTaskRepository.count() > 0 || !Files.exists(taskRoot)) {
            return;
        }
        try (var taskDirectories = Files.list(taskRoot)) {
            List<TranscriptionTask> tasks = taskDirectories
                    .filter(Files::isDirectory)
                    .map(this::loadTaskSafely)
                    .filter(task -> task != null)
                    .toList();
            transcriptionTaskRepository.saveAll(tasks);
        }
    }

    private void importVocabularyIfNeeded() throws IOException {
        if (vocabularyItemRepository.count() > 0 || !Files.exists(vocabularyRoot)) {
            return;
        }
        try (var files = Files.list(vocabularyRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String userId = file.getFileName().toString().replaceFirst("\\.json$", "");
                List<VocabularyItem> items = objectMapper.readValue(file.toFile(), new TypeReference<>() {
                });
                items.forEach(item -> item.setUserId(userId));
                vocabularyItemRepository.saveAll(items);
            }
        }
    }

    private TranscriptionTask loadTaskSafely(Path taskDirectory) {
        Path taskFile = taskDirectory.resolve("task.json");
        try {
            return Files.exists(taskFile)
                    ? objectMapper.readValue(taskFile.toFile(), TranscriptionTask.class)
                    : loadLegacyTask(taskDirectory);
        } catch (IOException exception) {
            return null;
        }
    }

    private TranscriptionTask loadLegacyTask(Path taskDirectory) throws IOException {
        Path resultFile = taskDirectory.resolve("result.json");
        if (!Files.exists(resultFile)) {
            return null;
        }

        TaskResult result = objectMapper.readValue(resultFile.toFile(), TaskResult.class);
        Instant timestamp = Files.getLastModifiedTime(resultFile).toInstant();

        TranscriptionTask task = new TranscriptionTask();
        task.setId(taskDirectory.getFileName().toString());
        task.setMediaUrl("历史任务");
        task.setFolderId(TaskService.DEFAULT_FOLDER_ID);
        task.setSourceLanguage("unknown");
        task.setTargetLanguages(List.of("zh", "ja", "en"));
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setCreatedAt(timestamp);
        task.setUpdatedAt(timestamp);
        task.setMediaTitle(result.mediaTitle());
        task.setSegments(result.segments() == null ? List.of() : result.segments());
        task.setAudioAvailable(Files.exists(taskDirectory.resolve("source.mp3")));
        return task;
    }

    private Path resolveRepositoryRoot(Path currentDirectory) {
        if ("backend".equals(currentDirectory.getFileName().toString())) {
            return currentDirectory.getParent();
        }
        return currentDirectory;
    }

    private record TaskResult(String mediaTitle, List<SubtitleSegment> segments) {
    }
}
