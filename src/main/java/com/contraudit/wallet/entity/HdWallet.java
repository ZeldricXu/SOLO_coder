package com.contraudit.wallet.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hd_wallet")
public class HdWallet extends BaseEntity {

    private String walletName;

    private String mnemonic;

    private String rootXpub;

    private String rootXpriv;

    private String derivationPath;

    private String chainType;

    private Integer status;
}
