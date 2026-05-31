package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_instance")
public class ApprovalInstance extends BaseEntity {

    private String processId;
    private String processName;
    private String businessType;
    private Long businessId;
    private String title;
    private String content;
    private Long initiatorId;
    private String initiatorName;
    private Integer status;
    private Integer currentNodeId;
    private String currentNodeName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String variables;
    private String remark;
}
