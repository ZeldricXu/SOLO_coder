package com.web3platform.multisigwallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposalCreateRequest {

    private String walletAddress;
    private String proposalType;
    private String targetAddress;
    private BigInteger value;
    private String data;
    private String description;
    private String creatorAddress;
}
