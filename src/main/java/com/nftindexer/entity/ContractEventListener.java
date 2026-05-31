package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("contract_event_listener")
public class ContractEventListener extends BaseEntity {

    private String listenerId;
    private String chainId;
    private String contractAddress;
    private String eventName;
    private String eventSignature;
    private String abi;
    private String callbackUrl;
    private String callbackType;
    private String filterTopics;
    private Integer fromBlock;
    private Integer toBlock;
    private String status;
    private Integer lastProcessedBlock;
    private LocalDateTime lastProcessedAt;
    private Map<String, Object> config;
}
