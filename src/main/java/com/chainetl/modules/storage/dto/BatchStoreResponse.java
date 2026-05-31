package com.chainetl.modules.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchStoreResponse {

    private String batchId;
    private String storageType;
    private Integer totalItems;
    private Integer successCount;
    private Integer failedCount;
    private List<BatchResultItem> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchResultItem {
        private Integer index;
        private String status;
        private String recordId;
        private String contentHash;
        private String contentUrl;
        private Long size;
        private String errorMessage;
    }
}
