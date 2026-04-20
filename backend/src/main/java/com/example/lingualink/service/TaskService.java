package com.example.lingualink.service;

import com.example.lingualink.dto.CreateFolderRequest;
import com.example.lingualink.dto.CreateTaskRequest;
import com.example.lingualink.dto.ImportTaskRequest;
import com.example.lingualink.dto.UpdateFolderRequest;
import com.example.lingualink.model.SubtitleSegment;
import com.example.lingualink.model.TaskFolder;
import com.example.lingualink.model.TaskStatus;
import com.example.lingualink.model.TranscriptionTask;
import com.example.lingualink.repository.TaskFolderRepository;
import com.example.lingualink.repository.TranscriptionTaskRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Service
public class TaskService {
    public static final String DEFAULT_FOLDER_ID = "inbox";
    private static final String FOLDER_KIND_CATEGORY = "category";
    private static final String FOLDER_KIND_CHANNEL = "channel";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path applicationRoot = Path.of("").toAbsolutePath();
    private final Path repositoryRoot = "backend".equals(applicationRoot.getFileName().toString()) ? applicationRoot.getParent() : applicationRoot;
    private final Path backendRoot = repositoryRoot.resolve("backend");
    private final Path runtimeRoot = backendRoot.resolve("runtime");
    private final Path taskRoot = runtimeRoot.resolve("tasks");
    private final Path pythonExecutable = Files.exists(backendRoot.resolve(".venv/bin/python")) ? backendRoot.resolve(".venv/bin/python") : Path.of("python3");

    private final boolean processingEnabled;
    private final AssetPublishService assetPublishService;
    private final TaskFolderRepository taskFolderRepository;
    private final TranscriptionTaskRepository transcriptionTaskRepository;

    public TaskService(
            RuntimeJsonMigrationService runtimeJsonMigrationService,
            @Value("${app.processing.enabled:true}") boolean processingEnabled,
            AssetPublishService assetPublishService,
            TaskFolderRepository taskFolderRepository,
            TranscriptionTaskRepository transcriptionTaskRepository
    ) throws IOException {
        runtimeJsonMigrationService.migrateIfNeeded();
        this.processingEnabled = processingEnabled;
        this.assetPublishService = assetPublishService;
        this.taskFolderRepository = taskFolderRepository;
        this.transcriptionTaskRepository = transcriptionTaskRepository;
        Files.createDirectories(taskRoot);
        Files.createDirectories(runtimeRoot);
        initializeDefaultFolder();
        migrateFoldersIfNeeded();
        recoverMissingFolders();
        ensureTasksAssignedToChannels();
        resumePendingTasks();
    }

    public synchronized TranscriptionTask createTask(CreateTaskRequest request) {
        if (!processingEnabled) {
            throw new IllegalStateException("当前云端后端不负责解析音频，请在本地处理端完成解析后再同步素材和元数据。");
        }
        TranscriptionTask task = new TranscriptionTask();
        task.setId(UUID.randomUUID().toString());
        task.setMediaUrl(request.mediaUrl());
        task.setFolderId(resolveTaskFolderId(request.folderId()));
        task.setSourceLanguage(request.sourceLanguage());
        task.setTargetLanguages(normalizeLanguages(request.targetLanguages()));
        task.setStatus(TaskStatus.QUEUED);
        task.setProgress(0);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        task.setAudioAvailable(false);
        persistTask(task);
        syncTaskMetadataOnCreate(task);
        executor.submit(() -> processTask(task.getId()));
        return task;
    }

    public synchronized TranscriptionTask importTask(ImportTaskRequest request) {
        TranscriptionTask task = request.id() == null || request.id().isBlank()
                ? new TranscriptionTask()
                : transcriptionTaskRepository.findById(request.id()).orElse(new TranscriptionTask());
        if (task.getId() == null || task.getId().isBlank()) {
            task.setId(request.id() == null || request.id().isBlank() ? UUID.randomUUID().toString() : request.id());
        }
        task.setMediaUrl(request.mediaUrl());
        task.setFolderId(resolveTaskFolderId(request.folderId()));
        task.setSourceLanguage(request.sourceLanguage());
        task.setTargetLanguages(normalizeLanguages(request.targetLanguages()));
        task.setMediaTitle(request.mediaTitle());
        task.setSegments(request.segments() == null ? List.of() : request.segments());
        task.setAudioUrl(trimToNull(request.audioUrl()));
        task.setSubtitleUrl(trimToNull(request.subtitleUrl()));
        task.setCoverUrl(trimToNull(request.coverUrl()));
        task.setStatus(request.status() == null ? TaskStatus.COMPLETED : request.status());
        task.setProgress(request.progress() == null ? defaultProgressForStatus(task.getStatus()) : request.progress());
        task.setErrorMessage(request.errorMessage());
        if (request.audioAvailable() != null) {
            task.setAudioAvailable(request.audioAvailable());
        }
        Instant createdAt = request.createdAt() == null ? task.getCreatedAt() : request.createdAt();
        task.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        task.setUpdatedAt(Instant.now());
        persistTask(task);
        return task;
    }

    public synchronized List<TranscriptionTask> listTasks() {
        return transcriptionTaskRepository.findAll().stream()
                .sorted(Comparator.comparing(TranscriptionTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public synchronized List<TaskFolder> listFolders() {
        return taskFolderRepository.findAll().stream()
                .sorted(Comparator.comparing(TaskFolder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public synchronized TaskFolder createFolder(CreateFolderRequest request) {
        String kind = normalizeFolderKind(request.kind());
        String parentId = normalizeParentId(request.parentId(), kind);
        TaskFolder folder = new TaskFolder();
        folder.setId(UUID.randomUUID().toString());
        folder.setName(normalizeFolderName(request.name(), null, parentId));
        folder.setKind(kind);
        folder.setParentId(parentId);
        folder.setContentLanguage(resolveFolderContentLanguage(kind, parentId, request.contentLanguage()));
        folder.setCoverImageDataUrl(trimToNull(request.coverImageDataUrl()));
        folder.setCoverOpacity(normalizeCoverOpacity(request.coverOpacity()));
        folder.setCreatedAt(Instant.now());
        return taskFolderRepository.save(folder);
    }

    public synchronized TaskFolder updateFolder(String folderId, UpdateFolderRequest request) {
        TaskFolder folder = getFolder(resolveFolderId(folderId));
        String parentId = folder.getParentId();
        if (request.parentId() != null) {
            parentId = normalizeParentId(request.parentId(), normalizeFolderKind(folder.getKind()));
            folder.setParentId(parentId);
        }
        folder.setName(normalizeFolderName(request.name(), folder.getId(), parentId));
        if (request.contentLanguage() != null) {
            folder.setContentLanguage(resolveFolderContentLanguage(normalizeFolderKind(folder.getKind()), parentId, request.contentLanguage()));
        }
        if (request.coverImageDataUrl() != null) {
            folder.setCoverImageDataUrl(trimToNull(request.coverImageDataUrl()));
        }
        if (request.coverOpacity() != null) {
            folder.setCoverOpacity(normalizeCoverOpacity(request.coverOpacity()));
        }
        return taskFolderRepository.save(folder);
    }

    public synchronized TranscriptionTask moveTaskToFolder(String taskId, String folderId) {
        TranscriptionTask task = getTask(taskId);
        task.setFolderId(resolveTaskFolderId(folderId));
        task.setUpdatedAt(Instant.now());
        persistTask(task);
        return task;
    }

    public synchronized void deleteFolder(String folderId) {
        String resolvedFolderId = resolveFolderId(folderId);
        if (DEFAULT_FOLDER_ID.equals(resolvedFolderId)) {
            throw new IllegalArgumentException("默认文件夹不能删除");
        }
        List<String> childIds = listFolders().stream().filter(folder -> resolvedFolderId.equals(folder.getParentId())).map(TaskFolder::getId).toList();
        for (TranscriptionTask task : listTasks()) {
            if (resolvedFolderId.equals(task.getFolderId()) || childIds.contains(task.getFolderId())) {
                task.setFolderId(DEFAULT_FOLDER_ID);
                task.setUpdatedAt(Instant.now());
                persistTask(task);
            }
        }
        taskFolderRepository.deleteAllById(childIds);
        taskFolderRepository.deleteById(resolvedFolderId);
    }

    public synchronized TranscriptionTask getTask(String taskId) {
        return transcriptionTaskRepository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    public synchronized void deleteTask(String taskId) {
        getTask(taskId);
        transcriptionTaskRepository.deleteById(taskId);
        deleteTaskDirectoryIfExists(taskId);
    }

    public Path getTaskAudioPath(String taskId) {
        getTask(taskId);
        Path audioPath = taskRoot.resolve(taskId).resolve("source.mp3");
        if (!Files.exists(audioPath)) {
            throw new IllegalArgumentException("任务音频不存在: " + taskId);
        }
        return audioPath;
    }

    public synchronized Map<String, Object> republishBrokenTasks(String sourceLanguage) {
        ensureRepublishAllowed();
        List<String> republished = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String normalizedLanguage = sourceLanguage == null || sourceLanguage.isBlank() ? null : sourceLanguage.trim().toLowerCase();
        for (TranscriptionTask task : listTasks()) {
            if (normalizedLanguage != null && !normalizedLanguage.equalsIgnoreCase(task.getSourceLanguage())) {
                skipped.add(task.getId());
                continue;
            }
            if (!needsRepublish(task) || !isRepublishableTask(task, taskRoot.resolve(task.getId()))) {
                skipped.add(task.getId());
                continue;
            }
            publishAgain(task);
            republished.add(task.getId());
        }
        return Map.of("status", "ok", "republishedCount", republished.size(), "republishedTaskIds", republished, "skippedCount", skipped.size());
    }

    public synchronized TranscriptionTask republishTask(String taskId) {
        ensureRepublishAllowed();
        TranscriptionTask task = getTask(taskId);
        if (!isRepublishableTask(task, taskRoot.resolve(task.getId()))) {
            throw new IllegalArgumentException("当前任务还没有可重发的本地素材或字幕结果。");
        }
        publishAgain(task);
        return task;
    }

    private void publishAgain(TranscriptionTask task) {
        try {
            prepareTaskForRepublish(task);
            assetPublishService.publishTaskAssets(task, taskRoot.resolve(task.getId()));
            persistTask(task);
        } catch (Exception exception) {
            throw new IllegalStateException("重新发布任务失败: " + task.getId() + "，原因: " + exception.getMessage(), exception);
        }
    }

    private void ensureRepublishAllowed() {
        if (!processingEnabled) {
            throw new IllegalStateException("当前云端后端不负责重新发布旧素材，请在本地处理端执行此操作。");
        }
        if (!assetPublishService.isPublishingConfigured()) {
            throw new IllegalStateException("当前本地处理端还没有配置对象存储或云端回写地址。");
        }
    }

    private void processTask(String taskId) {
        TranscriptionTask task = getTask(taskId);
        Path taskDirectory = taskRoot.resolve(taskId);
        Path outputFile = taskDirectory.resolve("result.json");
        try {
            Files.createDirectories(taskDirectory);
            updateStatus(task, TaskStatus.PROCESSING, 10, null);
            ProcessBuilder builder = new ProcessBuilder(
                    pythonExecutable.toString(),
                    backendRoot.resolve("scripts/process_media.py").toString(),
                    "--media-url", task.getMediaUrl(),
                    "--source-language", task.getSourceLanguage(),
                    "--target-languages", String.join(",", task.getTargetLanguages()),
                    "--output-file", outputFile.toString()
            );
            builder.directory(repositoryRoot.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String logs;
            try (InputStream inputStream = process.getInputStream()) {
                logs = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.waitFor() != 0) {
                throw new IllegalStateException(cleanProcessMessage(logs));
            }
            TaskResult result = objectMapper.readValue(outputFile.toFile(), TaskResult.class);
            task.setMediaTitle(result.mediaTitle());
            task.setSegments(result.segments() == null ? List.of() : result.segments());
            task.setAudioAvailable(Files.exists(taskDirectory.resolve("source.mp3")));
            if (assetPublishService.isPublishingConfigured()) {
                assetPublishService.publishTaskAssets(task, taskDirectory);
                cleanupLocalProcessingArtifacts(taskDirectory);
                task.setAudioAvailable(task.getAudioUrl() != null && !task.getAudioUrl().isBlank());
            }
            updateStatus(task, TaskStatus.COMPLETED, 100, null);
        } catch (Exception exception) {
            task.setAudioAvailable(Files.exists(taskDirectory.resolve("source.mp3")));
            updateStatus(task, TaskStatus.FAILED, 100, exception.getMessage());
        }
    }

    private void migrateFoldersIfNeeded() {
        for (TaskFolder folder : listFolders()) {
            boolean changed = false;
            if (folder.getKind() == null || folder.getKind().isBlank()) {
                folder.setKind(folder.getParentId() == null || folder.getParentId().isBlank() ? FOLDER_KIND_CATEGORY : FOLDER_KIND_CHANNEL);
                changed = true;
            }
            if (folder.getContentLanguage() == null || folder.getContentLanguage().isBlank()) {
                folder.setContentLanguage(inferFolderLanguage(folder.getName()));
                changed = true;
            }
            if (folder.getCoverImageDataUrl() != null && folder.getCoverImageDataUrl().isBlank()) {
                folder.setCoverImageDataUrl(null);
                changed = true;
            }
            if (folder.getCoverOpacity() == null) {
                folder.setCoverOpacity(50);
                changed = true;
            }
            if (changed) {
                taskFolderRepository.save(folder);
            }
        }
    }

    private void recoverMissingFolders() {
        int recoveredIndex = 1;
        List<TaskFolder> folders = listFolders();
        for (TranscriptionTask task : listTasks()) {
            if (task.getFolderId() == null || task.getFolderId().isBlank()) {
                task.setFolderId(DEFAULT_FOLDER_ID);
                persistTask(task);
                continue;
            }
            boolean exists = folders.stream().anyMatch(folder -> task.getFolderId().equals(folder.getId()));
            if (exists) {
                continue;
            }
            TaskFolder recovered = new TaskFolder();
            recovered.setId(task.getFolderId());
            recovered.setName("恢复分组 " + recoveredIndex++);
            recovered.setKind(FOLDER_KIND_CHANNEL);
            recovered.setParentId(DEFAULT_FOLDER_ID);
            recovered.setContentLanguage("ja");
            recovered.setCreatedAt(task.getCreatedAt() == null ? Instant.now() : task.getCreatedAt());
            taskFolderRepository.save(recovered);
            folders = listFolders();
        }
    }

    private void ensureTasksAssignedToChannels() {
        for (TranscriptionTask task : listTasks()) {
            String resolvedFolderId = resolveTaskFolderId(task.getFolderId());
            if (!resolvedFolderId.equals(task.getFolderId())) {
                task.setFolderId(resolvedFolderId);
                task.setUpdatedAt(task.getUpdatedAt() == null ? Instant.now() : task.getUpdatedAt());
                persistTask(task);
            }
        }
    }

    private void resumePendingTasks() {
        if (!processingEnabled) {
            return;
        }
        transcriptionTaskRepository.findByStatusIn(List.of(TaskStatus.QUEUED, TaskStatus.PROCESSING)).stream()
                .sorted(Comparator.comparing(TranscriptionTask::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(task -> executor.submit(() -> processTask(task.getId())));
    }

    private void persistTask(TranscriptionTask task) {
        task.setAudioAvailable((task.getAudioUrl() != null && !task.getAudioUrl().isBlank()) || Files.exists(taskRoot.resolve(task.getId()).resolve("source.mp3")));
        transcriptionTaskRepository.save(task);
    }

    private void initializeDefaultFolder() {
        TaskFolder folder = taskFolderRepository.findById(DEFAULT_FOLDER_ID).orElse(null);
        if (folder == null) {
            folder = new TaskFolder();
            folder.setId(DEFAULT_FOLDER_ID);
            folder.setName("未分类");
            folder.setKind(FOLDER_KIND_CATEGORY);
            folder.setContentLanguage("ja");
            folder.setCreatedAt(Instant.EPOCH);
        } else {
            if (folder.getKind() == null || folder.getKind().isBlank()) {
                folder.setKind(FOLDER_KIND_CATEGORY);
            }
            if (folder.getContentLanguage() == null || folder.getContentLanguage().isBlank()) {
                folder.setContentLanguage("ja");
            }
        }
        taskFolderRepository.save(folder);
    }

    private TaskFolder getFolder(String folderId) {
        return taskFolderRepository.findById(folderId).orElseThrow(() -> new IllegalArgumentException("文件夹不存在: " + folderId));
    }

    private String resolveFolderId(String folderId) {
        String resolved = folderId == null || folderId.isBlank() ? DEFAULT_FOLDER_ID : folderId;
        if (!taskFolderRepository.existsById(resolved)) {
            throw new IllegalArgumentException("文件夹不存在: " + resolved);
        }
        return resolved;
    }

    private String resolveTaskFolderId(String folderId) {
        String resolved = folderId == null || folderId.isBlank() ? DEFAULT_FOLDER_ID : folderId.trim();
        TaskFolder folder = taskFolderRepository.findById(resolved).orElse(null);
        if (folder == null) {
            if (processingEnabled) {
                return resolved;
            }
            throw new IllegalArgumentException("文件夹不存在: " + resolved);
        }
        return FOLDER_KIND_CATEGORY.equals(normalizeFolderKind(folder.getKind())) ? ensureChannelUnderCategory(resolved) : resolved;
    }

    private String ensureChannelUnderCategory(String categoryId) {
        TaskFolder channel = listFolders().stream()
                .filter(folder -> FOLDER_KIND_CHANNEL.equals(normalizeFolderKind(folder.getKind())))
                .filter(folder -> categoryId.equals(folder.getParentId()))
                .sorted(Comparator.comparing(TaskFolder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElse(null);
        if (channel != null) {
            return channel.getId();
        }
        TaskFolder category = getFolder(categoryId);
        TaskFolder created = new TaskFolder();
        created.setId(UUID.randomUUID().toString());
        created.setName(normalizeFolderName("未命名广播", null, categoryId));
        created.setKind(FOLDER_KIND_CHANNEL);
        created.setParentId(categoryId);
        created.setContentLanguage(resolveFolderContentLanguage(FOLDER_KIND_CHANNEL, categoryId, category.getContentLanguage()));
        created.setCoverOpacity(50);
        created.setCreatedAt(Instant.now());
        return taskFolderRepository.save(created).getId();
    }

    private String normalizeFolderName(String name, String currentFolderId, String parentId) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        boolean exists = listFolders().stream().anyMatch(folder -> !folder.getId().equals(currentFolderId) && normalizeNullable(folder.getParentId()).equals(normalizeNullable(parentId)) && folder.getName().equalsIgnoreCase(normalized));
        if (exists) {
            throw new IllegalArgumentException("文件夹已存在: " + normalized);
        }
        return normalized;
    }

    private String normalizeFolderKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return FOLDER_KIND_CATEGORY;
        }
        String normalized = kind.trim().toLowerCase();
        if (!List.of(FOLDER_KIND_CATEGORY, FOLDER_KIND_CHANNEL).contains(normalized)) {
            throw new IllegalArgumentException("分组类型只支持 category 或 channel");
        }
        return normalized;
    }

    private String normalizeParentId(String parentId, String kind) {
        if (FOLDER_KIND_CATEGORY.equals(kind)) {
            return null;
        }
        if (parentId == null || parentId.isBlank()) {
            throw new IllegalArgumentException("广播必须归属到一个大类下面");
        }
        TaskFolder parent = getFolder(parentId);
        if (!FOLDER_KIND_CATEGORY.equals(normalizeFolderKind(parent.getKind()))) {
            throw new IllegalArgumentException("广播只能挂在大类下面");
        }
        return parentId;
    }

    private String resolveFolderContentLanguage(String kind, String parentId, String contentLanguage) {
        if (FOLDER_KIND_CHANNEL.equals(kind)) {
            TaskFolder parent = taskFolderRepository.findById(parentId).orElse(null);
            return parent == null ? normalizeContentLanguage(contentLanguage) : normalizeContentLanguage(parent.getContentLanguage());
        }
        return normalizeContentLanguage(contentLanguage);
    }

    private String normalizeContentLanguage(String contentLanguage) {
        String normalized = contentLanguage == null || contentLanguage.isBlank() ? "en" : contentLanguage.trim().toLowerCase();
        if (!List.of("zh", "en", "ja").contains(normalized)) {
            throw new IllegalArgumentException("内容语言只支持 zh、en 或 ja");
        }
        return normalized;
    }

    private List<String> normalizeLanguages(String languageCsv) {
        return Arrays.stream(languageCsv.split(",")).map(String::trim).filter(language -> !language.isEmpty()).distinct().sorted().toList();
    }

    private String inferFolderLanguage(String name) {
        return name != null && name.toLowerCase().contains("日") ? "ja" : "en";
    }

    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Integer normalizeCoverOpacity(Integer coverOpacity) {
        if (coverOpacity == null) {
            return 50;
        }
        if (coverOpacity < 0 || coverOpacity > 100) {
            throw new IllegalArgumentException("封面透明度只支持 0 到 100");
        }
        return coverOpacity;
    }

    private boolean needsRepublish(TranscriptionTask task) {
        return isLegacyPrivateAudioUrl(task.getAudioUrl()) || isLegacyPrivateAudioUrl(task.getSubtitleUrl());
    }

    private boolean isRepublishableTask(TranscriptionTask task, Path taskDirectory) {
        return Files.exists(taskDirectory.resolve("source.mp3")) && task.getMediaTitle() != null && !task.getMediaTitle().isBlank() && task.getSegments() != null && !task.getSegments().isEmpty();
    }

    private void prepareTaskForRepublish(TranscriptionTask task) {
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setErrorMessage(null);
        task.setUpdatedAt(Instant.now());
    }

    private boolean isLegacyPrivateAudioUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.contains("http://minio:9000/") || normalized.contains("https://minio:9000/") || normalized.contains("http://localhost:9000/") || normalized.contains("https://localhost:9000/");
    }

    private void updateStatus(TranscriptionTask task, TaskStatus status, int progress, String errorMessage) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(Instant.now());
        persistTask(task);
        if (assetPublishService.isCloudSyncConfigured()) {
            try {
                assetPublishService.syncTaskMetadata(task);
            } catch (Exception ignored) {
            }
        }
    }

    private int defaultProgressForStatus(TaskStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case QUEUED -> 0;
            case PROCESSING -> 10;
            case COMPLETED, FAILED -> 100;
        };
    }

    private void syncTaskMetadataOnCreate(TranscriptionTask task) {
        if (!assetPublishService.isCloudSyncConfigured()) {
            return;
        }
        try {
            assetPublishService.syncTaskMetadata(task);
        } catch (Exception exception) {
            transcriptionTaskRepository.deleteById(task.getId());
            deleteTaskDirectoryIfExists(task.getId());
            throw new IllegalStateException("同步任务到 API 端失败，请重试。", exception);
        }
    }

    private void deleteTaskDirectoryIfExists(String taskId) {
        Path taskDirectory = taskRoot.resolve(taskId);
        if (!Files.exists(taskDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(taskDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("删除任务目录失败", exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("删除任务目录失败", exception);
        }
    }

    private void cleanupLocalProcessingArtifacts(Path taskDirectory) {
        deleteIfExists(taskDirectory.resolve("source.mp3"));
        deleteIfExists(taskDirectory.resolve("normalized.wav"));
        deleteIfExists(taskDirectory.resolve("result.json"));
        deleteIfExists(taskDirectory.resolve("subtitles.json"));
        deleteRecursively(taskDirectory.resolve("local_chunks"));
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("清理本地处理中间文件失败: " + path.getFileName(), exception);
        }
    }

    private void deleteRecursively(Path directoryPath) {
        if (!Files.exists(directoryPath)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directoryPath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("清理本地处理中间目录失败: " + directoryPath.getFileName(), exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("清理本地处理中间目录失败: " + directoryPath.getFileName(), exception);
        }
    }

    private String cleanProcessMessage(String logs) {
        if (logs == null || logs.isBlank()) {
            return "媒体处理失败";
        }
        String[] lines = logs.replace("\r", "\n").split("\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.isEmpty() && !line.startsWith("[download]")) {
                return line;
            }
        }
        return "媒体处理失败";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskResult(String mediaTitle, List<SubtitleSegment> segments) {
    }
}
