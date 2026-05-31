package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.device.platform.common.EntityStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inference_task")
public class InferenceTask extends BaseEntity {
    private String taskId;
    private String modelId;
    private String deviceId;
    private EntityStatus status;
    private String inputData;
    private String outputData;
    private Double confidence;
    private Long inferenceTimeMs;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private Integer priority;
}
