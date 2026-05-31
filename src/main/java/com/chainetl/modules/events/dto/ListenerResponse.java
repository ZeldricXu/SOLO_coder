package com.chainetl.modules.events.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListenerResponse {

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
