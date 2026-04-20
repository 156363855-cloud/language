package com.example.lingualink.controller;

import com.example.lingualink.dto.CreateFolderRequest;
import com.example.lingualink.dto.CreateTaskRequest;
import com.example.lingualink.dto.ImportTaskRequest;
import com.example.lingualink.dto.MoveTaskRequest;
import com.example.lingualink.dto.UpdateFolderRequest;
import com.example.lingualink.model.TaskFolder;
import com.example.lingualink.model.TranscriptionTask;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.service.AuthService;
import com.example.lingualink.service.CloudSyncService;
import com.example.lingualink.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final String CLOUD_SYNC_OWNER_EMAIL = "leonlovepeace@outlook.com";

    private final TaskService taskService;
    private final CloudSyncService cloudSyncService;
    private final AuthService authService;

    public TaskController(TaskService taskService, CloudSyncService cloudSyncService, AuthService authService) {
        this.taskService = taskService;
        this.cloudSyncService = cloudSyncService;
        this.authService = authService;
    }

    @GetMapping
    public List<TranscriptionTask> listTasks() {
        return taskService.listTasks();
    }

    @GetMapping("/folders")
    public List<TaskFolder> listFolders() {
        return taskService.listFolders();
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskFolder createFolder(@Valid @RequestBody CreateFolderRequest request) {
        return taskService.createFolder(request);
    }

    @PatchMapping("/folders/{folderId}")
    public TaskFolder updateFolder(@PathVariable String folderId, @Valid @RequestBody UpdateFolderRequest request) {
        return taskService.updateFolder(folderId, request);
    }

    @GetMapping("/{taskId}")
    public TranscriptionTask getTask(@PathVariable String taskId) {
        return taskService.getTask(taskId);
    }

    @GetMapping("/{taskId}/audio")
    public ResponseEntity<?> getTaskAudio(
            @PathVariable String taskId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        TranscriptionTask task = taskService.getTask(taskId);
        if (task.getAudioUrl() != null && !task.getAudioUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create(task.getAudioUrl()))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }
        return buildLocalAudioResponse(taskService.getTaskAudioPath(taskId), rangeHeader);
    }

    private ResponseEntity<byte[]> buildLocalAudioResponse(Path audioPath, String rangeHeader) {
        try {
            byte[] audioBytes = Files.readAllBytes(audioPath);
            long totalLength = audioBytes.length;

            if (rangeHeader == null || rangeHeader.isBlank()) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"source.mp3\"")
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .contentType(MediaType.parseMediaType("audio/mpeg"))
                        .contentLength(totalLength)
                        .body(audioBytes);
            }

            long[] range = parseRange(rangeHeader, totalLength);
            long start = range[0];
            long end = range[1];
            int contentLength = (int) (end - start + 1);
            byte[] partialBytes = new byte[contentLength];
            System.arraycopy(audioBytes, (int) start, partialBytes, 0, contentLength);

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"source.mp3\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalLength)
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .contentLength(contentLength)
                    .body(partialBytes);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取本地音频失败", exception);
        }
    }

    private long[] parseRange(String rangeHeader, long totalLength) {
        if (!rangeHeader.startsWith("bytes=")) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "不支持的音频范围请求");
        }
        String value = rangeHeader.substring("bytes=".length()).trim();
        String[] parts = value.split("-", 2);
        try {
            long start;
            long end;
            if (parts[0].isBlank()) {
                long suffixLength = Long.parseLong(parts[1]);
                start = Math.max(0, totalLength - suffixLength);
                end = totalLength - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts.length < 2 || parts[1].isBlank() ? totalLength - 1 : Long.parseLong(parts[1]);
            }
            if (start < 0 || end < start || start >= totalLength) {
                throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "音频范围超出限制");
            }
            end = Math.min(end, totalLength - 1);
            return new long[]{start, end};
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "音频范围格式不正确", exception);
        }
    }

    @PatchMapping("/{taskId}/folder")
    public TranscriptionTask moveTask(@PathVariable String taskId, @Valid @RequestBody MoveTaskRequest request) {
        return taskService.moveTaskToFolder(taskId, request.folderId());
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable String taskId) {
        taskService.deleteTask(taskId);
    }

    @DeleteMapping("/folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(@PathVariable String folderId) {
        taskService.deleteFolder(folderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TranscriptionTask createTask(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request);
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public TranscriptionTask importTask(@Valid @RequestBody ImportTaskRequest request) {
        return taskService.importTask(request);
    }

    @PostMapping("/sync-to-cloud")
    public Map<String, String> syncToCloud(@org.springframework.web.bind.annotation.RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        UserAccount user = authService.requireUser(authorizationHeader);
        if (!CLOUD_SYNC_OWNER_EMAIL.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有指定管理员账号可以同步到云端。");
        }
        try {
            CloudSyncService.SyncResult result = cloudSyncService.syncRuntimeToCloud();
            return Map.of(
                    "status", "ok",
                    "message", result.message(),
                    "output", result.output()
            );
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/republish")
    public Map<String, Object> republishBrokenTasks(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestBody(required = false) Map<String, String> payload
    ) {
        UserAccount user = authService.requireUser(authorizationHeader);
        if (!CLOUD_SYNC_OWNER_EMAIL.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有指定管理员账号可以重新发布旧素材。");
        }
        String sourceLanguage = payload == null ? null : payload.get("sourceLanguage");
        return taskService.republishBrokenTasks(sourceLanguage);
    }

    @PostMapping("/{taskId}/republish")
    public TranscriptionTask republishTask(
            @PathVariable String taskId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader
    ) {
        UserAccount user = authService.requireUser(authorizationHeader);
        if (!CLOUD_SYNC_OWNER_EMAIL.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有指定管理员账号可以重新发布旧素材。");
        }
        return taskService.republishTask(taskId);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
