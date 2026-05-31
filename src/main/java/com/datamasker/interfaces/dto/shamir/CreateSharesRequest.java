package com.datamasker.interfaces.dto.shamir;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSharesRequest {
    @NotBlank
    private String secret;
    @Min(2)
    private int threshold;
    @Min(2)
    private int totalShares;
    private String owner;
}
