package com.edgescheduler.inference.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "inference_task", autoResultMap = true)
public class InferenceTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String modelId;
    private String deviceKey;
    private String taskType;
    private String priority;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputData;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inferenceResult;

    private String status;
    private Double progress;
    private Long inferenceTimeMs;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;

    public interface Status {
        String PENDING = "pending";
        String SCHEDULED = "scheduled";
        String RUNNING = "running";
        String COMPLETED = "completed";
        String FAILED = "failed";
        String CANCELLED = "cancelled";
        String TIMEOUT = "timeout";
    }

    public interface TaskType {
        String BATCH = "batch";
        String REAL_TIME = "realtime";
        String SCHEDULED = "scheduled";
    }

    public interface Priority {
        String LOW = "low";
        String NORMAL = "normal";
        String HIGH = "high";
        String CRITICAL = "critical";
    }
}
