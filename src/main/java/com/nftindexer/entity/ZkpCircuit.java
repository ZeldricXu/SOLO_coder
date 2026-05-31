package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("zkp_circuit")
public class ZkpCircuit extends BaseEntity {

    private String circuitId;
    private String circuitName;
    private String circuitType;
    private String provingKey;
    private String verificationKey;
    private String compiledCircuit;
    private String sourceCode;
    private Integer version;
    private String status;
    private LocalDateTime compiledAt;
    private Map<String, Object> metadata;
}
