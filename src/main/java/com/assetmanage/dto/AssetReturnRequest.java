package com.assetmanage.dto;

import lombok.Data;

@Data
public class AssetReturnRequest {

    private String assetId;
    private String operatorId;
    private String returnNote;
}
