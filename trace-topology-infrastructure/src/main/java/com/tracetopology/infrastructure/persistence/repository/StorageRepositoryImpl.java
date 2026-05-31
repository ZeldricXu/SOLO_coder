package com.tracetopology.infrastructure.persistence.repository;

import com.tracetopology.common.utils.IdGenerator;
import com.tracetopology.spi.repository.StorageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Repository
public class StorageRepositoryImpl implements StorageRepository {

    private final Map<String, FileRecord> fileStore = new ConcurrentHashMap<>();
    private final Map<String, byte[]> contentStore = new ConcurrentHashMap<>();

    @Override
    public String saveFile(String fileName, InputStream content, Map<String, String> metadata) {
        try {
            String fileId = IdGenerator.generateId("file");
            byte[] bytes = content.readAllBytes();

            FileRecord record = FileRecord.builder()
                    .fileId(fileId)
                    .fileName(fileName)
                    .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
                    .size(bytes.length)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .archived(false)
                    .retentionPeriod(Duration.ofDays(30))
                    .build();

            fileStore.put(fileId, record);
            contentStore.put(fileId, bytes);

            log.info("文件已保存: fileId={}, fileName={}, size={}", fileId, fileName, bytes.length);
            return fileId;
        } catch (Exception e) {
            throw new RuntimeException("文件保存失败", e);
        }
    }

    @Override
    public Optional<InputStream> getFile(String fileId) {
        byte[] content = contentStore.get(fileId);
        if (content == null) {
            return Optional.empty();
        }
        return Optional.of(new ByteArrayInputStream(content));
    }

    @Override
    public boolean deleteFile(String fileId) {
        if (!fileStore.containsKey(fileId)) {
            return false;
        }
        fileStore.remove(fileId);
        contentStore.remove(fileId);
        log.info("文件已删除: fileId={}", fileId);
        return true;
    }

    @Override
    public String generateFileUrl(String fileId, Duration expiry) {
        if (!fileStore.containsKey(fileId)) {
            throw new RuntimeException("文件不存在: " + fileId);
        }
        String token = IdGenerator.generateId("token");
        long expiresAt = System.currentTimeMillis() + expiry.toMillis();
        return String.format("/api/v1/storage/%s/download?token=%s&expires=%d", fileId, token, expiresAt);
    }

    @Override
    public Map<String, Object> getFileMetadata(String fileId) {
        FileRecord record = fileStore.get(fileId);
        if (record == null) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileId", record.fileId);
        metadata.put("fileName", record.fileName);
        metadata.put("size", record.size);
        metadata.put("createdAt", record.createdAt);
        metadata.put("updatedAt", record.updatedAt);
        metadata.put("archived", record.archived);
        metadata.put("retentionDays", record.retentionPeriod.toDays());
        metadata.put("customMetadata", record.metadata);
        return metadata;
    }

    @Override
    public void updateLifecyclePolicy(String fileId, Duration retentionPeriod) {
        FileRecord record = fileStore.get(fileId);
        if (record != null) {
            record.retentionPeriod = retentionPeriod;
            record.updatedAt = Instant.now();
            log.info("文件生命周期策略已更新: fileId={}, retentionDays={}", fileId, retentionPeriod.toDays());
        }
    }

    @Override
    public boolean archiveFile(String fileId) {
        FileRecord record = fileStore.get(fileId);
        if (record == null || record.archived) {
            return false;
        }
        record.archived = true;
        record.updatedAt = Instant.now();
        log.info("文件已归档: fileId={}", fileId);
        return true;
    }

    @Override
    public boolean restoreFile(String fileId) {
        FileRecord record = fileStore.get(fileId);
        if (record == null || !record.archived) {
            return false;
        }
        record.archived = false;
        record.updatedAt = Instant.now();
        log.info("文件已恢复: fileId={}", fileId);
        return true;
    }

    @Override
    public boolean isFileArchived(String fileId) {
        FileRecord record = fileStore.get(fileId);
        return record != null && record.archived;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    static class FileRecord {
        private String fileId;
        private String fileName;
        private Map<String, String> metadata;
        private long size;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean archived;
        private Duration retentionPeriod;
    }
}
