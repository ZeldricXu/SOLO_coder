package com.contractai.flow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node")
public class WorkflowNode extends TenantBaseEntity {

    @TableField("flow_id")
    private Long flowId;

    @TableField("node_id")
    private String nodeId;

    @TableField("node_name")
    private String nodeName;

    @TableField("node_type")
    private String nodeType;

    @TableField("position_x")
    private Integer positionX;

    @TableField("position_y")
    private Integer positionY;

    @TableField(value = "config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> config;

    @TableField(value = "form_schema", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> formSchema;

    @TableField(value = "listener_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> listenerConfig;
}
