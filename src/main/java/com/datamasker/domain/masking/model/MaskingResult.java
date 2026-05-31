package com.datamasker.domain.masking.model;

import lombok.Data;

@Data
public class MaskingResult {

    private String fieldName;

    private String originalValue;

    private String maskedValue;

    private String strategy;

    private boolean wasMasked;
}
