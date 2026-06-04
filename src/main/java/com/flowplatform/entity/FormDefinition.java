package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("form_definition")
public class FormDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String formKey;
    private String formName;
    private String formDesc;
    private String formSchema;
    private Integer version;
    private Integer status;
    private String category;
    private Long creatorId;
    private String deptIds;
    private String roleIds;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String creatorName;
}
