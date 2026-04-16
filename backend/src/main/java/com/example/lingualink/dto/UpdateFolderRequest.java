package com.example.lingualink.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateFolderRequest(
        @NotBlank(message = "文件夹名称不能为空")
        String name,
        String parentId,
        String contentLanguage,
        String coverImageDataUrl,
        Integer coverOpacity
) {
}
