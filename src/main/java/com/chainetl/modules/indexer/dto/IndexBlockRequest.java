package com.chainetl.modules.indexer.dto;

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
public class IndexBlockRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotNull(message = "blockNumber is required")
    private Long blockNumber;

    private String rawBlockData;
}
