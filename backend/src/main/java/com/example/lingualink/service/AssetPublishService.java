package com.example.lingualink.service;

import com.example.lingualink.model.TranscriptionTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.http.client.StandardClientProperties;
import com.oracle.bmc.http.client.jersey3.ApacheClientProperties;
import com.oracle.bmc.http.client.jersey3.apacheconfigurator.ApacheConfigurator;
import com.oracle.bmc.http.client.jersey3.apacheconfigurator.ApacheConnectorProperties;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;
import jakarta.annotation.PreDestroy;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AssetPublishService {
    private static final Logger logger = LoggerFactory.getLogger(AssetPublishService.class);
    private static final int CLOUD_SYNC_ATTEMPTS = 3;
    private static final int OCI_UPLOAD_ATTEMPTS = 3;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String storageEndpoint;
    private final String storagePublicBaseUrl;
    private final String storageAccessKey;
    private final String storageSecretKey;
    private final String storageBucket;
    private final String cloudApiBaseUrl;
    private final String cloudImportToken;
    private final String ociNamespace;
    private final String ociRegion;
    private final String ociTenancy;
    private final String ociUser;
    private final String ociFingerprint;
    private final String ociKeyFile;

    private final Object ociClientLock = new Object();
    private volatile ObjectStorageClient sharedOciClient;

    public AssetPublishService(
            @Value("${app.storage.endpoint:}") String storageEndpoint,
            @Value("${app.storage.public-base-url:}") String storagePublicBaseUrl,
            @Value("${app.storage.access-key:}") String storageAccessKey,
            @Value("${app.storage.secret-key:}") String storageSecretKey,
            @Value("${app.storage.bucket:lingualink-assets}") String storageBucket,
            @Value("${app.cloud.api-base-url:}") String cloudApiBaseUrl,
            @Value("${app.cloud.import-token:}") String cloudImportToken,
            @Value("${app.storage.oci.namespace:}") String ociNamespace,
            @Value("${app.storage.oci.region:}") String ociRegion,
            @Value("${app.storage.oci.tenancy:}") String ociTenancy,
            @Value("${app.storage.oci.user:}") String ociUser,
            @Value("${app.storage.oci.fingerprint:}") String ociFingerprint,
            @Value("${app.storage.oci.key-file:}") String ociKeyFile
    ) {
        this.storageEndpoint = storageEndpoint;
        this.storagePublicBaseUrl = storagePublicBaseUrl;
        this.storageAccessKey = storageAccessKey;
        this.storageSecretKey = storageSecretKey;
        this.storageBucket = storageBucket;
        this.cloudApiBaseUrl = cloudApiBaseUrl;
        this.cloudImportToken = cloudImportToken;
        this.ociNamespace = ociNamespace;
        this.ociRegion = ociRegion;
        this.ociTenancy = ociTenancy;
        this.ociUser = ociUser;
        this.ociFingerprint = ociFingerprint;
        this.ociKeyFile = ociKeyFile;
    }

    public boolean isPublishingConfigured() {
        return isCloudSyncConfigured() && (isOciConfigured() || isS3Configured());
    }

    public boolean isCloudSyncConfigured() {
        return !isBlank(cloudApiBaseUrl);
    }

    public void publishTaskAssets(TranscriptionTask task, Path taskDirectory) {
        if (!isPublishingConfigured()) {
            return;
        }

        try {
            String assetPrefix = "tasks/" + task.getId();
            Path audioPath = taskDirectory.resolve("source.mp3");
            Path subtitleJsonPath = taskDirectory.resolve("subtitles.json");

            Files.writeString(
                    subtitleJsonPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "mediaTitle", task.getMediaTitle(),
                            "segments", task.getSegments()
                    )),
                    StandardCharsets.UTF_8
            );

            String audioObjectKey = assetPrefix + "/source.mp3";
            String subtitleObjectKey = assetPrefix + "/subtitles.json";

            if (isOciConfigured()) {
                publishToOci(audioPath, subtitleJsonPath, audioObjectKey, subtitleObjectKey, task);
            } else {
                publishToS3Compatible(audioPath, subtitleJsonPath, audioObjectKey, subtitleObjectKey, task);
            }

            syncTaskMetadata(task);
        } catch (Exception exception) {
            throw new IllegalStateException("发布素材到对象存储失败: " + exception.getMessage(), exception);
        }
    }

    public void syncTaskMetadata(TranscriptionTask task) throws IOException, InterruptedException {
        IOException lastIoException = null;
        InterruptedException lastInterruptedException = null;
        IllegalStateException lastStateException = null;

        for (int attempt = 1; attempt <= CLOUD_SYNC_ATTEMPTS; attempt++) {
            try {
                syncTaskMetadataOnce(task);
                return;
            } catch (IOException exception) {
                lastIoException = exception;
                logger.warn("同步任务元数据失败，第 {} 次重试，taskId={}", attempt, task.getId(), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastInterruptedException = exception;
                logger.warn("同步任务元数据被中断，taskId={}", task.getId(), exception);
                break;
            } catch (IllegalStateException exception) {
                lastStateException = exception;
                logger.warn("同步任务元数据失败，第 {} 次重试，taskId={}", attempt, task.getId(), exception);
            }

            if (attempt < CLOUD_SYNC_ATTEMPTS) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }

        if (lastInterruptedException != null) {
            throw lastInterruptedException;
        }
        if (lastIoException != null) {
            throw lastIoException;
        }
        if (lastStateException != null) {
            throw lastStateException;
        }
    }

    private void syncTaskMetadataOnce(TranscriptionTask task) throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", task.getId());
        payload.put("mediaUrl", task.getMediaUrl());
        payload.put("folderId", task.getFolderId());
        payload.put("sourceLanguage", task.getSourceLanguage());
        payload.put("targetLanguages", String.join(",", task.getTargetLanguages()));
        payload.put("mediaTitle", task.getMediaTitle());
        payload.put("segments", task.getSegments());
        payload.put("audioUrl", task.getAudioUrl());
        payload.put("subtitleUrl", task.getSubtitleUrl());
        payload.put("coverUrl", task.getCoverUrl());
        payload.put("createdAt", task.getCreatedAt());
        payload.put("status", task.getStatus());
        payload.put("progress", task.getProgress());
        payload.put("errorMessage", task.getErrorMessage());
        payload.put("audioAvailable", task.isAudioAvailable());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(cloudApiBaseUrl) + "/tasks/import"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");

        if (!isBlank(cloudImportToken)) {
            builder.header("Authorization", "Bearer " + cloudImportToken);
        }

        HttpResponse<String> response = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() >= 400) {
            throw new IllegalStateException("同步到云端失败（HTTP " + response.statusCode() + "）: " + response.body());
        }
    }

    private void publishToS3Compatible(
            Path audioPath,
            Path subtitleJsonPath,
            String audioObjectKey,
            String subtitleObjectKey,
            TranscriptionTask task
    ) throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(storageEndpoint)
                .credentials(storageAccessKey, storageSecretKey)
                .build();

        ensureBucket(minioClient);
        putFile(minioClient, audioPath, audioObjectKey, "audio/mpeg");
        putBytes(
                minioClient,
                subtitleJsonPath,
                subtitleObjectKey,
                Files.readAllBytes(subtitleJsonPath),
                "application/json"
        );

        task.setAudioUrl(buildS3PublicUrl(minioClient, audioObjectKey));
        task.setSubtitleUrl(buildS3PublicUrl(minioClient, subtitleObjectKey));
    }

    private void publishToOci(
            Path audioPath,
            Path subtitleJsonPath,
            String audioObjectKey,
            String subtitleObjectKey,
            TranscriptionTask task
    ) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= OCI_UPLOAD_ATTEMPTS; attempt++) {
            try {
                ObjectStorageClient client = getOrCreateOciClient();

                long localAudioSize = Files.exists(audioPath) ? Files.size(audioPath) : 0L;
                Long remoteAudioSize = headOciObjectSize(client, audioObjectKey);
                if (remoteAudioSize != null && localAudioSize > 0 && remoteAudioSize == localAudioSize) {
                    logger.info("OCI 音频对象已存在，跳过上传 taskId={} size={}", task.getId(), remoteAudioSize);
                } else {
                    putOciFile(client, audioPath, audioObjectKey, "audio/mpeg");
                }

                putOciBytes(client, subtitleJsonPath, subtitleObjectKey, Files.readAllBytes(subtitleJsonPath), "application/json");

                task.setAudioUrl(buildOciAccessUrl(client, audioObjectKey));
                task.setSubtitleUrl(buildOciAccessUrl(client, subtitleObjectKey));
                return;
            } catch (Exception exception) {
                lastException = exception;
                logger.warn("OCI 发布失败，第 {} 次重试，taskId={}", attempt, task.getId(), exception);
                if (isTransientHttpClientError(exception)) {
                    resetOciClient();
                }
                if (attempt < OCI_UPLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw interruptedException;
                    }
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }

    private Long headOciObjectSize(ObjectStorageClient client, String objectKey) {
        try {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder()
                            .namespaceName(ociNamespace)
                            .bucketName(storageBucket)
                            .objectName(objectKey)
                            .build()
            );
            return response.getContentLength();
        } catch (BmcException exception) {
            if (exception.getStatusCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    private ObjectStorageClient getOrCreateOciClient() {
        ObjectStorageClient existing = sharedOciClient;
        if (existing != null) {
            return existing;
        }
        synchronized (ociClientLock) {
            if (sharedOciClient == null) {
                sharedOciClient = buildOciClient();
            }
            return sharedOciClient;
        }
    }

    private void resetOciClient() {
        synchronized (ociClientLock) {
            ObjectStorageClient toClose = sharedOciClient;
            sharedOciClient = null;
            if (toClose != null) {
                try {
                    toClose.close();
                } catch (Exception closeException) {
                    logger.debug("关闭旧的 OCI 客户端时忽略异常", closeException);
                }
            }
        }
    }

    @PreDestroy
    void shutdownOciClient() {
        resetOciClient();
    }

    private boolean isTransientHttpClientError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("connection pool shut down")
                        || lower.contains("connection pool")
                        || lower.contains("connection reset")
                        || lower.contains("closed")
                        || lower.contains("broken pipe")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private ObjectStorageClient buildOciClient() {
        if (!Files.exists(Path.of(ociKeyFile))) {
            throw new IllegalStateException("OCI 私钥文件不存在: " + ociKeyFile);
        }

        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(ociTenancy)
                .userId(ociUser)
                .fingerprint(ociFingerprint)
                .privateKeySupplier(this::openOciPrivateKey)
                .build();

        ApacheConfigurator.NonBuffering nonBufferingConfigurator = new ApacheConfigurator.NonBuffering(
                ApacheConnectorProperties.builder()
                        .connectionReuseStrategy(null)
                        .requestRetryHandler(null)
                        .build()
        );

        ObjectStorageClient client = ObjectStorageClient.builder()
                .clientConfigurator(builder -> {
                    nonBufferingConfigurator.customizeClient(builder);
                    builder.property(StandardClientProperties.BUFFER_REQUEST, false);
                    builder.property(ApacheClientProperties.REUSE_STRATEGY, null);
                    builder.property(ApacheClientProperties.RETRY_HANDLER, null);
                })
                .region(Region.fromRegionId(ociRegion))
                .build(provider);
        return client;
    }

    private InputStream openOciPrivateKey() {
        try {
            return new FileInputStream(ociKeyFile);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 OCI 私钥文件失败: " + ociKeyFile, exception);
        }
    }

    private void putOciFile(ObjectStorageClient client, Path filePath, String objectKey, String contentType) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("待上传文件不存在: " + filePath);
        }

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .namespaceName(ociNamespace)
                    .bucketName(storageBucket)
                    .objectName(objectKey)
                    .contentType(contentType)
                    .contentLength(Files.size(filePath))
                    .putObjectBody(inputStream)
                    .build();
            client.putObject(request);
        } catch (BmcException exception) {
            throw new IllegalStateException("上传 OCI 文件失败: " + exception.getMessage(), exception);
        }
    }

    private void putOciBytes(
            ObjectStorageClient client,
            Path filePath,
            String objectKey,
            byte[] content,
            String contentType
    ) {
        if (content.length == 0) {
            throw new IllegalStateException("待上传内容为空: " + filePath);
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .namespaceName(ociNamespace)
                    .bucketName(storageBucket)
                    .objectName(objectKey)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .putObjectBody(inputStream)
                    .build();
            client.putObject(request);
        } catch (IOException exception) {
            throw new IllegalStateException("读取待上传内容失败: " + filePath, exception);
        } catch (BmcException exception) {
            throw new IllegalStateException("上传 OCI 内容失败: " + exception.getMessage(), exception);
        }
    }

    private String buildOciAccessUrl(ObjectStorageClient client, String objectKey) {
        if (!isBlank(storagePublicBaseUrl)) {
            return trimTrailingSlash(storagePublicBaseUrl) + "/" + objectKey;
        }

        CreatePreauthenticatedRequestDetails details = CreatePreauthenticatedRequestDetails.builder()
                .name("lingualink-" + UUID.randomUUID())
                .objectName(objectKey)
                .accessType(CreatePreauthenticatedRequestDetails.AccessType.ObjectRead)
                .timeExpires(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .build();

        CreatePreauthenticatedRequestRequest request = CreatePreauthenticatedRequestRequest.builder()
                .namespaceName(ociNamespace)
                .bucketName(storageBucket)
                .createPreauthenticatedRequestDetails(details)
                .build();

        try {
            String accessUri = client.createPreauthenticatedRequest(request)
                    .getPreauthenticatedRequest()
                    .getAccessUri();
            return "https://objectstorage." + ociRegion + ".oraclecloud.com" + accessUri;
        } catch (BmcException exception) {
            throw new IllegalStateException("生成 OCI 访问链接失败: " + exception.getMessage(), exception);
        }
    }

    private boolean isS3Configured() {
        return !isBlank(storageEndpoint)
                && !isBlank(storageAccessKey)
                && !isBlank(storageSecretKey);
    }

    private boolean isOciConfigured() {
        return !isBlank(ociNamespace)
                && !isBlank(ociRegion)
                && !isBlank(ociTenancy)
                && !isBlank(ociUser)
                && !isBlank(ociFingerprint)
                && !isBlank(ociKeyFile);
    }

    private void ensureBucket(MinioClient minioClient) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(storageBucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(storageBucket).build());
        }
    }

    private void putFile(MinioClient minioClient, Path filePath, String objectKey, String contentType) throws Exception {
        if (!Files.exists(filePath)) {
            throw new IllegalStateException("待上传文件不存在: " + filePath);
        }
        minioClient.uploadObject(
                io.minio.UploadObjectArgs.builder()
                        .bucket(storageBucket)
                        .object(objectKey)
                        .filename(filePath.toString())
                        .contentType(contentType)
                        .build()
        );
    }

    private void putBytes(MinioClient minioClient, Path filePath, String objectKey, byte[] content, String contentType) throws Exception {
        if (content.length == 0) {
            throw new IllegalStateException("待上传内容为空: " + filePath);
        }
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(storageBucket)
                        .object(objectKey)
                        .stream(new ByteArrayInputStream(content), content.length, -1)
                        .contentType(contentType)
                        .build()
        );
    }

    private String buildS3PublicUrl(MinioClient minioClient, String objectKey) throws Exception {
        if (!isBlank(storagePublicBaseUrl)) {
            return trimTrailingSlash(storagePublicBaseUrl) + "/" + storageBucket + "/" + objectKey;
        }

        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(storageBucket)
                        .object(objectKey)
                        .expiry(60 * 60 * 24 * 7)
                        .build()
        );
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
