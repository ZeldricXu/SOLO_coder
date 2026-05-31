package com.datastandard.modules.quality.rule;

import java.util.Map;

public interface QualityRule {
    boolean validate(Map<String, Object> data);
    Map<String, Object> getLastViolation();
    Map<String, Object> generateSuggestions(Map<String, Object> violation);
    String getRuleName();
}
