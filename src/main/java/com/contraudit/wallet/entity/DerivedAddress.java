package com.contraudit.wallet.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derived_address")
public class DerivedAddress extends BaseEntity {

    private String walletId;

    private String address;

    private Integer addressIndex;

    private String derivationPath;

    private String publicKey;

    private String chainType;

    private Integer status;
}
