package com.chainetl.modules.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitSignatureRequest {

    @NotBlank(message = "proposalId is required")
    private String proposalId;

    @NotBlank(message = "signerAddress is required")
    private String signerAddress;

    @NotBlank(message = "signatureData is required")
    private String signatureData;
}
