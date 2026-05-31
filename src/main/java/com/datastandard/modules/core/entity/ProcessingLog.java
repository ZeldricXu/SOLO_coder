package com.datastandard.modules.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("processing_logs")
public class ProcessingLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String logId;

    private String requestId;

    private String dataSource;

    private String datasetName;

    private String templateId;

    private String status;

    private int totalRecords;

    private int successCount;

    private int failedCount;

    private long durationMs;

    private String errorMessage;

    private String metrics;

    private Instant startTime;

    private Instant endTime;

    private Instant createdAt;

    private Integer deleted;
}
