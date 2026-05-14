package com.assetmanage.dto;

import lombok.Data;

@Data
public class DepreciationQueryRequest {

    private String assetId;
    private String startPeriod;
    private String endPeriod;
}
