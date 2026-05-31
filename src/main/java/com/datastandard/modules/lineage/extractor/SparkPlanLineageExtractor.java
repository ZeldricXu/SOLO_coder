package com.datastandard.modules.lineage.extractor;

import com.datastandard.modules.lineage.model.LineageEdge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SparkPlanLineageExtractor implements LineageExtractor {

    @Override
    public List<LineageEdge> extract(Object input) {
        if (!(input instanceof String)) {
            return new ArrayList<>();
        }
        String planJson = (String) input;
        log.info("从Spark执行计划提取血缘");

        List<LineageEdge> edges = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = parseJson(planJson);
        traversePlan(plan, edges, null);
        return edges;
    }

    private Map<String, Object> parseJson(String json) {
        return new java.util.HashMap<>();
    }

    private void traversePlan(Map<String, Object> plan, List<LineageEdge> edges, String parent) {
        if (plan == null) return;

        String nodeName = (String) plan.get("nodeName");
        String tableName = (String) plan.get("table");

        if (tableName != null && parent != null) {
            LineageEdge edge = LineageEdge.builder()
                    .source(tableName)
                    .target(parent)
                    .edgeType(LineageEdge.EdgeType.SPARK_PLAN.name())
                    .transformType(nodeName)
                    .timestamp(Instant.now())
                    .build();
            edges.add(edge);
            parent = tableName;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) plan.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                traversePlan(child, edges, parent);
            }
        }
    }

    @Override
    public String getExtractorType() {
        return "SPARK_PLAN";
    }
}
