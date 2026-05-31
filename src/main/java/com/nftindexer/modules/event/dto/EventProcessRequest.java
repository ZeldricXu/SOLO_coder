package com.nftindexer.modules.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class EventProcessRequest {

    @NotBlank(message = "监听器ID不能为空")
    private String listenerId;

    @NotBlank(message = "交易哈希不能为空")
    private String transactionHash;

    private Integer logIndex;

    private Integer blockNumber;

    private String blockHash;

    private LocalDateTime blockTime;

    @NotBlank(message = "合约地址不能为空")
    private String contractAddress;

    private String eventName;

    private String eventSignature;

    private Map<String, Object> topics;

    private Map<String, Object> decodedData;

    private String rawData;

    private Map<String, Object> metadata;
}
