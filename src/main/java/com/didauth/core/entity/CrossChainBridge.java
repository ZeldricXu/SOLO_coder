package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cross_chain_bridge")
public class CrossChainBridge extends BaseEntity {

    private String bridgeId;
    private String sourceChain;
    private String targetChain;
    private String assetSymbol;
    private String assetAddress;
    private String bridgeContract;
    private String status;
}
