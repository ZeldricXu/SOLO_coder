package com.chainetl.modules.events.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateListenerRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "contractAddress is required")
    private String contractAddress;

    @NotBlank(message = "eventSignature is required")
    private String eventSignature;

    @NotBlank(message = "callbackUrl is required")
    private String callbackUrl;

    @NotNull(message = "startBlock is required")
    private Long startBlock;
}
