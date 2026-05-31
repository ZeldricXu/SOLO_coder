package com.cdcsync.streamquery.plan;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlanOptimizer {

    public static LogicalPlan optimize(LogicalPlan plan) {
        LogicalPlan optimized = deepCopy(plan);
        pushDownPredicates(optimized);
        pruneColumns(optimized);
        optimized.setProperty("optimized", true);
        return optimized;
    }

    private static LogicalPlan deepCopy(LogicalPlan plan) {
        return JSON.parseObject(JSON.toJSONString(plan), LogicalPlan.class);
    }

    private static void pushDownPredicates(LogicalPlan plan) {
        if ("FILTER".equals(plan.getPlanType()) && !plan.getChildren().isEmpty()) {
            LogicalPlan child = plan.getChildren().get(0);
            if ("PROJECT".equals(child.getPlanType()) && !child.getChildren().isEmpty()) {
                LogicalPlan grandChild = child.getChildren().get(0);
                if ("SCAN".equals(grandChild.getPlanType())) {
                    grandChild.setProperty("filter", plan.getProperty("condition"));
                    plan.getChildren().clear();
                    plan.addChild(child);
                    plan.setProperty("pushedDown", true);
                }
            }
        }
        for (LogicalPlan child : plan.getChildren()) {
            pushDownPredicates(child);
        }
    }

    private static void pruneColumns(LogicalPlan plan) {
        List<String> requiredColumns = new ArrayList<>();
        collectRequiredColumns(plan, requiredColumns);
        applyColumnPruning(plan, requiredColumns);
    }

    private static void collectRequiredColumns(LogicalPlan plan, List<String> columns) {
        if ("PROJECT".equals(plan.getPlanType())) {
            Object selectItems = plan.getProperty("selectItems");
            if (selectItems != null) {
                columns.add(selectItems.toString());
            }
        }
        for (LogicalPlan child : plan.getChildren()) {
            collectRequiredColumns(child, columns);
        }
    }

    private static void applyColumnPruning(LogicalPlan plan, List<String> requiredColumns) {
        if ("SCAN".equals(plan.getPlanType())) {
            plan.setProperty("requiredColumns", requiredColumns);
            plan.setProperty("pruned", true);
        }
        for (LogicalPlan child : plan.getChildren()) {
            applyColumnPruning(child, requiredColumns);
        }
    }

    public static Map<String, Object> explainOptimization(LogicalPlan original, LogicalPlan optimized) {
        return Map.of(
                "original", SqlParser.explain(original),
                "optimized", SqlParser.explain(optimized),
                "rulesApplied", List.of("Predicate Pushdown", "Column Pruning")
        );
    }
}
