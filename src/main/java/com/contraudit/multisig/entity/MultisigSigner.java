package com.contraudit.multisig.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_signer")
public class MultisigSigner extends BaseEntity {

    private String walletId;

    private String signerAddress;

    private Integer signerIndex;

    private String publicKey;
}
