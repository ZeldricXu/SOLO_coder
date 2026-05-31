package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.device.platform.common.EntityStatus;
import com.device.platform.common.RunPhase;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstance extends BaseEntity {
    private String runId;
    private String entityId;
    private String entityType;
    private RunPhase phase;
    private EntityStatus status;
    private Double progress;
    private String configSnapshot;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private String traceId;
    private String resultData;
}
