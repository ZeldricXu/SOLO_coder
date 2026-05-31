package com.datastandard.modules.logging.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoggerStatusResponse {

    private String packagePath;

    private String currentLevel;

    private String configuredLevel;

    private String effectiveLevel;

    private Instant lastModified;

    private String modifiedBy;

    private boolean isTemporary;

    private Instant expiresAt;

    private boolean persistent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchResponse {
        private List<LoggerStatusResponse> loggers;
        private int totalCount;
        private String filter;
    }
}
