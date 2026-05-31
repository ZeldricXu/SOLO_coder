package com.contractai.flow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_edge")
public class WorkflowEdge extends TenantBaseEntity {

    @TableField("flow_id")
    private Long flowId;

    @TableField("edge_id")
    private String edgeId;

    @TableField("source_node_id")
    private String sourceNodeId;

    @TableField("target_node_id")
    private String targetNodeId;

    @TableField("edge_name")
    private String edgeName;

    @TableField("condition_expression")
    private String conditionExpression;

    @TableField("priority")
    private Integer priority;
}
