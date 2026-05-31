package com.chainetl.modules.chain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcNodeConfig {

    @NotBlank(message = "rpcUrl is required")
    private String rpcUrl;

    private String wsUrl;

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotBlank(message = "chainName is required")
    private String chainName;
}
