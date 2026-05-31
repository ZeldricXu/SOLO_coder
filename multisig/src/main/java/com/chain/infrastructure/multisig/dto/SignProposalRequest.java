package com.chain.infrastructure.multisig.dto;

import lombok.Data;

@Data
public class SignProposalRequest {

    private String proposalId;

    private String signer;

    private String signature;
}
