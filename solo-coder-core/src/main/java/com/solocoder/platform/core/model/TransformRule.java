package com.solocoder.platform.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ruleId;
    private String sourceField;
    private String targetField;
    private TransformType type;
    private Map<String, Object> parameters;

    public enum TransformType {
        RENAME, CAST, FORMAT, TRIM, DEFAULT, MAP, COMPUTE, FILTER
    }
}
