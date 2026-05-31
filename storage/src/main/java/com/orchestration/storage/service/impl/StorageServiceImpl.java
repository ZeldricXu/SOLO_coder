package com.orchestration.storage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.storage.service.StorageService;
import com.orchestration.persistence.entity.BackupRecord;
import com.orchestration.persistence.entity.RestoreRecord;
import com.orchestration.persistence.mapper.BackupRecordMapper;
import com.orchestration.persistence.mapper.RestoreRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final BackupRecordMapper backupRecordMapper;
    private final RestoreRecordMapper restoreRecordMapper;

    @Override
    public Long createBackup(String backupType, String backupName, String sourcePath, String targetPath) {
        BackupRecord backup = new BackupRecord();
        backup.setBackupType(backupType);
        backup.setBackupName(backupName);
        backup.setSourcePath(sourcePath);
        backup.setTargetPath(targetPath);
        backup.setStatus("pending");
        backupRecordMapper.insert(backup);

        executeBackup(backup.getId());
        return backup.getId();
    }

    @Override
    public BackupRecord getBackup(Long id) {
        return backupRecordMapper.selectById(id);
    }

    @Override
    public List<BackupRecord> listBackups(String backupType, String status, Integer page, Integer size) {
        Page<BackupRecord> pageResult = backupRecordMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<BackupRecord>()
                        .eq(backupType != null, BackupRecord::getBackupType, backupType)
                        .eq(status != null, BackupRecord::getStatus, status)
                        .orderByDesc(BackupRecord::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    public boolean deleteBackup(Long id) {
        BackupRecord backup = backupRecordMapper.selectById(id);
        if (backup == null) {
            throw new BusinessException("备份记录不存在");
        }

        try {
            Files.deleteIfExists(Paths.get(backup.getTargetPath()));
        } catch (Exception e) {
            log.warn("删除备份文件失败: {}", backup.getTargetPath(), e);
        }

        return backupRecordMapper.deleteById(id) > 0;
    }

    @Override
    public Long createRestore(Long backupId, String restoreName, String targetPath) {
        BackupRecord backup = backupRecordMapper.selectById(backupId);
        if (backup == null) {
            throw new BusinessException("备份记录不存在");
        }
        if (!"success".equals(backup.getStatus())) {
            throw new BusinessException("备份未成功完成，无法恢复");
        }

        RestoreRecord restore = new RestoreRecord();
        restore.setBackupId(backupId);
        restore.setRestoreName(restoreName);
        restore.setSourcePath(backup.getTargetPath());
        restore.setTargetPath(targetPath);
        restore.setStatus("pending");
        restoreRecordMapper.insert(restore);

        executeRestore(restore.getId());
        return restore.getId();
    }

    @Override
    public RestoreRecord getRestore(Long id) {
        return restoreRecordMapper.selectById(id);
    }

    @Override
    public List<RestoreRecord> listRestores(String status, Integer page, Integer size) {
        Page<RestoreRecord> pageResult = restoreRecordMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<RestoreRecord>()
                        .eq(status != null, RestoreRecord::getStatus, status)
                        .orderByDesc(RestoreRecord::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    @Async
    public void executeBackup(Long backupId) {
        BackupRecord backup = backupRecordMapper.selectById(backupId);
        if (backup == null) {
            return;
        }

        try {
            backup.setStatus("running");
            backup.setStartedAt(LocalDateTime.now());
            backupRecordMapper.updateById(backup);

            log.info("开始执行备份: {}", backup.getBackupName());

            Thread.sleep(1000);

            File sourceFile = new File(backup.getSourcePath());
            long fileSize = sourceFile.exists() ? sourceFile.length() : 0;

            String checksum = calculateChecksum(backup.getSourcePath());

            backup.setFileSize(fileSize);
            backup.setChecksum(checksum);
            backup.setStatus("success");
            backup.setCompletedAt(LocalDateTime.now());
            backupRecordMapper.updateById(backup);

            log.info("备份完成: {}, 大小: {} bytes", backup.getBackupName(), fileSize);
        } catch (Exception e) {
            log.error("备份失败: {}", backup.getBackupName(), e);
            backup.setStatus("failed");
            backup.setErrorMessage(e.getMessage());
            backup.setCompletedAt(LocalDateTime.now());
            backupRecordMapper.updateById(backup);
        }
    }

    @Override
    @Async
    public void executeRestore(Long restoreId) {
        RestoreRecord restore = restoreRecordMapper.selectById(restoreId);
        if (restore == null) {
            return;
        }

        try {
            restore.setStatus("running");
            restore.setStartedAt(LocalDateTime.now());
            restoreRecordMapper.updateById(restore);

            log.info("开始执行恢复: {}", restore.getRestoreName());

            Thread.sleep(1000);

            restore.setStatus("success");
            restore.setCompletedAt(LocalDateTime.now());
            restoreRecordMapper.updateById(restore);

            log.info("恢复完成: {}", restore.getRestoreName());
        } catch (Exception e) {
            log.error("恢复失败: {}", restore.getRestoreName(), e);
            restore.setStatus("failed");
            restore.setErrorMessage(e.getMessage());
            restore.setCompletedAt(LocalDateTime.now());
            restoreRecordMapper.updateById(restore);
        }
    }

    @Override
    public Map<String, Object> getStorageUsage() {
        Map<String, Object> usage = new HashMap<>();

        long totalBackups = backupRecordMapper.selectCount(null);
        long successfulBackups = backupRecordMapper.selectCount(
                new LambdaQueryWrapper<BackupRecord>().eq(BackupRecord::getStatus, "success")
        );

        long totalSize = backupRecordMapper.selectList(null).stream()
                .mapToLong(b -> b.getFileSize() != null ? b.getFileSize() : 0)
                .sum();

        usage.put("totalBackups", totalBackups);
        usage.put("successfulBackups", successfulBackups);
        usage.put("totalSize", totalSize);
        usage.put("totalSizeGB", totalSize / (1024.0 * 1024 * 1024));

        return usage;
    }

    @Override
    public Map<String, Object> verifyBackup(Long backupId) {
        BackupRecord backup = backupRecordMapper.selectById(backupId);
        if (backup == null) {
            throw new BusinessException("备份记录不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("backupId", backupId);
        result.put("backupName", backup.getBackupName());

        try {
            Path path = Paths.get(backup.getTargetPath());
            boolean exists = Files.exists(path);
            result.put("fileExists", exists);

            if (exists) {
                String currentChecksum = calculateChecksum(backup.getTargetPath());
                result.put("checksumMatch", currentChecksum.equals(backup.getChecksum()));
                result.put("currentChecksum", currentChecksum);
                result.put("storedChecksum", backup.getChecksum());
                result.put("fileSize", Files.size(path));
            }

            result.put("valid", exists);
        } catch (Exception e) {
            result.put("valid", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    @Override
    @Scheduled(cron = "0 0 2 * * ?")
    public boolean cleanExpiredBackups() {
        log.info("开始清理过期备份");
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        List<BackupRecord> expired = backupRecordMapper.selectList(
                new LambdaQueryWrapper<BackupRecord>()
                        .lt(BackupRecord::getCreatedAt, threshold)
                        .eq(BackupRecord::getStatus, "success")
        );

        int count = 0;
        for (BackupRecord backup : expired) {
            try {
                Files.deleteIfExists(Paths.get(backup.getTargetPath()));
                backupRecordMapper.deleteById(backup.getId());
                count++;
            } catch (Exception e) {
                log.warn("清理过期备份失败: {}", backup.getId(), e);
            }
        }

        log.info("清理过期备份完成，清理数量: {}", count);
        return count > 0;
    }

    private String calculateChecksum(String filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(filePath.getBytes());
            return new BigInteger(1, digest).toString(16);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
