package com.web3platform.persistence.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("multisig_signature")
public class MultisigSignature {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("proposal_id")
    private Long proposalId;

    @TableField("signer_address")
    private String signerAddress;

    @TableField("signature")
    private String signature;

    @TableField("signed_at")
    private LocalDateTime signedAt;
}
