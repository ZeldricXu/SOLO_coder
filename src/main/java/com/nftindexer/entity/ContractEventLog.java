package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract_event_log")
public class ContractEventLog extends BaseEntity {

    private String logId;
    private String listenerId;
    private String chainId;
    private String contractAddress;
    private String transactionHash;
    private Integer logIndex;
    private Integer blockNumber;
    private String blockHash;
    private LocalDateTime blockTime;
    private String eventName;
    private String eventSignature;
    private Map<String, Object> topics;
    private Map<String, Object> decodedData;
    private String rawData;
    private String status;
    private LocalDateTime processedAt;
    private String callbackResponse;
    private Integer callbackAttempts;
    private String errorDetail;
}
