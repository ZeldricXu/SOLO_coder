package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_process")
public class ApprovalProcess extends BaseEntity {

    private String processCode;
    private String processName;
    private String businessType;
    private String nodeConfig;
    private String lineConfig;
    private Integer strategy;
    private Integer enabled;
    private String version;
    private String remark;
}
