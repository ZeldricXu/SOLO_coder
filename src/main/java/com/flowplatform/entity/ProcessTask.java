package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("process_task")
public class ProcessTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long instanceId;
    private Long processId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private Long assigneeId;
    private String assigneeIds;
    private String status;
    private String action;
    private String comment;
    private LocalDateTime dueDate;
    private LocalDateTime claimTime;
    private LocalDateTime completeTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String assigneeName;
    @TableField(exist = false)
    private String instanceTitle;
    @TableField(exist = false)
    private String processName;
}
