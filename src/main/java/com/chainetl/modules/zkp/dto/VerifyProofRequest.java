package com.chainetl.modules.zkp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyProofRequest {

    @NotBlank(message = "circuitId is required")
    private String circuitId;

    @NotBlank(message = "proofData is required")
    private String proofData;

    @NotNull(message = "publicInputs is required")
    private Map<String, Object> publicInputs;

    @NotNull(message = "verificationKey is required")
    private Map<String, Object> verificationKey;
}
