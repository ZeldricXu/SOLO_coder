package com.chainetl.modules.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRetrieveResponse {

    private String batchId;
    private Integer totalItems;
    private Integer successCount;
    private Integer failedCount;
    private List<RetrieveResultItem> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrieveResultItem {
        private String recordId;
        private String status;
        private String storageType;
        private String contentHash;
        private String contentUrl;
        private String content;
        private String pinStatus;
        private Long size;
        private Instant createdAt;
        private Map<String, Object> metadata;
        private String errorMessage;
    }
}
