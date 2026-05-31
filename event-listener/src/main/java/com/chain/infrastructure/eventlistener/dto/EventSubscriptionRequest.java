package com.chain.infrastructure.eventlistener.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class EventSubscriptionRequest {

    private String chainType;

    private String contractAddress;

    private String eventSignature;

    private String eventName;

    private List<String> topics;

    private Long fromBlock;

    private Long toBlock;

    private String callbackUrl;

    private Map<String, Object> callbackMetadata;

    private Boolean active;
}
