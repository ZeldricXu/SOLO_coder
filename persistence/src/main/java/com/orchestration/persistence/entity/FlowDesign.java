package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_design")
public class FlowDesign extends TenantEntity {

    private String designName;

    private String designCode;

    private String flowType;

    private String description;

    private String nodeDefinitions;

    private String edgeDefinitions;

    private String designData;

    private String status;

    private Integer version;
}
