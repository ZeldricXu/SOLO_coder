package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract_event_log")
public class ContractEventLog extends BaseEntity {

    private String eventId;
    private String chainType;
    private Long blockNumber;
    private String txHash;
    private Integer logIndex;
    private String contractAddress;
    private String eventData;
    private String decodedData;
    private String callbackStatus;
    private String callbackResponse;
    private Long timestamp;
}
