package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_process_instance")
public class ProcessInstance extends BaseEntity {

    private Long definitionId;
    private String processCode;
    private String processName;
    private Long businessId;
    private String businessType;
    private Integer status;
    private Long currentNodeId;
    private String variables;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String remark;
}
