package com.assetmanage.dto;

import lombok.Data;

import java.util.List;

@Data
public class DepreciationData {

    private List<DepreciationItem> depreciation;
}
