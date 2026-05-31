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
public class JobConfigLineageExtractor implements LineageExtractor {

    @Override
    public List<LineageEdge> extract(Object input) {
        if (!(input instanceof Map)) {
            return new ArrayList<>();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> jobConfig = (Map<String, Object>) input;
        log.info("从Job配置提取血缘关系");

        String jobName = (String) jobConfig.get("jobName");
        String jobType = (String) jobConfig.get("jobType");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) jobConfig.get("sources");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sinks = (List<Map<String, Object>>) jobConfig.get("sinks");

        return buildEdges(sources, sinks, jobName, jobType);
    }

    private List<LineageEdge> buildEdges(List<Map<String, Object>> sources,
                                          List<Map<String, Object>> sinks,
                                          String jobName, String jobType) {
        List<LineageEdge> edges = new ArrayList<>();
        if (sources == null || sinks == null) {
            return edges;
        }

        for (Map<String, Object> sink : sinks) {
            for (Map<String, Object> source : sources) {
                LineageEdge edge = LineageEdge.builder()
                        .source((String) source.get("name"))
                        .target((String) sink.get("name"))
                        .edgeType(LineageEdge.EdgeType.JOB.name())
                        .transformType(jobType)
                        .jobName(jobName)
                        .timestamp(Instant.now())
                        .build();
                edges.add(edge);
            }
        }
        return edges;
    }

    @Override
    public String getExtractorType() {
        return "JOB_CONFIG";
    }
}
