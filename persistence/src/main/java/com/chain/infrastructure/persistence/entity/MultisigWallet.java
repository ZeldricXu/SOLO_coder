package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multisig_wallet")
public class MultisigWallet extends BaseEntity {

    private String walletId;

    private String chainType;

    private String walletAddress;

    private Integer threshold;

    private String owners;

    private String name;

    private String description;
}
