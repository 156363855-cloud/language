package com.example.lingualink.service;

import com.example.lingualink.dto.CreateFolderRequest;
import com.example.lingualink.dto.CreateTaskRequest;
import com.example.lingualink.dto.ImportTaskRequest;
import com.example.lingualink.dto.UpdateFolderRequest;
import com.example.lingualink.model.SubtitleSegment;
import com.example.lingualink.model.TaskFolder;
import com.example.lingualink.model.TaskStatus;
import com.example.lingualink.model.TranscriptionTask;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TaskService {

    public static final String DEFAULT_FOLDER_ID = "inbox";
    private static final String FOLDER_KIND_CATEGORY = "category";
    private static final String FOLDER_KIND_CHANNEL = "channel";

    private final Map<String, TranscriptionTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, TaskFolder> folders = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path applicationRoot = Path.of("").toAbsolutePath();
    private final Path repositoryRoot = resolveRepositoryRoot(applicationRoot);
    private final Path backendRoot = repositoryRoot.resolve("backend");
    private final Path runtimeRoot = backendRoot.resolve("runtime");
    private final Path taskRoot = runtimeRoot.resolve("tasks");
    private final Path folderStore = runtimeRoot.resolve("folders.json");
    private final Path pythonExecutable = resolvePythonExecutable();
    private final boolean processingEnabled;
    private final AssetPublishService assetPublishService;

    public TaskService(
            @Value("${app.processing.enabled:true}") boolean processingEnabled,
            AssetPublishService assetPublishService
    ) throws IOException {
        this.processingEnabled = processingEnabled;
        this.assetPublishService = assetPublishService;
        Files.createDirectories(taskRoot);
        Files.createDirectories(runtimeRoot);
        loadFolders();
        initializeDefaultFolder();
        loadTasks();
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

        tasks.put(task.getId(), task);
        persistTask(task);
        syncTaskMetadataOnCreate(task);
        executor.submit(() -> processTask(task.getId()));
        return task;
    }

    public synchronized TranscriptionTask importTask(ImportTaskRequest request) {
        TranscriptionTask task = request.id() == null || request.id().isBlank()
                ? new TranscriptionTask()
                : tasks.getOrDefault(request.id(), new TranscriptionTask());

        if (task.getId() == null || task.getId().isBlank()) {
            task.setId(request.id() == null || request.id().isBlank() ? UUID.randomUUID().toString() : request.id());
        }

        task.setMediaUrl(request.mediaUrl());
        task.setFolderId(resolveTaskFolderId(request.folderId()));
        task.setSourceLanguage(request.sourceLanguage());
        task.setTargetLanguages(normalizeLanguages(request.targetLanguages()));
        task.setMediaTitle(request.mediaTitle());
        task.setSegments(request.segments() == null ? List.of() : request.segments());
        task.setAudioUrl(normalizeExternalUrl(request.audioUrl()));
        task.setSubtitleUrl(normalizeExternalUrl(request.subtitleUrl()));
        task.setCoverUrl(normalizeExternalUrl(request.coverUrl()));
        task.setStatus(request.status() == null ? TaskStatus.COMPLETED : request.status());
        task.setProgress(request.progress() == null ? defaultProgressForStatus(task.getStatus()) : request.progress());
        task.setErrorMessage(request.errorMessage());
        if (request.audioAvailable() != null) {
            task.setAudioAvailable(request.audioAvailable());
        }

        Instant createdAt = request.createdAt() == null ? task.getCreatedAt() : request.createdAt();
        task.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        task.setUpdatedAt(Instant.now());

        tasks.put(task.getId(), task);
        persistTask(task);
        return task;
    }

    public synchronized List<TranscriptionTask> listTasks() {
        return new ArrayList<>(tasks.values()).stream()
                .sorted(Comparator.comparing(TranscriptionTask::getCreatedAt).reversed())
                .toList();
    }

    public synchronized List<TaskFolder> listFolders() {
        return new ArrayList<>(folders.values()).stream()
                .sorted(Comparator.comparing(TaskFolder::getCreatedAt))
                .toList();
    }

    public synchronized TaskFolder createFolder(CreateFolderRequest request) {
        String normalizedKind = normalizeFolderKind(request.kind());
        String normalizedParentId = normalizeParentId(request.parentId(), normalizedKind);
        String normalizedName = normalizeFolderName(request.name(), null, normalizedParentId);

        TaskFolder folder = new TaskFolder();
        folder.setId(UUID.randomUUID().toString());
        folder.setName(normalizedName);
        folder.setKind(normalizedKind);
        folder.setParentId(normalizedParentId);
        folder.setContentLanguage(resolveFolderContentLanguage(normalizedKind, normalizedParentId, request.contentLanguage()));
        folder.setCoverImageDataUrl(normalizeCoverImageDataUrl(request.coverImageDataUrl()));
        folder.setCoverOpacity(normalizeCoverOpacity(request.coverOpacity()));
        folder.setCreatedAt(Instant.now());
        folders.put(folder.getId(), folder);
        persistFolders();
        return folder;
    }

    public synchronized TaskFolder updateFolder(String folderId, UpdateFolderRequest request) {
        TaskFolder folder = folders.get(resolveFolderId(folderId));
        if (folder == null) {
            throw new IllegalArgumentException("文件夹不存在: " + folderId);
        }

        String normalizedParentId = folder.getParentId();
        if (request.parentId() != null) {
            normalizedParentId = normalizeParentId(request.parentId(), normalizeFolderKind(folder.getKind()));
            folder.setParentId(normalizedParentId);
        }

        String normalizedName = normalizeFolderName(request.name(), folder.getId(), normalizedParentId);
        folder.setName(normalizedName);
        if (request.contentLanguage() != null) {
            folder.setContentLanguage(resolveFolderContentLanguage(
                    normalizeFolderKind(folder.getKind()),
                    normalizedParentId,
                    request.contentLanguage()
            ));
        }
        if (request.coverImageDataUrl() != null) {
            folder.setCoverImageDataUrl(normalizeCoverImageDataUrl(request.coverImageDataUrl()));
        }
        if (request.coverOpacity() != null) {
            folder.setCoverOpacity(normalizeCoverOpacity(request.coverOpacity()));
        }
        persistFolders();
        return folder;
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

        List<String> childFolderIds = folders.values().stream()
                .filter(folder -> resolvedFolderId.equals(folder.getParentId()))
                .map(TaskFolder::getId)
                .toList();

        for (TranscriptionTask task : tasks.values()) {
            if (resolvedFolderId.equals(task.getFolderId()) || childFolderIds.contains(task.getFolderId())) {
                task.setFolderId(DEFAULT_FOLDER_ID);
                task.setUpdatedAt(Instant.now());
                persistTask(task);
            }
        }

        for (String childFolderId : childFolderIds) {
            folders.remove(childFolderId);
        }
        folders.remove(resolvedFolderId);
        persistFolders();
    }

    public synchronized TranscriptionTask getTask(String taskId) {
        TranscriptionTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    public synchronized void deleteTask(String taskId) {
        getTask(taskId);
        tasks.remove(taskId);

        Path taskDirectory = taskRoot.resolve(taskId);
        if (Files.exists(taskDirectory)) {
            try (var paths = Files.walk(taskDirectory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException exception) {
                                throw new IllegalStateException("删除任务文件失败", exception);
                            }
                        });
            } catch (IOException exception) {
                throw new IllegalStateException("删除任务目录失败", exception);
            }
        }
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
        if (!processingEnabled) {
            throw new IllegalStateException("当前云端后端不负责重新发布旧素材，请在本地处理端执行此操作。");
        }
        if (!assetPublishService.isPublishingConfigured()) {
            throw new IllegalStateException("当前本地处理端还没有配置对象存储或云端回写地址。");
        }

        List<String> republishedTaskIds = new ArrayList<>();
        List<String> skippedTaskIds = new ArrayList<>();
        String normalizedLanguage = sourceLanguage == null || sourceLanguage.isBlank()
                ? null
                : sourceLanguage.trim().toLowerCase();

        for (TranscriptionTask task : listTasks()) {
            if (task.getStatus() != TaskStatus.COMPLETED) {
                skippedTaskIds.add(task.getId());
                continue;
            }
            if (normalizedLanguage != null && !normalizedLanguage.equalsIgnoreCase(task.getSourceLanguage())) {
                skippedTaskIds.add(task.getId());
                continue;
            }
            if (!needsRepublish(task)) {
                skippedTaskIds.add(task.getId());
                continue;
            }

            Path taskDirectory = taskRoot.resolve(task.getId());
            Path audioPath = taskDirectory.resolve("source.mp3");
            if (!Files.exists(audioPath)) {
                skippedTaskIds.add(task.getId());
                continue;
            }

            try {
                assetPublishService.publishTaskAssets(task, taskDirectory);
                persistTask(task);
                republishedTaskIds.add(task.getId());
            } catch (Exception exception) {
                throw new IllegalStateException("重新发布任务失败: " + task.getId() + "，原因: " + exception.getMessage(), exception);
            }
        }

        return Map.of(
                "status", "ok",
                "republishedCount", republishedTaskIds.size(),
                "republishedTaskIds", republishedTaskIds,
                "skippedCount", skippedTaskIds.size()
        );
    }

    public synchronized TranscriptionTask republishTask(String taskId) {
        if (!processingEnabled) {
            throw new IllegalStateException("当前云端后端不负责重新发布旧素材，请在本地处理端执行此操作。");
        }
        if (!assetPublishService.isPublishingConfigured()) {
            throw new IllegalStateException("当前本地处理端还没有配置对象存储或云端回写地址。");
        }

        TranscriptionTask task = getTask(taskId);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw new IllegalArgumentException("只有已完成的任务才能重新发布素材。");
        }

        Path taskDirectory = taskRoot.resolve(task.getId());
        Path audioPath = taskDirectory.resolve("source.mp3");
        if (!Files.exists(audioPath)) {
            throw new IllegalArgumentException("任务音频不存在: " + task.getId());
        }

        try {
            assetPublishService.publishTaskAssets(task, taskDirectory);
            persistTask(task);
            return task;
        } catch (Exception exception) {
            throw new IllegalStateException("重新发布任务失败: " + task.getId() + "，原因: " + exception.getMessage(), exception);
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
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(cleanProcessMessage(logs));
            }

            TaskResult result = objectMapper.readValue(outputFile.toFile(), TaskResult.class);
            task.setMediaTitle(result.mediaTitle());
            task.setSegments(result.segments() == null ? List.of() : result.segments());
            task.setAudioAvailable(Files.exists(taskDirectory.resolve("source.mp3")));
            if (assetPublishService.isPublishingConfigured()) {
                assetPublishService.publishTaskAssets(task, taskDirectory);
            }
            updateStatus(task, TaskStatus.COMPLETED, 100, null);
        } catch (Exception exception) {
            task.setAudioAvailable(Files.exists(taskDirectory.resolve("source.mp3")));
            updateStatus(task, TaskStatus.FAILED, 100, exception.getMessage());
        }
    }

    private synchronized void loadTasks() throws IOException {
        if (!Files.exists(taskRoot)) {
            return;
        }

        try (var taskDirectories = Files.list(taskRoot)) {
            taskDirectories
                    .filter(Files::isDirectory)
                    .forEach(taskDirectory -> {
                        Path taskFile = taskDirectory.resolve("task.json");
                        try {
                            TranscriptionTask task = Files.exists(taskFile)
                                    ? objectMapper.readValue(taskFile.toFile(), TranscriptionTask.class)
                                    : loadLegacyTask(taskDirectory);
                            if (task == null) {
                                return;
                            }
                            if (task.getFolderId() == null || task.getFolderId().isBlank()) {
                                task.setFolderId(DEFAULT_FOLDER_ID);
                            }
                            task.setFolderId(resolveTaskFolderId(task.getFolderId()));
                            updateAudioAvailability(task);
                            tasks.put(task.getId(), task);
                            persistTask(task);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private synchronized void recoverMissingFolders() {
        boolean changed = false;
        int recoveredIndex = 1;

        for (TranscriptionTask task : tasks.values()) {
            String folderId = task.getFolderId();
            if (folderId == null || folderId.isBlank()) {
                task.setFolderId(DEFAULT_FOLDER_ID);
                persistTask(task);
                changed = true;
                continue;
            }

            if (folders.containsKey(folderId)) {
                continue;
            }

            TaskFolder recoveredFolder = new TaskFolder();
            recoveredFolder.setId(folderId);
            recoveredFolder.setName("恢复分组 " + recoveredIndex++);
            recoveredFolder.setKind(FOLDER_KIND_CHANNEL);
            recoveredFolder.setParentId(DEFAULT_FOLDER_ID);
            recoveredFolder.setContentLanguage("ja");
            recoveredFolder.setCreatedAt(task.getCreatedAt() == null ? Instant.now() : task.getCreatedAt());
            folders.put(folderId, recoveredFolder);
            changed = true;
        }

        if (changed) {
            persistFolders();
        }
    }

    private synchronized void ensureTasksAssignedToChannels() {
        boolean changed = false;
        for (TranscriptionTask task : tasks.values()) {
            String resolvedFolderId = resolveTaskFolderId(task.getFolderId());
            if (!resolvedFolderId.equals(task.getFolderId())) {
                task.setFolderId(resolvedFolderId);
                if (task.getUpdatedAt() == null) {
                    task.setUpdatedAt(Instant.now());
                }
                persistTask(task);
                changed = true;
            }
        }
        if (changed) {
            persistFolders();
        }
    }

    private void resumePendingTasks() {
        if (!processingEnabled) {
            return;
        }
        tasks.values().stream()
                .filter(task -> task.getStatus() == TaskStatus.QUEUED || task.getStatus() == TaskStatus.PROCESSING)
                .sorted(Comparator
                        .comparing(TranscriptionTask::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TranscriptionTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(task -> executor.submit(() -> processTask(task.getId())));
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
        task.setFolderId(DEFAULT_FOLDER_ID);
        task.setSourceLanguage("unknown");
        task.setTargetLanguages(List.of("zh", "ja", "en"));
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setCreatedAt(timestamp);
        task.setUpdatedAt(timestamp);
        task.setMediaTitle(result.mediaTitle());
        task.setSegments(result.segments() == null ? List.of() : result.segments());
        updateAudioAvailability(task);
        return task;
    }

    private synchronized void loadFolders() throws IOException {
        if (!Files.exists(folderStore)) {
            persistFolders();
            return;
        }

        List<TaskFolder> storedFolders = objectMapper.readValue(folderStore.toFile(), new TypeReference<>() {
        });
        for (TaskFolder folder : storedFolders) {
            migrateFolder(folder);
            folders.put(folder.getId(), folder);
        }
        initializeDefaultFolder();
    }

    private synchronized void persistTask(TranscriptionTask task) {
        try {
            Path taskDirectory = taskRoot.resolve(task.getId());
            Files.createDirectories(taskDirectory);
            updateAudioAvailability(task);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskDirectory.resolve("task.json").toFile(), task);
        } catch (IOException exception) {
            throw new IllegalStateException("保存任务失败", exception);
        }
    }

    private void updateAudioAvailability(TranscriptionTask task) {
        task.setAudioAvailable(
                (task.getAudioUrl() != null && !task.getAudioUrl().isBlank())
                        || Files.exists(taskRoot.resolve(task.getId()).resolve("source.mp3"))
        );
    }

    private synchronized void persistFolders() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(folderStore.toFile(), listFolders());
        } catch (IOException exception) {
            throw new IllegalStateException("保存文件夹失败", exception);
        }
    }

    private synchronized void initializeDefaultFolder() {
        if (folders.containsKey(DEFAULT_FOLDER_ID)) {
            TaskFolder folder = folders.get(DEFAULT_FOLDER_ID);
            if (folder.getKind() == null || folder.getKind().isBlank()) {
                folder.setKind(FOLDER_KIND_CATEGORY);
            }
            if (folder.getContentLanguage() == null || folder.getContentLanguage().isBlank()) {
                folder.setContentLanguage("ja");
            }
            return;
        }
        TaskFolder folder = new TaskFolder();
        folder.setId(DEFAULT_FOLDER_ID);
        folder.setName("未分类");
        folder.setKind(FOLDER_KIND_CATEGORY);
        folder.setContentLanguage("ja");
        folder.setCreatedAt(Instant.EPOCH);
        folders.put(folder.getId(), folder);
        persistFolders();
    }

    private void migrateFolder(TaskFolder folder) {
        if (folder.getKind() == null || folder.getKind().isBlank()) {
            boolean looksLikeLegacyTopLevel = folder.getParentId() == null || folder.getParentId().isBlank();
            folder.setKind(looksLikeLegacyTopLevel ? FOLDER_KIND_CATEGORY : FOLDER_KIND_CHANNEL);
        }

        if (folder.getContentLanguage() == null || folder.getContentLanguage().isBlank()) {
            folder.setContentLanguage(inferFolderLanguage(folder.getName()));
        }
        if (folder.getCoverImageDataUrl() != null && folder.getCoverImageDataUrl().isBlank()) {
            folder.setCoverImageDataUrl(null);
        }
        if (folder.getCoverOpacity() == null) {
            folder.setCoverOpacity(50);
        }
    }

    private String inferFolderLanguage(String name) {
        String normalized = name == null ? "" : name.toLowerCase();
        if (normalized.contains("日")) {
            return "ja";
        }
        return "en";
    }

    private List<String> normalizeLanguages(String languageCsv) {
        return Arrays.stream(languageCsv.split(","))
                .map(String::trim)
                .filter(language -> !language.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private String resolveFolderId(String folderId) {
        String resolved = folderId == null || folderId.isBlank() ? DEFAULT_FOLDER_ID : folderId;
        if (!folders.containsKey(resolved)) {
            throw new IllegalArgumentException("文件夹不存在: " + resolved);
        }
        return resolved;
    }

    private String resolveTaskFolderId(String folderId) {
        String resolvedFolderId = folderId == null || folderId.isBlank() ? DEFAULT_FOLDER_ID : folderId.trim();
        TaskFolder folder = folders.get(resolvedFolderId);
        if (folder == null) {
            if (processingEnabled) {
                return resolvedFolderId;
            }
            throw new IllegalArgumentException("文件夹不存在: " + resolvedFolderId);
        }
        if (!FOLDER_KIND_CATEGORY.equals(normalizeFolderKind(folder.getKind()))) {
            return resolvedFolderId;
        }
        return ensureChannelUnderCategory(resolvedFolderId);
    }

    private String ensureChannelUnderCategory(String categoryId) {
        TaskFolder existingChannel = folders.values().stream()
                .filter(folder -> FOLDER_KIND_CHANNEL.equals(normalizeFolderKind(folder.getKind())))
                .filter(folder -> categoryId.equals(folder.getParentId()))
                .sorted(Comparator.comparing(TaskFolder::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElse(null);
        if (existingChannel != null) {
            return existingChannel.getId();
        }

        TaskFolder categoryFolder = folders.get(categoryId);
        if (categoryFolder == null) {
            throw new IllegalArgumentException("上级大类不存在: " + categoryId);
        }

        TaskFolder channel = new TaskFolder();
        channel.setId(UUID.randomUUID().toString());
        channel.setName(normalizeFolderName("未命名广播", null, categoryId));
        channel.setKind(FOLDER_KIND_CHANNEL);
        channel.setParentId(categoryId);
        channel.setContentLanguage(resolveFolderContentLanguage(
                FOLDER_KIND_CHANNEL,
                categoryId,
                categoryFolder.getContentLanguage()
        ));
        channel.setCoverImageDataUrl(null);
        channel.setCoverOpacity(50);
        channel.setCreatedAt(Instant.now());
        folders.put(channel.getId(), channel);
        persistFolders();
        return channel.getId();
    }

    private String normalizeFolderName(String folderName, String currentFolderId, String parentId) {
        String normalizedName = folderName == null ? "" : folderName.trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }

        boolean exists = folders.values().stream()
                .anyMatch(folder ->
                        !folder.getId().equals(currentFolderId)
                                && normalizeNullable(folder.getParentId()).equals(normalizeNullable(parentId))
                                && folder.getName().equalsIgnoreCase(normalizedName)
                );
        if (exists) {
            throw new IllegalArgumentException("文件夹已存在: " + normalizedName);
        }
        return normalizedName;
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

        TaskFolder parentFolder = folders.get(parentId);
        if (parentFolder == null) {
            throw new IllegalArgumentException("上级大类不存在");
        }
        if (!FOLDER_KIND_CATEGORY.equals(normalizeFolderKind(parentFolder.getKind()))) {
            throw new IllegalArgumentException("广播只能挂在大类下面");
        }
        return parentId;
    }

    private String resolveFolderContentLanguage(String kind, String parentId, String contentLanguage) {
        if (FOLDER_KIND_CHANNEL.equals(kind)) {
            TaskFolder parentFolder = folders.get(parentId);
            return parentFolder == null ? normalizeContentLanguage(contentLanguage) : normalizeContentLanguage(parentFolder.getContentLanguage());
        }
        return normalizeContentLanguage(contentLanguage);
    }

    private String normalizeContentLanguage(String contentLanguage) {
        String normalized = contentLanguage == null || contentLanguage.isBlank()
                ? "en"
                : contentLanguage.trim().toLowerCase();
        if (!List.of("zh", "en", "ja").contains(normalized)) {
            throw new IllegalArgumentException("内容语言只支持 zh、en 或 ja");
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeCoverImageDataUrl(String coverImageDataUrl) {
        if (coverImageDataUrl == null) {
            return null;
        }
        String normalized = coverImageDataUrl.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeExternalUrl(String url) {
        if (url == null) {
            return null;
        }
        String normalized = url.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean needsRepublish(TranscriptionTask task) {
        return isLegacyPrivateAudioUrl(task.getAudioUrl()) || isLegacyPrivateAudioUrl(task.getSubtitleUrl());
    }

    private boolean isLegacyPrivateAudioUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String normalized = url.trim().toLowerCase();
        return normalized.contains("http://minio:9000/")
                || normalized.contains("https://minio:9000/")
                || normalized.contains("http://localhost:9000/")
                || normalized.contains("https://localhost:9000/");
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

    private Path resolveRepositoryRoot(Path currentDirectory) {
        if ("backend".equals(currentDirectory.getFileName().toString())) {
            return currentDirectory.getParent();
        }
        return currentDirectory;
    }

    private Path resolvePythonExecutable() {
        Path virtualEnvPython = backendRoot.resolve(".venv/bin/python");
        if (Files.exists(virtualEnvPython)) {
            return virtualEnvPython;
        }
        return Path.of("python3");
    }

    private synchronized void updateStatus(TranscriptionTask task, TaskStatus status, int progress, String errorMessage) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(Instant.now());
        persistTask(task);
        syncTaskMetadataSafely(task);
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

    private void syncTaskMetadataSafely(TranscriptionTask task) {
        if (!assetPublishService.isCloudSyncConfigured()) {
            return;
        }
        try {
            assetPublishService.syncTaskMetadata(task);
        } catch (Exception ignored) {
        }
    }

    private void syncTaskMetadataOnCreate(TranscriptionTask task) {
        if (!assetPublishService.isCloudSyncConfigured()) {
            return;
        }
        try {
            assetPublishService.syncTaskMetadata(task);
        } catch (Exception exception) {
            tasks.remove(task.getId());
            deleteTaskDirectoryIfExists(task.getId());
            throw new IllegalStateException("同步任务到 API 端失败，请重试。", exception);
        }
    }

    private void deleteTaskDirectoryIfExists(String taskId) {
        Path taskDirectory = taskRoot.resolve(taskId);
        if (!Files.exists(taskDirectory)) {
            return;
        }
        try (var paths = Files.walk(taskDirectory)) {
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
