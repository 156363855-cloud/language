package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateFolderRequest(
        @NotBlank(message = "文件夹名称不能为空")
        String name,
        String parentId,
        String kind,
        String contentLanguage,
        String coverImageDataUrl,
        Integer coverOpacity
) {
}
