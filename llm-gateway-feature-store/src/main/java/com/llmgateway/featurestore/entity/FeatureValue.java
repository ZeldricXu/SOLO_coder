package com.llmgateway.featurestore.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("feature_value")
public class FeatureValue implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("feature_id")
    private String featureId;

    @TableField("entity_key")
    private String entityKey;

    @TableField(value = "value", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> value;

    @TableField("timestamp_ms")
    private Long timestampMs;

    @TableField("event_time")
    private LocalDateTime eventTime;

    @TableField("source")
    private String source;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
