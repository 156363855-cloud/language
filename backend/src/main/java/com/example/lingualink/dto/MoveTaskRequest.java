package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;

public record MoveTaskRequest(
        @NotBlank(message = "文件夹不能为空")
        String folderId
) {
}
