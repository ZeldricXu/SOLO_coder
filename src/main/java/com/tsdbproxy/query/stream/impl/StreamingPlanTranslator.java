package com.tsdbproxy.query.stream.impl;

import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.PhysicalPlan;
import com.tsdbproxy.query.stream.spi.PlanTranslator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class StreamingPlanTranslator implements PlanTranslator {

    @Override
    public PhysicalPlan translate(LogicalPlan logicalPlan) {
        log.info("翻译逻辑计划到物理计划");

        PhysicalPlan.PhysicalPlanBuilder builder = PhysicalPlan.builder()
                .operator(logicalPlan.getOperator())
                .executionMode("streaming")
                .storageEngine("tsdb");

        List<String> partitions = new ArrayList<>();
        if (logicalPlan.getGroupBy() != null && !logicalPlan.getGroupBy().isEmpty()) {
            builder.executionMode("parallel_streaming");
            partitions.add("partition_by_group_key");
        }
        builder.partitions(partitions);

        List<PhysicalPlan> children = new ArrayList<>();
        if (logicalPlan.getCondition() != null) {
            PhysicalPlan filterPlan = PhysicalPlan.builder()
                    .operator("FILTER")
                    .executionMode("streaming")
                    .storageEngine("tsdb")
                    .build();
            children.add(filterPlan);
        }
        builder.children(children);

        return builder.build();
    }
}
