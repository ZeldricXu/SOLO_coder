package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_multisig_wallet")
public class MultisigWallet extends BaseEntity {

    private String walletId;
    private String chainType;
    private String address;
    private Integer threshold;
    private Integer signerCount;
    private String signers;
    private String name;
    private String userId;
    private String status;
}
