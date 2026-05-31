package com.chainetl.modules.chain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chain_nodes")
public class ChainNode {

    @TableId(type = IdType.INPUT)
    private String nodeId;

    private String chainId;

    private String rpcUrl;

    private String wsUrl;

    private String status;

    private Integer priority;

    private Long latency;

    private Instant lastChecked;

    private Instant createdAt;

    private Instant updatedAt;
}
