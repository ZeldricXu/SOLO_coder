package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_metadata_schema")
public class SysMetadataSchema extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String schemaId;

    private String sourceId;

    private String databaseName;

    private String tableName;

    private String description;

    private List<ColumnInfo> columns;

    private Map<String, Object> statistics;

    private List<Map<String, Object>> sampleData;

    private Long rowCount;

    private Long dataSize;

    private LocalDateTime collectedAt;

    @Data
    public static class ColumnInfo {
        private String name;
        private String type;
        private Integer length;
        private Integer precision;
        private Integer scale;
        private Boolean nullable;
        private String defaultValue;
        private String comment;
        private Boolean primaryKey;
        private Boolean autoIncrement;
    }
}
