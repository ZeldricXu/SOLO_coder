package com.datamasker.interfaces.dto.shamir;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ReconstructRequest {
    @NotBlank
    private String secretId;
    @NotEmpty
    private List<Integer> shardIndices;
}
