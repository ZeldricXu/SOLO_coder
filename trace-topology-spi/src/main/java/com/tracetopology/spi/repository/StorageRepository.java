package com.tracetopology.spi.repository;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface StorageRepository {

    String saveFile(String fileName, InputStream content, Map<String, String> metadata);

    Optional<InputStream> getFile(String fileId);

    boolean deleteFile(String fileId);

    String generateFileUrl(String fileId, Duration expiry);

    Map<String, Object> getFileMetadata(String fileId);

    void updateLifecyclePolicy(String fileId, Duration retentionPeriod);

    boolean archiveFile(String fileId);

    boolean restoreFile(String fileId);

    boolean isFileArchived(String fileId);
}
