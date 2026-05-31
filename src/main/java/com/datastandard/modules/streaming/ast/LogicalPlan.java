package com.datastandard.modules.streaming.ast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicalPlan {
    private PlanType type;
    @Builder.Default
    private List<LogicalPlan> children = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> properties = new HashMap<>();

    public enum PlanType {
        SCAN, FILTER, PROJECT, AGGREGATE, JOIN,
        SORT, LIMIT, DISTINCT, WINDOW_AGG,
        TUMBLE_WINDOW, HOP_WINDOW, SESSION_WINDOW
    }
}
