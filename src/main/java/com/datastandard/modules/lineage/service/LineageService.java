package com.datastandard.modules.lineage.service;

import com.datastandard.modules.lineage.exporter.LineageExporter;
import com.datastandard.modules.lineage.extractor.LineageExtractor;
import com.datastandard.modules.lineage.model.LineageEdge;
import com.datastandard.modules.lineage.storage.LineageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class LineageService {

    private final Map<String, LineageExtractor> extractors;
    private final Map<String, LineageExporter> exporters;
    private final LineageStorage storage;

    public LineageService(List<LineageExtractor> extractorList,
                          List<LineageExporter> exporterList,
                          LineageStorage storage) {
        this.extractors = new HashMap<>();
        for (LineageExtractor extractor : extractorList) {
            this.extractors.put(extractor.getExtractorType(), extractor);
        }

        this.exporters = new HashMap<>();
        for (LineageExporter exporter : exporterList) {
            this.exporters.put(exporter.getFormat(), exporter);
        }

        this.storage = storage;
    }

    public List<LineageEdge> extractFromSql(String sql) {
        LineageExtractor extractor = extractors.get("SQL");
        if (extractor == null) {
            throw new IllegalArgumentException("未找到SQL血缘提取器");
        }
        List<LineageEdge> edges = extractor.extract(sql);
        storage.saveEdges(edges);
        return edges;
    }

    public List<LineageEdge> extractFromJobConfig(Map<String, Object> jobConfig) {
        LineageExtractor extractor = extractors.get("JOB_CONFIG");
        if (extractor == null) {
            throw new IllegalArgumentException("未找到JOB配置血缘提取器");
        }
        List<LineageEdge> edges = extractor.extract(jobConfig);
        storage.saveEdges(edges);
        return edges;
    }

    public List<LineageEdge> extractFromSparkPlan(String planJson) {
        LineageExtractor extractor = extractors.get("SPARK_PLAN");
        if (extractor == null) {
            throw new IllegalArgumentException("未找到Spark执行计划血缘提取器");
        }
        List<LineageEdge> edges = extractor.extract(planJson);
        storage.saveEdges(edges);
        return edges;
    }

    public void addCustomEdge(String source, String target, String type) {
        LineageEdge edge = LineageEdge.builder()
                .source(source)
                .target(target)
                .edgeType(type)
                .timestamp(java.time.Instant.now())
                .build();
        storage.saveEdge(edge);
    }

    public List<LineageEdge> queryUpstream(String tableName, int depth) {
        return storage.queryUpstream(tableName, depth);
    }

    public List<LineageEdge> queryDownstream(String tableName, int depth) {
        return storage.queryDownstream(tableName, depth);
    }

    public void exportLineage(String format, String path) {
        LineageExporter exporter = exporters.get(format.toUpperCase());
        if (exporter == null) {
            throw new IllegalArgumentException("不支持的导出格式: " + format);
        }
        List<LineageEdge> allEdges = storage.getAllEdges();
        exporter.export(allEdges, path);
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        List<LineageEdge> edges = storage.getAllEdges();
        stats.put("totalEdges", edges.size());
        stats.put("extractorTypes", extractors.keySet());
        stats.put("exportFormats", exporters.keySet());
        return stats;
    }

    public void clearAll() {
        storage.clear();
    }
}
