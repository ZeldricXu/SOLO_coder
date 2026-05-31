package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_process_definition")
public class ProcessDefinition extends BaseEntity {

    private String processCode;
    private String processName;
    private String processType;
    private String nodes;
    private String lines;
    private String description;
    private Integer version;
    private Integer enabled;
    private String category;
}
