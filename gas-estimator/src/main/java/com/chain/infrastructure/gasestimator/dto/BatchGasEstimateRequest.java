package com.chain.infrastructure.gasestimator.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchGasEstimateRequest {

    private List<GasEstimateRequest> requests;

    private boolean optimize;
}
