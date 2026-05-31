package com.tsdbproxy.metadata.crawler.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CrawlResult {
    private Long datasourceId;
    private String schemaName;
    private String tableName;
    private String tableComment;
    private Long rowCount;
    private Long sizeBytes;
    private String sampleData;
    private List<ColumnInfo> columns;
    private String status;
    private LocalDateTime crawlTime;
    private String errorMessage;

    @Data
    @Builder
    public static class ColumnInfo {
        private String columnName;
        private String columnType;
        private String columnComment;
        private Integer isNullable;
        private Integer isPrimaryKey;
        private Integer ordinalPosition;
        private String minValue;
        private String maxValue;
        private Long distinctCount;
        private Long nullCount;
    }
}
