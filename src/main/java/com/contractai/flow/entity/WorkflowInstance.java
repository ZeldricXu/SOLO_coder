package com.contractai.flow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_instance")
public class WorkflowInstance extends TenantBaseEntity {

    @TableField("instance_no")
    private String instanceNo;

    @TableField("flow_id")
    private Long flowId;

    @TableField("business_key")
    private String businessKey;

    @TableField("status")
    private String status;

    @TableField("current_node_id")
    private String currentNodeId;

    @TableField(value = "variables", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> variables;

    @TableField(value = "form_data", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> formData;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;

    @TableField("started_by")
    private Long startedBy;

    @TableField("error_detail")
    private String errorDetail;
}
