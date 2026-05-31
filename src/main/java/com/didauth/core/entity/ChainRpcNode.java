package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_chain_rpc_node")
public class ChainRpcNode extends BaseEntity {

    private String chainType;
    private String rpcUrl;
    private Long chainId;
    private String name;
    private Integer priority;
    private Boolean isActive;
    private String healthStatus;
    private LocalDateTime lastCheckAt;
    private Integer latencyMs;
}
