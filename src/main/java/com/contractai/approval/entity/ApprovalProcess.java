package com.contractai.approval.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_process")
public class ApprovalProcess extends TenantBaseEntity {

    private String processNo;

    private String businessType;

    private String businessId;

    private String title;

    private String status;

    private String approvalStrategy;

    private Integer currentStage;

    private Integer totalStages;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> formData;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> variables;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> approverList;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> ccList;

    private Long startedBy;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime timeoutAt;

    private String finalDecision;

    private String finalComment;

    @TableField(exist = false)
    private List<ApprovalStage> stages;

    @TableField(exist = false)
    private List<ApprovalTask> tasks;

    @TableField(exist = false)
    private com.contractai.skill.entity.Employee starter;
}
