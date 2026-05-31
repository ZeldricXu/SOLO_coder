package com.web3platform.chainindexer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventIndexRequest {

    private String chainId;
    private String contractAddress;
    private String eventSignature;
    private Long fromBlock;
    private Long toBlock;
}
