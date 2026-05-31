package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multi_sig_signature")
public class MultiSigSignature extends BaseEntity {

    private String signatureId;
    private String proposalId;
    private String signerAddress;
    private String signature;
    private Integer signatureIndex;
    private String status;
    private LocalDateTime signedAt;
    private String signedBy;
    private String signatureType;
}
