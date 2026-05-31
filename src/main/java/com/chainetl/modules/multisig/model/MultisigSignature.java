package com.chainetl.modules.multisig.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("multisig_signatures")
public class MultisigSignature {

    @TableId(type = IdType.INPUT)
    private String signatureId;

    private String proposalId;

    private String signerAddress;

    private String signatureData;

    private Instant signedAt;
}
