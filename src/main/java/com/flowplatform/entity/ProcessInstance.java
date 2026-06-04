package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("process_instance")
public class ProcessInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long processId;
    private Long formId;
    private String title;
    private Long initiatorId;
    private String status;
    private String formData;
    private String currentNodes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String processName;
    @TableField(exist = false)
    private String initiatorName;
    @TableField(exist = false)
    private String formName;
}
