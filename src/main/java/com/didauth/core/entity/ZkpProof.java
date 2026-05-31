package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_zkp_proof")
public class ZkpProof extends BaseEntity {

    private String proofId;
    private String circuitId;
    private String proofData;
    private String publicInputs;
    private String verifyResult;
    private Long verifyTimeMs;
    private String errorMessage;
    private String status;
}
