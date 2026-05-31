package com.datamasker.interfaces.dto.privacy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class BatchAddNoiseRequest {

    @NotEmpty
    @Valid
    private List<BatchItem> items;

    @Data
    public static class BatchItem {

        private String queryId;

        @NotNull
        private double value;

        @Positive
        private double sensitivity;

        @NotBlank
        private String mechanism;

        private Double epsilon;

        private Double delta;
    }
}
