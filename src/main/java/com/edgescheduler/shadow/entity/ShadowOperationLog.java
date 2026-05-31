package com.edgescheduler.shadow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "shadow_operation_log", autoResultMap = true)
public class ShadowOperationLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String logId;
    private String deviceKey;
    private String operationType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> beforeState;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> afterState;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> changeSet;

    private String operator;
    private Integer version;

    public interface OperationType {
        String DESIRED_UPDATE = "desired_update";
        String REPORTED_UPDATE = "reported_update";
        String SYNC = "sync";
        String MERGE = "merge";
        String DELETE = "delete";
    }
}
