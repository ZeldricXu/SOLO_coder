package com.datamigrate.dto;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.entity.MappingRule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailResponse {
    private String taskId;
    private String taskName;
    private String sourceType;
    private String sourceHost;
    private Integer sourcePort;
    private String sourceDatabase;
    private String sourceTable;
    private String sourceQuery;
    private String targetType;
    private String targetHost;
    private Integer targetPort;
    private String targetDatabase;
    private String targetTable;
    private String primaryKeyField;
    private Integer batchSize;
    private Integer maxRetryTimes;
    private Boolean autoVerify;
    private TaskStatus status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<MappingRule> mappingRules;
}
