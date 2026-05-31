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
public class RecoveryRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String recoveryId;
    private String backupId;
    private String targetPath;
    private RecoveryStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;

    public enum RecoveryStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
}
