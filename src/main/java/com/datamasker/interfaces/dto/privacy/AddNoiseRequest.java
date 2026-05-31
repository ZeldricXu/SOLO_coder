package com.datamasker.interfaces.dto.privacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AddNoiseRequest {

    @NotNull
    private double value;

    @Positive
    private double sensitivity;

    private Double epsilon;

    private Double delta;

    @NotBlank
    private String mechanismType;
}
