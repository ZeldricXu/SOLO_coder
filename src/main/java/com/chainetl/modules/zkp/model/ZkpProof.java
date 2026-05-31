package com.chainetl.modules.zkp.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chainetl.common.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "zkp_proofs", autoResultMap = true)
public class ZkpProof {

    @TableId(type = IdType.INPUT)
    private String proofId;

    private String circuitId;

    private String proofData;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> publicInputs;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> verificationKey;

    private Boolean verificationResult;

    private Instant verifiedAt;

    private Instant createdAt;

    private String errorMessage;
}
