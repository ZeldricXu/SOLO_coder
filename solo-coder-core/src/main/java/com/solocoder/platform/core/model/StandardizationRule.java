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
public class StandardizationRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ruleId;
    private String field;
    private StandardizationType type;
    private Map<String, Object> parameters;

    public enum StandardizationType {
        DATE_FORMAT, NUMBER_SCALE, STRING_CASE, TRIM_WHITESPACE, NULL_DEFAULT, ENUM_MAPPING, REGEX_REPLACE
    }
}
