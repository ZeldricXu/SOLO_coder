package com.datamasker.domain.classification.model;

import lombok.Data;

@Data
public class DataField {

    private String dataSource;

    private String fieldName;

    private String fieldValue;

    private String fieldType;

    private String category;

    private String level;

    private double confidence;
}
