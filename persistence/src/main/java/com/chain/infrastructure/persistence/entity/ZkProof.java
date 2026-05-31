package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("zk_proof")
public class ZkProof extends BaseEntity {

    private String proofId;

    private String circuitId;

    private String schemeType;

    private String proofData;

    private String publicInputs;

    private String verificationKey;

    private Boolean verified;

    private LocalDateTime verifiedAt;

    private String verificationResult;
}
