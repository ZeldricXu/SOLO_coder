package com.datastandard.modules.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DimensionFilter {

    private String key;

    private String value;

    private Operator operator;

    private List<String> values;

    public enum Operator {
        EQ,
        NEQ,
        IN,
        NOT_IN,
        CONTAINS,
        GT,
        LT,
        GTE,
        LTE
    }
}
