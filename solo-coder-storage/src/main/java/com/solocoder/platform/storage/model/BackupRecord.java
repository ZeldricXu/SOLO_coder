package com.solocoder.platform.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackupRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String backupId;
    private String sourcePath;
    private String targetPath;
    private BackupStatus status;
    private long fileSize;
    private String checksum;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String errorMessage;

    public enum BackupStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
}
