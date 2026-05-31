package com.datastandard.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationResult {

    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private List<String> successIds;
    private List<FailedItem> failedItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {
        private String id;
        private String errorMessage;
        private String errorCode;
    }
}
