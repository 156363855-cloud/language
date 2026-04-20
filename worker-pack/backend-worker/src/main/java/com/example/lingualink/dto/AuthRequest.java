package com.example.lingualink.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "请输入正确的邮箱")
        String email,
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, message = "密码至少需要 6 位")
        String password
) {
}
