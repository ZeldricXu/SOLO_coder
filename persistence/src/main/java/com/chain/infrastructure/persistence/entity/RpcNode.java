package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rpc_node")
public class RpcNode extends BaseEntity {

    private String nodeId;

    private String chainType;

    private Integer chainId;

    private String rpcUrl;

    private String wsUrl;

    private String name;

    private Integer priority;

    private Integer weight;

    private Integer maxRetries;

    private Integer timeoutMs;

    private Boolean enabled;

    private String healthStatus;

    private LocalDateTime lastHealthCheck;
}
