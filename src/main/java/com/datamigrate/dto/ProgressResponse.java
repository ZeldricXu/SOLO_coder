package com.datamigrate.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressResponse {
    private ProgressInfo progress;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressInfo {
        private Long totalRecords;
        private Long migratedRecords;
        private Long successRecords;
        private Long failRecords;
        private Integer progressRate;
        private Integer currentBatch;
    }
}
