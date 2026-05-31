package com.contraudit.zkp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("zkp_circuit")
public class ZkpCircuit extends BaseEntity {

    private String circuitName;

    private String circuitType;

    private String version;

    private String verifyingKey;

    private String provingKeyCid;

    private String circuitCid;

    private String inputSchema;

    private String description;

    private Integer status;
}
