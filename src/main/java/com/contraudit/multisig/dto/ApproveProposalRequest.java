package com.contraudit.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApproveProposalRequest {

    @NotBlank(message = "proposal id cannot be blank")
    private String proposalId;

    @NotBlank(message = "signer address cannot be blank")
    private String signerAddress;

    @NotBlank(message = "signature cannot be blank")
    private String signature;

    @NotBlank(message = "approval type cannot be blank")
    private String approvalType;
}
