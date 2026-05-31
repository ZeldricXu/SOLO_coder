package com.web3platform.chainindexer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecodedEvent {

    private String eventName;
    private String contractAddress;
    private Map<String, String> params;
    private String txHash;
    private int logIndex;
}
