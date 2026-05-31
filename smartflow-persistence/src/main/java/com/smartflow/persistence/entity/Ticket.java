package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ticket")
public class Ticket extends BaseEntity {

    private String title;
    private String description;
    private String ticketType;
    private Integer priority;
    private Integer status;
    private Long assigneeId;
    private String assigneeName;
    private Long reporterId;
    private String reporterName;
    private String requiredSkills;
    private Long slaId;
    private Long approvalId;
    private String attachments;
    private String tags;
    private String remark;
}
