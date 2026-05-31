package com.datamasker.domain.masking.model;

import lombok.Data;

@Data
public class MaskingContext {

    private String userLevel;

    private String fieldPattern;

    private String originalValue;

    private String fieldName;

    private String dataCategory;
}
