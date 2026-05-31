package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sla_notification")
public class SlaNotification extends BaseEntity {

    private Long trackingId;
    private Long relatedId;
    private String relatedType;
    private Integer notificationType;
    private Long recipientId;
    private String recipientName;
    private String content;
    private Integer status;
    private LocalDateTime sentAt;
    private String remark;
}
