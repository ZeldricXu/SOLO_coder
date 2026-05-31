package com.contractai.flow.entity;

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
@TableName("workflow_definition")
public class WorkflowDefinition extends TenantBaseEntity {

    @TableField("flow_code")
    private String flowCode;

    @TableField("flow_name")
    private String flowName;

    @TableField("version")
    private Integer version;

    @TableField("category")
    private String category;

    @TableField("description")
    private String description;

    @TableField("status")
    private String status;

    @TableField(value = "nodes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> nodes;

    @TableField(value = "edges", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> edges;

    @TableField(value = "variables", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> variables;

    @TableField(value = "form_schema", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> formSchema;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField("published_by")
    private Long publishedBy;
}
