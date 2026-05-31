package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_record")
public class ApprovalRecord extends BaseEntity {

    private Long instanceId;
    private Long nodeId;
    private String nodeName;
    private Long approverId;
    private String approverName;
    private Integer action;
    private String comment;
    private LocalDateTime operateTime;
    private String signature;
    private Integer strategy;
    private Integer approveOrder;
}
