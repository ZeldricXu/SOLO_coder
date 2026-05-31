package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_run_instance")
public class SysRunInstance extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String runId;

    private String entityId;

    private String phase;

    private Double progress;

    private Map<String, Object> metrics;

    private Map<String, Object> context;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorDetail;
}
