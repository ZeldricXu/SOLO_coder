package com.solocoder.platform.storage.service;

import com.solocoder.platform.storage.model.BackupRecord;
import com.solocoder.platform.storage.model.RecoveryRecord;

import java.util.List;
import java.util.Optional;

public interface StorageService {

    StorageItemResult put(String key, byte[] data, java.util.Map<String, String> metadata);

    Optional<StorageItemResult> get(String key);

    boolean delete(String key);

    BackupRecord createBackup(String sourcePath, String targetPath);

    RecoveryRecord recover(String backupId, String targetPath);

    List<BackupRecord> listBackups();

    Optional<BackupRecord> getBackup(String backupId);

    record StorageItemResult(String key, byte[] data, java.util.Map<String, String> metadata, long size) {}
}
