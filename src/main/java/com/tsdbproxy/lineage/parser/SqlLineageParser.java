package com.tsdbproxy.lineage.parser;

import cn.hutool.core.util.StrUtil;
import com.tsdbproxy.lineage.dto.LineageGraphResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SqlLineageParser {

    private static final Pattern FROM_PATTERN = Pattern.compile(
            "FROM\\s+([\\w\\s,]+?)(?:\\s+WHERE|\\s+GROUP|\\s+ORDER|\\s+HAVING|\\s+LIMIT|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SELECT_PATTERN = Pattern.compile(
            "SELECT\\s+(.*?)\\s+FROM",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern JOIN_PATTERN = Pattern.compile(
            "JOIN\\s+(\\w+)\\s+ON",
            Pattern.CASE_INSENSITIVE);

    public LineageGraphResult parse(String sql, String targetTable) {
        log.info("解析SQL血缘: targetTable={}", targetTable);

        LineageGraphResult result = new LineageGraphResult();
        List<LineageGraphResult.Node> nodes = new ArrayList<>();
        List<LineageGraphResult.Edge> edges = new ArrayList<>();
        Map<String, Set<String>> tableLineage = new HashMap<>();
        Map<String, Set<String>> columnLineage = new HashMap<>();

        String normalizedSql = sql.trim().replaceAll("\\s+", " ");

        Set<String> sourceTables = extractSourceTables(normalizedSql);
        Set<String> columns = extractColumns(normalizedSql);

        for (String sourceTable : sourceTables) {
            String sourceNodeId = "table:" + sourceTable;
            LineageGraphResult.Node sourceNode = new LineageGraphResult.Node();
            sourceNode.setId(sourceNodeId);
            sourceNode.setName(sourceTable);
            sourceNode.setType("table");
            sourceNode.setTable(sourceTable);
            nodes.add(sourceNode);

            String targetNodeId = "table:" + targetTable;
            LineageGraphResult.Node targetNode = new LineageGraphResult.Node();
            targetNode.setId(targetNodeId);
            targetNode.setName(targetTable);
            targetNode.setType("table");
            targetNode.setTable(targetTable);
            if (!nodes.contains(targetNode)) {
                nodes.add(targetNode);
            }

            LineageGraphResult.Edge edge = new LineageGraphResult.Edge();
            edge.setSource(sourceNodeId);
            edge.setTarget(targetNodeId);
            edge.setTransformType("select");
            edges.add(edge);

            tableLineage.computeIfAbsent(targetTable, k -> new HashSet<>()).add(sourceTable);
        }

        for (String column : columns) {
            for (String sourceTable : sourceTables) {
                String sourceNodeId = "column:" + sourceTable + "." + column;
                LineageGraphResult.Node sourceNode = new LineageGraphResult.Node();
                sourceNode.setId(sourceNodeId);
                sourceNode.setName(sourceTable + "." + column);
                sourceNode.setType("column");
                sourceNode.setTable(sourceTable);
                sourceNode.setColumn(column);
                nodes.add(sourceNode);

                String targetNodeId = "column:" + targetTable + "." + column;
                LineageGraphResult.Node targetNode = new LineageGraphResult.Node();
                targetNode.setId(targetNodeId);
                targetNode.setName(targetTable + "." + column);
                targetNode.setType("column");
                targetNode.setTable(targetTable);
                targetNode.setColumn(column);
                if (!nodes.contains(targetNode)) {
                    nodes.add(targetNode);
                }

                LineageGraphResult.Edge edge = new LineageGraphResult.Edge();
                edge.setSource(sourceNodeId);
                edge.setTarget(targetNodeId);
                edge.setTransformType("projection");
                edges.add(edge);

                columnLineage.computeIfAbsent(targetTable + "." + column, k -> new HashSet<>())
                        .add(sourceTable + "." + column);
            }
        }

        result.setNodes(nodes);
        result.setEdges(edges);
        result.setTableLineage(tableLineage);
        result.setColumnLineage(columnLineage);

        return result;
    }

    private Set<String> extractSourceTables(String sql) {
        Set<String> tables = new HashSet<>();

        Matcher fromMatcher = FROM_PATTERN.matcher(sql);
        while (fromMatcher.find()) {
            String fromClause = fromMatcher.group(1);
            String[] tableNames = fromClause.split(",");
            for (String tableName : tableNames) {
                String trimmed = tableName.trim();
                if (StrUtil.isNotBlank(trimmed)) {
                    tables.add(trimmed.split("\\s+")[0]);
                }
            }
        }

        Matcher joinMatcher = JOIN_PATTERN.matcher(sql);
        while (joinMatcher.find()) {
            tables.add(joinMatcher.group(1));
        }

        return tables;
    }

    private Set<String> extractColumns(String sql) {
        Set<String> columns = new HashSet<>();

        Matcher selectMatcher = SELECT_PATTERN.matcher(sql);
        if (selectMatcher.find()) {
            String selectClause = selectMatcher.group(1);
            if ("*".equals(selectClause.trim())) {
                columns.add("*");
            } else {
                String[] cols = selectClause.split(",");
                for (String col : cols) {
                    String trimmed = col.trim();
                    if (StrUtil.isNotBlank(trimmed)) {
                        columns.add(trimmed.split("\\s+")[0]);
                    }
                }
            }
        }

        return columns;
    }
}
