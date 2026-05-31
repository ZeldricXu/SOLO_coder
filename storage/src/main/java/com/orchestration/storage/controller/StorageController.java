package com.orchestration.storage.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.persistence.entity.BackupRecord;
import com.orchestration.persistence.entity.RestoreRecord;
import com.orchestration.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/backups")
    public Result<Long> createBackup(
            @RequestParam String backupType,
            @RequestParam String backupName,
            @RequestParam String sourcePath,
            @RequestParam String targetPath) {
        return Result.success(storageService.createBackup(backupType, backupName, sourcePath, targetPath));
    }

    @GetMapping("/backups/{id}")
    public Result<BackupRecord> getBackup(@PathVariable Long id) {
        return Result.success(storageService.getBackup(id));
    }

    @GetMapping("/backups")
    public Result<List<BackupRecord>> listBackups(
            @RequestParam(required = false) String backupType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(storageService.listBackups(backupType, status, page, size));
    }

    @DeleteMapping("/backups/{id}")
    public Result<Boolean> deleteBackup(@PathVariable Long id) {
        return Result.success(storageService.deleteBackup(id));
    }

    @PostMapping("/restores")
    public Result<Long> createRestore(
            @RequestParam Long backupId,
            @RequestParam String restoreName,
            @RequestParam String targetPath) {
        return Result.success(storageService.createRestore(backupId, restoreName, targetPath));
    }

    @GetMapping("/restores/{id}")
    public Result<RestoreRecord> getRestore(@PathVariable Long id) {
        return Result.success(storageService.getRestore(id));
    }

    @GetMapping("/restores")
    public Result<List<RestoreRecord>> listRestores(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(storageService.listRestores(status, page, size));
    }

    @PostMapping("/backups/{id}/execute")
    public Result<Void> executeBackup(@PathVariable Long id) {
        storageService.executeBackup(id);
        return Result.success();
    }

    @PostMapping("/restores/{id}/execute")
    public Result<Void> executeRestore(@PathVariable Long id) {
        storageService.executeRestore(id);
        return Result.success();
    }

    @GetMapping("/usage")
    public Result<Map<String, Object>> getStorageUsage() {
        return Result.success(storageService.getStorageUsage());
    }

    @GetMapping("/backups/{id}/verify")
    public Result<Map<String, Object>> verifyBackup(@PathVariable Long id) {
        return Result.success(storageService.verifyBackup(id));
    }

    @PostMapping("/backups/clean")
    public Result<Boolean> cleanExpiredBackups() {
        return Result.success(storageService.cleanExpiredBackups());
    }
}
