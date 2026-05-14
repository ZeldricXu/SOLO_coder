package com.assetmanage.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AssetScrapRequest {

    private String assetId;
    private String scrapReason;
    private BigDecimal residualValue;
    private String operatorId;
}
