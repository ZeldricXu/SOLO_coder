package com.orchestration.storage.service;

import com.orchestration.persistence.entity.BackupRecord;
import com.orchestration.persistence.entity.RestoreRecord;
import java.util.List;
import java.util.Map;

public interface StorageService {

    Long createBackup(String backupType, String backupName, String sourcePath, String targetPath);

    BackupRecord getBackup(Long id);

    List<BackupRecord> listBackups(String backupType, String status, Integer page, Integer size);

    boolean deleteBackup(Long id);

    Long createRestore(Long backupId, String restoreName, String targetPath);

    RestoreRecord getRestore(Long id);

    List<RestoreRecord> listRestores(String status, Integer page, Integer size);

    void executeBackup(Long backupId);

    void executeRestore(Long restoreId);

    Map<String, Object> getStorageUsage();

    Map<String, Object> verifyBackup(Long backupId);

    boolean cleanExpiredBackups();
}
