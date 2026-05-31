package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("multi_sig_wallet")
public class MultiSigWallet extends BaseEntity {

    private String walletId;
    private String name;
    private String chainId;
    private String walletAddress;
    private Integer threshold;
    private Integer totalSigners;
    private String signers;
    private String status;
    private String createdBy;
    private Map<String, Object> metadata;
}
