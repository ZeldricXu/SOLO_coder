package com.flowplatform.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("notification_preference")
public class NotificationPreference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer enableInApp;
    private Integer enableEmail;
    private Integer enableWechat;
    private Integer taskArrival;
    private Integer taskTimeout;
    private Integer taskComplete;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
