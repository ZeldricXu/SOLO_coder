package com.web3platform.multisigwallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureSubmitRequest {

    private Long proposalId;
    private String signerAddress;
    private String signature;
}
