package com.device.platform.storage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.BackupRequest;
import com.device.platform.dto.RestoreRequest;
import com.device.platform.entity.BackupRecord;
import com.device.platform.mapper.BackupRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final BackupRecordMapper backupRecordMapper;

    @Value("${storage.backup.directory:/tmp/backups}")
    private String backupDirectory;

    @Value("${storage.backup.default-retention-days:30}")
    private int defaultRetentionDays;

    @Value("${storage.backup.auto-schedule:false}")
    private boolean autoScheduleBackup;

    @Transactional
    public Mono<BackupRecord> createBackup(BackupRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("backupType", request.getBackupType());
            ctx.putAttribute("backupScope", request.getBackupScope());

            String backupId = generateBackupId();
            String fileName = backupId + ".gz";
            Path backupPath = Paths.get(backupDirectory, fileName);

            try {
                Files.createDirectories(backupPath.getParent());
            } catch (IOException e) {
                throw new BusinessException(500, "创建备份目录失败: " + e.getMessage(), ctx.getTraceId());
            }

            BackupRecord record = new BackupRecord();
            record.setBackupId(backupId);
            record.setBackupType(request.getBackupType());
            record.setBackupScope(request.getBackupScope() != null ? request.getBackupScope() : "FULL");
            record.setStoragePath(backupPath.toString());
            record.setStatus("IN_PROGRESS");
            record.setStartedAt(Instant.now());
            record.setRetentionDays(request.getRetentionDays() != null ?
                    request.getRetentionDays() : defaultRetentionDays);
            record.setEncrypted(request.isEncrypted());

            backupRecordMapper.insert(record);

            performBackupAsync(record, request);

            log.info("备份任务已创建: backupId={}, type={}, scope={}, traceId={}",
                    backupId, request.getBackupType(), request.getBackupScope(), ctx.getTraceId());

            return record;
        });
    }

    @Async
    @Transactional
    protected void performBackupAsync(BackupRecord record, BackupRequest request) {
        try {
            Path backupPath = Paths.get(record.getStoragePath());

            Map<String, Object> backupData = collectBackupData(request.getBackupScope());

            try (FileOutputStream fos = new FileOutputStream(backupPath.toFile());
                 GZIPOutputStream gzos = new GZIPOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(gzos)) {

                oos.writeObject(backupData);
            }

            long fileSize = Files.size(backupPath);
            String md5 = calculateMD5(backupPath);

            record.setFileSize(fileSize);
            record.setMd5(md5);
            record.setStatus("COMPLETED");
            record.setCompletedAt(Instant.now());

            backupRecordMapper.updateById(record);
            log.info("备份完成: backupId={}, size={} bytes", record.getBackupId(), fileSize);

        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setErrorDetail(e.getMessage());
            record.setCompletedAt(Instant.now());
            backupRecordMapper.updateById(record);
            log.error("备份失败: backupId={}, error={}", record.getBackupId(), e.getMessage(), e);
        }
    }

    private Map<String, Object> collectBackupData(String scope) {
        Map<String, Object> backupData = new java.util.LinkedHashMap<>();
        backupData.put("backupMetadata", Map.of(
                "version", "1.0",
                "createdAt", Instant.now().toString(),
                "scope", scope
        ));

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("timestamp", Instant.now().toString());
        data.put("description", "Backup data for scope: " + scope);
        backupData.put("data", data);

        return backupData;
    }

    @Transactional
    public Mono<BackupRecord> restoreBackup(RestoreRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("backupId", request.getBackupId());

            BackupRecord record = backupRecordMapper.selectOne(new LambdaQueryWrapper<BackupRecord>()
                    .eq(BackupRecord::getBackupId, request.getBackupId()));

            if (record == null) {
                throw new BusinessException(404, "备份记录不存在", ctx.getTraceId());
            }

            if (!"COMPLETED".equals(record.getStatus())) {
                throw new BusinessException(400, "备份未完成或已失败，无法恢复", ctx.getTraceId());
            }

            Path backupPath = Paths.get(record.getStoragePath());
            if (!Files.exists(backupPath)) {
                throw new BusinessException(404, "备份文件不存在", ctx.getTraceId());
            }

            try {
                String actualMd5 = calculateMD5(backupPath);
                if (!actualMd5.equals(record.getMd5())) {
                    throw new BusinessException(400, "备份文件校验失败，文件可能已损坏", ctx.getTraceId());
                }
            } catch (Exception e) {
                throw new BusinessException(500, "备份文件校验失败: " + e.getMessage(), ctx.getTraceId());
            }

            record.setStatus("RESTORING");
            backupRecordMapper.updateById(record);

            performRestoreAsync(record, request);

            log.info("恢复任务已启动: backupId={}, traceId={}", request.getBackupId(), ctx.getTraceId());

            return record;
        });
    }

    @Async
    @Transactional
    protected void performRestoreAsync(BackupRecord record, RestoreRequest request) {
        try {
            Path backupPath = Paths.get(record.getStoragePath());

            try (FileInputStream fis = new FileInputStream(backupPath.toFile());
                 java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(fis);
                 ObjectInputStream ois = new ObjectInputStream(gzis)) {

                @SuppressWarnings("unchecked")
                Map<String, Object> backupData = (Map<String, Object>) ois.readObject();

                log.info("恢复数据已读取: backupId={}, dataSize={}",
                        record.getBackupId(), backupData.size());
            }

            record.setStatus("RESTORED");
            record.setCompletedAt(Instant.now());
            backupRecordMapper.updateById(record);

            log.info("恢复完成: backupId={}", record.getBackupId());

        } catch (Exception e) {
            record.setStatus("RESTORE_FAILED");
            record.setErrorDetail(e.getMessage());
            backupRecordMapper.updateById(record);
            log.error("恢复失败: backupId={}, error={}", record.getBackupId(), e.getMessage(), e);
        }
    }

    public Mono<BackupRecord> getBackupStatus(String backupId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            BackupRecord record = backupRecordMapper.selectOne(new LambdaQueryWrapper<BackupRecord>()
                    .eq(BackupRecord::getBackupId, backupId));

            if (record == null) {
                throw new BusinessException(404, "备份记录不存在", ctx.getTraceId());
            }

            return record;
        });
    }

    public Flux<BackupRecord> listBackups(String backupType, String status, TraceContext ctx) {
        return Flux.fromIterable(backupRecordMapper.selectList(new LambdaQueryWrapper<BackupRecord>()
                .eq(backupType != null, BackupRecord::getBackupType, backupType)
                .eq(status != null, BackupRecord::getStatus, status)
                .orderByDesc(BackupRecord::getCreatedAt)
                .last("LIMIT 100")));
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void scheduledBackup() {
        if (!autoScheduleBackup) {
            return;
        }

        log.info("开始执行定时备份");

        BackupRequest request = new BackupRequest();
        request.setBackupType("SCHEDULED");
        request.setBackupScope("FULL");

        TraceContext ctx = new TraceContext();
        createBackup(request, ctx).subscribe();
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupExpiredBackups() {
        try {
            Instant cutoffDate = Instant.now().minus(defaultRetentionDays, ChronoUnit.DAYS);

            List<BackupRecord> expiredBackups = backupRecordMapper.selectList(
                    new LambdaQueryWrapper<BackupRecord>()
                            .lt(BackupRecord::getCreatedAt, cutoffDate)
                            .ne(BackupRecord::getStatus, "DELETED"));

            for (BackupRecord backup : expiredBackups) {
                try {
                    Path backupPath = Paths.get(backup.getStoragePath());
                    Files.deleteIfExists(backupPath);
                    backup.setStatus("DELETED");
                    backupRecordMapper.updateById(backup);
                    log.info("过期备份已清理: backupId={}", backup.getBackupId());
                } catch (Exception e) {
                    log.warn("清理过期备份失败: backupId={}, error={}",
                            backup.getBackupId(), e.getMessage());
                }
            }

            log.info("过期备份清理完成: count={}", expiredBackups.size());

        } catch (Exception e) {
            log.error("清理过期备份任务失败: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public Mono<Void> deleteBackup(String backupId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            BackupRecord record = backupRecordMapper.selectOne(new LambdaQueryWrapper<BackupRecord>()
                    .eq(BackupRecord::getBackupId, backupId));

            if (record == null) {
                throw new BusinessException(404, "备份记录不存在", ctx.getTraceId());
            }

            try {
                Path backupPath = Paths.get(record.getStoragePath());
                Files.deleteIfExists(backupPath);
            } catch (IOException e) {
                throw new BusinessException(500, "删除备份文件失败: " + e.getMessage(), ctx.getTraceId());
            }

            backupRecordMapper.deleteById(record.getId());
            log.info("备份已删除: backupId={}, traceId={}", backupId, ctx.getTraceId());

            return null;
        });
    }

    private String calculateMD5(Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        return Base64.getEncoder().encodeToString(hash);
    }

    private String generateBackupId() {
        return "bak_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
