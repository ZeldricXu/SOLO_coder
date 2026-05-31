package com.chain.infrastructure.eventlistener.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class EventLog {

    private String chainType;

    private Long blockNumber;

    private String blockHash;

    private String txHash;

    private Integer logIndex;

    private String contractAddress;

    private String eventSignature;

    private String eventName;

    private List<String> topics;

    private String data;

    private Map<String, Object> decodedData;

    private Long timestamp;
}
