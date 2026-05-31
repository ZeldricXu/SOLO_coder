package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inference_model")
public class InferenceModel extends BaseEntity {
    private String modelId;
    private String modelName;
    private String modelVersion;
    private String modelType;
    private String modelFormat;
    private Long modelSize;
    private String modelUrl;
    private String md5;
    private String inputSchema;
    private String outputSchema;
    private String labels;
    private Integer targetDeviceMemoryMb;
    private Double averageLatencyMs;
    private Double accuracy;
    private boolean active;
    private Instant deployedAt;
}
