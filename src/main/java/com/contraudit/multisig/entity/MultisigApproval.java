package com.contraudit.multisig.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_approval")
public class MultisigApproval extends BaseEntity {

    private String proposalId;

    private String signerAddress;

    private String signature;

    private String approvalType;

    private LocalDateTime signedAt;
}
