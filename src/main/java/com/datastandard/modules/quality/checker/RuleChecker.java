package com.datastandard.modules.quality.checker;

import java.util.List;
import java.util.Map;

public interface RuleChecker {
    List<Map<String, Object>> check(Map<String, Object> data);
    String getRuleName();
}
