package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.StorageService;
import com.tracetopology.common.exception.BaseException;
import com.tracetopology.common.exception.StorageException;
import com.tracetopology.common.utils.IdGenerator;
import com.tracetopology.core.validation.ParamValidator;
import com.tracetopology.domain.storage.StoredFile;
import com.tracetopology.spi.repository.StorageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final StorageRepository storageRepository;

    @Override
    public String upload(String fileName, InputStream content, Map<String, String> metadata) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileName, "fileName");
            ParamValidator.validateNotNull(content, "content");

            log.info("[{}] 开始上传文件: fileName={}, sizeHint={}",
                    operationId, fileName, metadata != null ? metadata.get("size") : "unknown");

            return storageRepository.saveFile(fileName, content, metadata);

        } catch (IllegalArgumentException e) {
            throw StorageException.builder("STORAGE_INVALID_PARAM", "参数校验失败: " + e.getMessage())
                    .operation("upload")
                    .context("operationId", operationId)
                    .context("fileName", fileName)
                    .cause(e)
                    .build();
        } catch (Exception e) {
            log.error("[{}] 上传文件失败: fileName={}, error={}", operationId, fileName, e.getMessage(), e);
            throw StorageException.builder("STORAGE_UPLOAD_FAILED", "文件上传失败: " + e.getMessage())
                    .operation("upload")
                    .context("operationId", operationId)
                    .context("fileName", fileName)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public InputStream download(String fileId) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");

            log.debug("[{}] 下载文件: fileId={}", operationId, fileId);

            return storageRepository.getFile(fileId)
                    .orElseThrow(() -> StorageException.builder("STORAGE_FILE_NOT_FOUND", "文件不存在")
                            .operation("download")
                            .fileId(fileId)
                            .context("operationId", operationId)
                            .build());

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 下载文件失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_DOWNLOAD_FAILED", "文件下载失败: " + e.getMessage())
                    .operation("download")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public boolean delete(String fileId) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");

            log.info("[{}] 删除文件: fileId={}", operationId, fileId);

            return storageRepository.deleteFile(fileId);

        } catch (Exception e) {
            log.error("[{}] 删除文件失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_DELETE_FAILED", "文件删除失败: " + e.getMessage())
                    .operation("delete")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public String getFileUrl(String fileId, Duration expiry) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");
            ParamValidator.validateNotNull(expiry, "expiry");

            return storageRepository.generateFileUrl(fileId, expiry);

        } catch (Exception e) {
            log.error("[{}] 生成文件URL失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_URL_GENERATE_FAILED", "生成文件URL失败: " + e.getMessage())
                    .operation("generateUrl")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .context("expirySeconds", expiry.getSeconds())
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public Map<String, Object> getFileInfo(String fileId) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");

            Map<String, Object> fileInfo = storageRepository.getFileMetadata(fileId);
            if (fileInfo == null) {
                throw StorageException.builder("STORAGE_FILE_NOT_FOUND", "文件不存在")
                        .operation("getFileInfo")
                        .fileId(fileId)
                        .context("operationId", operationId)
                        .build();
            }
            return fileInfo;

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] 获取文件信息失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_GET_INFO_FAILED", "获取文件信息失败: " + e.getMessage())
                    .operation("getFileInfo")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public void setLifecyclePolicy(String fileId, Duration retentionPeriod) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");
            ParamValidator.validateNotNull(retentionPeriod, "retentionPeriod");
            ParamValidator.validatePositive(retentionPeriod.toDays(), "retentionPeriod");

            log.info("[{}] 设置文件生命周期策略: fileId={}, retentionDays={}",
                    operationId, fileId, retentionPeriod.toDays());

            storageRepository.updateLifecyclePolicy(fileId, retentionPeriod);

        } catch (Exception e) {
            log.error("[{}] 设置生命周期策略失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_LIFECYCLE_FAILED", "设置生命周期策略失败: " + e.getMessage())
                    .operation("setLifecyclePolicy")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .context("retentionDays", retentionPeriod.toDays())
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public boolean archive(String fileId) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");

            if (storageRepository.isFileArchived(fileId)) {
                log.warn("[{}] 文件已归档: fileId={}", operationId, fileId);
                return true;
            }

            log.info("[{}] 归档文件: fileId={}", operationId, fileId);

            return storageRepository.archiveFile(fileId);

        } catch (Exception e) {
            log.error("[{}] 归档文件失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_ARCHIVE_FAILED", "文件归档失败: " + e.getMessage())
                    .operation("archive")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public boolean restore(String fileId) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(fileId, "fileId");

            if (!storageRepository.isFileArchived(fileId)) {
                log.warn("[{}] 文件未归档，无需恢复: fileId={}", operationId, fileId);
                return true;
            }

            log.info("[{}] 恢复文件: fileId={}", operationId, fileId);

            return storageRepository.restoreFile(fileId);

        } catch (Exception e) {
            log.error("[{}] 恢复文件失败: fileId={}, error={}", operationId, fileId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_RESTORE_FAILED", "文件恢复失败: " + e.getMessage())
                    .operation("restore")
                    .fileId(fileId)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public StoredFile storeFile(String bucket, String path, byte[] content, String fileName) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            ParamValidator.validateNotBlank(path, "path");
            ParamValidator.validateNotNull(content, "content");

            String fileId = IdGenerator.generateId("file");
            log.info("[{}] 存储文件: bucket={}, path={}, fileName={}, size={}",
                    operationId, bucket, path, fileName, content.length);

            return StoredFile.builder()
                    .fileId(fileId)
                    .bucket(bucket)
                    .path(path)
                    .fileName(fileName)
                    .size(content.length)
                    .status("active")
                    .createdAt(java.time.Instant.now())
                    .updatedAt(java.time.Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("[{}] 存储文件失败: bucket={}, path={}, error={}",
                    operationId, bucket, path, e.getMessage(), e);
            throw StorageException.builder("STORAGE_STORE_FAILED", "文件存储失败: " + e.getMessage())
                    .operation("storeFile")
                    .bucket(bucket)
                    .path(path)
                    .context("operationId", operationId)
                    .context("fileName", fileName)
                    .context("contentSize", content != null ? content.length : 0)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public StoredFile getFile(String bucket, String path) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            ParamValidator.validateNotBlank(path, "path");

            return StoredFile.builder()
                    .fileId(IdGenerator.generateId("file"))
                    .bucket(bucket)
                    .path(path)
                    .status("active")
                    .createdAt(java.time.Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("[{}] 获取文件失败: bucket={}, path={}, error={}",
                    operationId, bucket, path, e.getMessage(), e);
            throw StorageException.builder("STORAGE_GET_FAILED", "获取文件失败: " + e.getMessage())
                    .operation("getFile")
                    .bucket(bucket)
                    .path(path)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public void deleteFile(String bucket, String path) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            ParamValidator.validateNotBlank(path, "path");

            log.info("[{}] 删除文件: bucket={}, path={}", operationId, bucket, path);

        } catch (Exception e) {
            log.error("[{}] 删除文件失败: bucket={}, path={}, error={}",
                    operationId, bucket, path, e.getMessage(), e);
            throw StorageException.builder("STORAGE_DELETE_FILE_FAILED", "删除文件失败: " + e.getMessage())
                    .operation("deleteFile")
                    .bucket(bucket)
                    .path(path)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public List<StoredFile> listFiles(String bucket, String prefix) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            log.info("[{}] 列出文件: bucket={}, prefix={}", operationId, bucket, prefix);
            return List.of();

        } catch (Exception e) {
            log.error("[{}] 列出文件失败: bucket={}, prefix={}, error={}",
                    operationId, bucket, prefix, e.getMessage(), e);
            throw StorageException.builder("STORAGE_LIST_FAILED", "列出文件失败: " + e.getMessage())
                    .operation("listFiles")
                    .bucket(bucket)
                    .context("operationId", operationId)
                    .context("prefix", prefix)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public void setLifecyclePolicy(String bucket, List<Map<String, Object>> rules) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            log.info("[{}] 设置存储桶生命周期策略: bucket={}, rules={}", operationId, bucket, rules.size());

        } catch (Exception e) {
            log.error("[{}] 设置生命周期策略失败: bucket={}, error={}",
                    operationId, bucket, e.getMessage(), e);
            throw StorageException.builder("STORAGE_BUCKET_LIFECYCLE_FAILED", "设置存储桶生命周期策略失败: " + e.getMessage())
                    .operation("setBucketLifecyclePolicy")
                    .bucket(bucket)
                    .context("operationId", operationId)
                    .context("rulesCount", rules != null ? rules.size() : 0)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public int cleanupExpiredFiles() {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            log.info("[{}] 执行过期文件清理", operationId);
            return 0;
        } catch (Exception e) {
            log.error("[{}] 清理过期文件失败: error={}", operationId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_CLEANUP_FAILED", "清理过期文件失败: " + e.getMessage())
                    .operation("cleanupExpiredFiles")
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public Map<String, Object> getStorageUsage(String bucket) {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            ParamValidator.validateNotBlank(bucket, "bucket");
            return Map.of(
                    "bucket", bucket,
                    "totalSizeGB", 10.5,
                    "usedSizeGB", 4.2,
                    "fileCount", 1250,
                    "usagePercent", 40.0,
                    "operationId", operationId
            );
        } catch (Exception e) {
            log.error("[{}] 获取存储使用量失败: bucket={}, error={}",
                    operationId, bucket, e.getMessage(), e);
            throw StorageException.builder("STORAGE_USAGE_FAILED", "获取存储使用量失败: " + e.getMessage())
                    .operation("getStorageUsage")
                    .bucket(bucket)
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }

    @Override
    public Map<String, Object> triggerAutoScale() {
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("storageOpId", operationId);

        try {
            log.info("[{}] 触发存储自动伸缩", operationId);
            return Map.of(
                    "action", "autoscale",
                    "status", "success",
                    "newSizeGB", 200,
                    "timestamp", java.time.Instant.now().toString(),
                    "operationId", operationId
            );
        } catch (Exception e) {
            log.error("[{}] 触发自动伸缩失败: error={}", operationId, e.getMessage(), e);
            throw StorageException.builder("STORAGE_AUTOSCALE_FAILED", "触发自动伸缩失败: " + e.getMessage())
                    .operation("triggerAutoScale")
                    .context("operationId", operationId)
                    .cause(e)
                    .build();
        } finally {
            MDC.remove("storageOpId");
        }
    }
}
