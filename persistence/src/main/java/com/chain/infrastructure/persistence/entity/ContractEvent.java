package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract_event")
public class ContractEvent extends BaseEntity {

    private String eventId;

    private String chainType;

    private Long blockNumber;

    private String txHash;

    private Integer logIndex;

    private String contractAddress;

    private String eventSignature;

    private String eventName;

    private String topics;

    private String data;

    private String decodedData;

    private Boolean processed;

    private LocalDateTime processedAt;
}
