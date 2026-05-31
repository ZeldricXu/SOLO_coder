package com.tracetopology.api.service;

import com.tracetopology.domain.storage.StoredFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface StorageService {

    StoredFile storeFile(String bucket, String path, byte[] content, String fileName);

    StoredFile getFile(String bucket, String path);

    void deleteFile(String bucket, String path);

    List<StoredFile> listFiles(String bucket, String prefix);

    void setLifecyclePolicy(String bucket, List<Map<String, Object>> rules);

    int cleanupExpiredFiles();

    Map<String, Object> getStorageUsage(String bucket);

    Map<String, Object> triggerAutoScale();

    String upload(String fileName, InputStream content, Map<String, String> metadata);

    InputStream download(String fileId);

    boolean delete(String fileId);

    String getFileUrl(String fileId, Duration expiry);

    Map<String, Object> getFileInfo(String fileId);

    void setLifecyclePolicy(String fileId, Duration retentionPeriod);

    boolean archive(String fileId);

    boolean restore(String fileId);
}
