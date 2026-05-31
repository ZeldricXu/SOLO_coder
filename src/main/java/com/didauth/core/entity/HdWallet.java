package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_hd_wallet")
public class HdWallet extends BaseEntity {

    private String walletId;
    private String chainType;
    private String derivationPath;
    private String address;
    private String publicKey;
    private String privateKeyEncrypted;
    private String label;
    private String tags;
    private String userId;
    private String status;
}
