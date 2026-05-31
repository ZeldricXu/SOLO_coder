package com.chainetl.modules.multisig.dto;

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
public class ProposalDetailResponse {

    private String proposalId;
    private String walletId;
    private String chainId;
    private String transactionData;
    private Integer requiredSignatures;
    private Integer currentSignatures;
    private String status;
    private String proposer;
    private Instant createdAt;
    private Instant executedAt;
    private Instant expiresAt;
    private List<SignatureInfo> signatures;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignatureInfo {
        private String signerAddress;
        private String signatureData;
        private Instant signedAt;
    }
}
