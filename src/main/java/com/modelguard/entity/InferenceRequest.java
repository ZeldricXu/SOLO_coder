package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "inference_request", autoResultMap = true)
public class InferenceRequest extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;

    private String modelName;

    private String providerId;

    private String routeId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestBody;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responseBody;

    private Integer statusCode;

    private Long latencyMs;

    private String status;

    private String errorMessage;

    private String fallbackFrom;

    private String fallbackTo;

    private Integer retryCount;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String traceId;

    private String userId;
}
