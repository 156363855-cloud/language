package com.example.lingualink.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class CloudSyncService {

    private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(10);

    public SyncResult syncRuntimeToCloud() {
        if (System.getenv("SSH_PASSWORD") == null && System.getenv("SSH_AUTH_SOCK") == null) {
            throw new IllegalStateException("未配置云端同步认证，请先为本地后端配置 SSH_PASSWORD 或可用的 SSH key。");
        }

        Path scriptPath = resolveScriptPath();
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("没有找到同步脚本: " + scriptPath);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(scriptPath.toString());
        processBuilder.directory(scriptPath.getParent().getParent().toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output = readProcessOutput(process);
            boolean finished = process.waitFor(SYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("同步超时，已经超过 " + SYNC_TIMEOUT.toMinutes() + " 分钟。");
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.isBlank() ? "同步失败，请查看本地后端日志。" : output.trim());
            }

            return new SyncResult("已同步到云端", output.trim());
        } catch (IOException exception) {
            throw new IllegalStateException("启动同步脚本失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步过程被中断。", exception);
        }
    }

    private Path resolveScriptPath() {
        String configuredPath = System.getenv("LINGUALINK_SYNC_SCRIPT");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Paths.get(configuredPath).toAbsolutePath().normalize();
        }

        Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return workingDirectory.getParent().resolve("scripts").resolve("sync-runtime-to-cloud.sh").normalize();
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    public record SyncResult(String message, String output) {
    }
}
