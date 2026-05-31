package com.contraudit.zkp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("zkp_verification")
public class ZkpVerification extends BaseEntity {

    private String verificationId;

    private String circuitId;

    private String proofData;

    private String publicInputs;

    private String verifierAddress;

    private String status;

    private Integer verifyResult;

    private Long verifyTime;

    private String errorMessage;

    private LocalDateTime verifiedAt;
}
