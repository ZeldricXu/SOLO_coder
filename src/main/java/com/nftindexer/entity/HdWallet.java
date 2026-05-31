package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hd_wallet")
public class HdWallet extends BaseEntity {

    private String walletId;
    private String name;
    private String mnemonic;
    private String passphrase;
    private String rootPath;
    private String chainCode;
    private String publicKey;
    private String fingerprint;
    private String createdBy;
    private Map<String, Object> metadata;
}
