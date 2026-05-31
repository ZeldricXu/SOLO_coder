package com.contraudit.bridge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bridge_chain")
public class BridgeChain extends BaseEntity {

    private String chainName;

    private Long chainId;

    private String chainType;

    private String rpcUrl;

    private String bridgeContract;

    private Integer confirmations;

    private Integer status;
}
