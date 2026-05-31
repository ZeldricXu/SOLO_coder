package com.chainetl.modules.events.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLogResponse {

    private String logId;
    private String chainId;
    private Long blockNumber;
    private String txHash;
    private Integer logIndex;
    private String contractAddress;
    private String eventSignature;
    private List<String> topics;
    private String data;
    private Boolean processed;
    private Instant processedAt;
}
