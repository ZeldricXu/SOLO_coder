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
public class IndexRangeRequest {

    @NotBlank(message = "chainId is required")
    private String chainId;

    @NotNull(message = "startBlock is required")
    private Long startBlock;

    @NotNull(message = "endBlock is required")
    private Long endBlock;

    private Boolean parallel;
}
