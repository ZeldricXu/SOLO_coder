package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_process_node")
public class ProcessNode extends BaseEntity {

    private Long definitionId;
    private String nodeCode;
    private String nodeName;
    private Integer nodeType;
    private String nodeConfig;
    private String formConfig;
    private Integer sortOrder;
    private String position;
    private String style;
}
