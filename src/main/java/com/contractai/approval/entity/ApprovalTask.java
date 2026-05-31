package com.contractai.approval.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_task")
public class ApprovalTask extends TenantBaseEntity {

    private Long processId;

    private Long stageId;

    private Long approverId;

    private String status;

    private String action;

    private String comment;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> signatures;

    private LocalDateTime assignedAt;

    private LocalDateTime actedAt;

    private Long transferredTo;

    private Long delegatedTo;

    @TableField(exist = false)
    private com.contractai.skill.entity.Employee approver;
}
