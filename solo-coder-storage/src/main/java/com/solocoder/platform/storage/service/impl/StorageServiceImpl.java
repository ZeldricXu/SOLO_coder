package com.solocoder.platform.storage.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.storage.model.BackupRecord;
import com.solocoder.platform.storage.model.RecoveryRecord;
import com.solocoder.platform.storage.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class StorageServiceImpl implements StorageService {

    private final StringRedisTemplate redisTemplate;
    private final com.solocoder.platform.storage.cache.StorageCacheManager cacheManager;
    private final Map<String, BackupRecord> backupStore = new ConcurrentHashMap<>();
    private final Map<String, RecoveryRecord> recoveryStore = new ConcurrentHashMap<>();
    private final Map<String, byte[]> dataStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadataStore = new ConcurrentHashMap<>();

    public StorageServiceImpl(StringRedisTemplate redisTemplate,
                              com.solocoder.platform.storage.cache.StorageCacheManager cacheManager) {
        this.redisTemplate = redisTemplate;
        this.cacheManager = cacheManager;
    }

    @Override
    public StorageItemResult put(String key, byte[] data, Map<String, String> metadata) {
        dataStore.put(key, Arrays.copyOf(data, data.length));
        metadataStore.put(key, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        if (metadata != null) {
            String metaKey = "storage:meta:" + key;
            metadata.forEach((k, v) -> redisTemplate.opsForHash().put(metaKey, k, v));
        }
        StorageItemResult result = new StorageItemResult(key, data, metadata, data.length);
        cacheManager.put(key, result);
        log.info("Stored item: key={}, size={}", key, data.length);
        return result;
    }

    @Override
    public Optional<StorageItemResult> get(String key) {
        Optional<StorageItemResult> cached = cacheManager.get(key);
        if (cached.isPresent()) {
            return cached;
        }

        byte[] data = dataStore.get(key);
        if (data == null) {
            return Optional.empty();
        }
        Map<String, String> metadata = metadataStore.getOrDefault(key, Map.of());
        StorageItemResult result = new StorageItemResult(key, Arrays.copyOf(data, data.length), metadata, data.length);
        if (cacheManager.isHotKey(key)) {
            cacheManager.put(key, result);
        }
        return Optional.of(result);
    }

    @Override
    public boolean delete(String key) {
        dataStore.remove(key);
        metadataStore.remove(key);
        redisTemplate.delete("storage:meta:" + key);
        cacheManager.invalidate(key);
        log.info("Deleted item: key={}", key);
        return true;
    }

    @Override
    public BackupRecord createBackup(String sourcePath, String targetPath) {
        String backupId = UUID.randomUUID().toString();
        BackupRecord record = BackupRecord.builder()
                .backupId(backupId)
                .sourcePath(sourcePath)
                .targetPath(targetPath)
                .status(BackupRecord.BackupStatus.IN_PROGRESS)
                .createdAt(LocalDateTime.now())
                .build();
        backupStore.put(backupId, record);

        try {
            byte[] data = dataStore.get(sourcePath);
            if (data == null) {
                record.setStatus(BackupRecord.BackupStatus.FAILED);
                record.setErrorMessage("Source data not found: " + sourcePath);
                return record;
            }

            String checksum = computeChecksum(data);
            dataStore.put(targetPath, Arrays.copyOf(data, data.length));
            metadataStore.put(targetPath, new HashMap<>(metadataStore.getOrDefault(sourcePath, Map.of())));

            record.setStatus(BackupRecord.BackupStatus.COMPLETED);
            record.setFileSize(data.length);
            record.setChecksum(checksum);
            record.setCompletedAt(LocalDateTime.now());
            log.info("Backup completed: id={}, from={} to={}", backupId, sourcePath, targetPath);
        } catch (Exception e) {
            record.setStatus(BackupRecord.BackupStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.error("Backup failed: id={}", backupId, e);
        }
        return record;
    }

    @Override
    public RecoveryRecord recover(String backupId, String targetPath) {
        BackupRecord backup = backupStore.get(backupId);
        if (backup == null) {
            throw new BusinessException("Backup not found: " + backupId);
        }

        String recoveryId = UUID.randomUUID().toString();
        RecoveryRecord record = RecoveryRecord.builder()
                .recoveryId(recoveryId)
                .backupId(backupId)
                .targetPath(targetPath)
                .status(RecoveryRecord.RecoveryStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .build();
        recoveryStore.put(recoveryId, record);

        try {
            byte[] data = dataStore.get(backup.getTargetPath());
            if (data == null) {
                record.setStatus(RecoveryRecord.RecoveryStatus.FAILED);
                record.setErrorMessage("Backup data not found");
                return record;
            }

            String recoveredChecksum = computeChecksum(data);
            if (!recoveredChecksum.equals(backup.getChecksum())) {
                record.setStatus(RecoveryRecord.RecoveryStatus.FAILED);
                record.setErrorMessage("Checksum mismatch");
                return record;
            }

            dataStore.put(targetPath, Arrays.copyOf(data, data.length));
            metadataStore.put(targetPath, new HashMap<>(metadataStore.getOrDefault(backup.getTargetPath(), Map.of())));

            record.setStatus(RecoveryRecord.RecoveryStatus.COMPLETED);
            record.setCompletedAt(LocalDateTime.now());
            log.info("Recovery completed: id={}, to={}", recoveryId, targetPath);
        } catch (Exception e) {
            record.setStatus(RecoveryRecord.RecoveryStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.error("Recovery failed: id={}", recoveryId, e);
        }
        return record;
    }

    @Override
    public List<BackupRecord> listBackups() {
        return new ArrayList<>(backupStore.values());
    }

    @Override
    public Optional<BackupRecord> getBackup(String backupId) {
        return Optional.ofNullable(backupStore.get(backupId));
    }

    private String computeChecksum(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
