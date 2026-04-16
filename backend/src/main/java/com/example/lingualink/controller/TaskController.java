package com.example.lingualink.controller;

import com.example.lingualink.dto.CreateFolderRequest;
import com.example.lingualink.dto.CreateTaskRequest;
import com.example.lingualink.dto.MoveTaskRequest;
import com.example.lingualink.dto.UpdateFolderRequest;
import com.example.lingualink.model.TaskFolder;
import com.example.lingualink.model.TranscriptionTask;
import com.example.lingualink.model.UserAccount;
import com.example.lingualink.service.AuthService;
import com.example.lingualink.service.CloudSyncService;
import com.example.lingualink.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Resource> getTaskAudio(@PathVariable String taskId) {
        Resource resource = new FileSystemResource(taskService.getTaskAudioPath(taskId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"source.mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(resource);
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

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
