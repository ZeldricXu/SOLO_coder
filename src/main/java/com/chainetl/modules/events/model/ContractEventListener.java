package com.chainetl.modules.events.model;

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
@TableName(value = "contract_event_listeners", autoResultMap = true)
public class ContractEventListener {

    @TableId(type = IdType.INPUT)
    private String listenerId;

    private String chainId;

    private String contractAddress;

    private String eventSignature;

    private String callbackUrl;

    private Long startBlock;

    private String status;

    private Long lastProcessedBlock;

    private Instant createdAt;
}
