package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("zkp_proof")
public class ZkpProof extends BaseEntity {

    private String proofId;
    private String circuitId;
    private String circuitName;
    private String proofData;
    private String publicInputs;
    private String verificationKey;
    private Boolean verified;
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime verifiedAt;
    private Long verificationTimeMs;
    private String errorDetail;
    private Map<String, Object> metadata;
}
