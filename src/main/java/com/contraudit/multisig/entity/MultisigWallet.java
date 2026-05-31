package com.contraudit.multisig.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_wallet")
public class MultisigWallet extends BaseEntity {

    private String walletName;

    private String chainType;

    private String walletAddress;

    private Integer threshold;

    private Integer totalSigners;

    private Integer status;
}
