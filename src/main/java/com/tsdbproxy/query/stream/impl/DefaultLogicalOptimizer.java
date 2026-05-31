package com.tsdbproxy.query.stream.impl;

import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.spi.LogicalOptimizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DefaultLogicalOptimizer implements LogicalOptimizer {

    @Override
    public List<String> optimize(LogicalPlan plan) {
        List<String> appliedRules = new ArrayList<>();

        if (plan.getLimit() != null && plan.getLimit() < 1000) {
            appliedRules.add("push_down_limit");
            log.info("应用优化规则: push_down_limit");
        }

        if (plan.getCondition() != null && !plan.getCondition().isEmpty()) {
            appliedRules.add("predicate_pushdown");
            log.info("应用优化规则: predicate_pushdown");
        }

        if (plan.getProjections() != null && !plan.getProjections().contains("*")) {
            appliedRules.add("projection_pruning");
            log.info("应用优化规则: projection_pruning");
        }

        return appliedRules;
    }
}
