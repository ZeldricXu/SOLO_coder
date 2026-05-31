package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("derived_address")
public class DerivedAddress extends BaseEntity {

    private String addressId;
    private String walletId;
    private String address;
    private String derivationPath;
    private Integer index;
    private String publicKey;
    private String privateKey;
    private String chainId;
    private String status;
    private String tags;
    private String label;
    private Map<String, Object> metadata;
}
