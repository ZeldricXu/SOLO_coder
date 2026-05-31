package com.chain.infrastructure.multisig.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CreateProposalRequest {

    private String walletId;

    private String proposer;

    private String title;

    private String description;

    private String txData;

    private Map<String, Object> txParams;

    private LocalDateTime expiresAt;
}
