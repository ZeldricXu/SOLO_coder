package com.assetmanage.dto;

import lombok.Data;

@Data
public class InventoryDiffHandleRequest {

    private String diffId;
    private String handleType;
    private String handleResult;
    private String operatorId;
}
