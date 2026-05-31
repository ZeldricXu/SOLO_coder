package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("zkp_proof")
public class ZkpProofEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("proof_id")
    private String proofId;

    @TableField("proof_type")
    private String proofType;

    @TableField("circuit_id")
    private String circuitId;

    @TableField("circuit_version")
    private String circuitVersion;

    @TableField("public_inputs")
    private String publicInputs;

    @TableField("proof_data")
    private String proofData;

    @TableField("verification_key")
    private String verificationKey;

    @TableField("verification_result")
    private Integer verificationResult;

    @TableField("verification_time")
    private Long verificationTime;

    @TableField("verification_error")
    private String verificationError;

    @TableField("verifier")
    private String verifier;

    @TableField("verified_at")
    private LocalDateTime verifiedAt;

    @TableField("chain_verified")
    private Integer chainVerified;

    @TableField("tx_hash")
    private String txHash;

    @TableField("metadata")
    private String metadata;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
