package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("process_definition")
public class ProcessDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String processKey;
    private String processName;
    private String processDesc;
    private Long formId;
    private String bpmnXml;
    private String processData;
    private Integer version;
    private Integer status;
    private String category;
    private Long creatorId;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String creatorName;
    @TableField(exist = false)
    private String formName;
}
