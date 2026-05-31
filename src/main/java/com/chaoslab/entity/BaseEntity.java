package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class BaseEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    @TableField(typeHandler = JacksonTypeHandler.class, exist = false)
    private Map<String, Object> additionalProperties = new HashMap<>();

    public Object getAdditionalProperty(String key) {
        return additionalProperties.get(key);
    }

    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }
}
