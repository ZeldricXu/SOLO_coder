package com.enterprise.risk.common.rule;

import com.enterprise.risk.common.event.RiskEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvaluationResult implements Serializable {

    private String ruleId;
    private String ruleName;
    private boolean matched;
    private boolean shortCircuited;

    @Builder.Default
    private Double ruleScore = 0.0;

    @Builder.Default
    private Double modelScore = 0.0;

    @Builder.Default
    private Double finalScore = 0.0;

    @Builder.Default
    private Map<String, Object> context = new HashMap<>();

    @Builder.Default
    private List<RiskEvent> matchedEvents = new ArrayList<>();

    @Builder.Default
    private List<String> matchedReasons = new ArrayList<>();

    public static RuleEvaluationResult notMatched(String ruleId) {
        return RuleEvaluationResult.builder()
                .ruleId(ruleId)
                .matched(false)
                .build();
    }

    public static RuleEvaluationResult matched(String ruleId, String ruleName) {
        return RuleEvaluationResult.builder()
                .ruleId(ruleId)
                .ruleName(ruleName)
                .matched(true)
                .ruleScore(1.0)
                .build();
    }
}
