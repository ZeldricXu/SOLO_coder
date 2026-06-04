package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("approval_comment")
public class ApprovalComment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long instanceId;
    private Long userId;
    private String action;
    private String comment;
    private String signatureUrl;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private String userName;
}
