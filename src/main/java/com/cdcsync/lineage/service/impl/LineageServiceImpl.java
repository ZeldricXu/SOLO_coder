package com.cdcsync.lineage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.lineage.core.LineageDagBuilder;
import com.cdcsync.lineage.core.LineageRelation;
import com.cdcsync.lineage.core.SqlLineageParser;
import com.cdcsync.lineage.domain.LineageEdge;
import com.cdcsync.lineage.domain.LineageGraph;
import com.cdcsync.lineage.mapper.LineageEdgeMapper;
import com.cdcsync.lineage.mapper.LineageGraphMapper;
import com.cdcsync.lineage.service.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineageServiceImpl implements LineageService {

    private final SqlLineageParser sqlLineageParser;
    private final LineageDagBuilder lineageDagBuilder;
    private final LineageGraphMapper lineageGraphMapper;
    private final LineageEdgeMapper lineageEdgeMapper;

    @Override
    @Transactional
    public LineageGraph parseSql(String sql, String sourceIdentifier) {
        try {
            List<LineageRelation> relations = sqlLineageParser.parse(sql);

            LineageGraph graph = lineageDagBuilder.buildGraph("SQL", sourceIdentifier, sql, relations);
            lineageGraphMapper.insert(graph);

            List<LineageEdge> edges = lineageDagBuilder.buildEdges(graph.getId(), relations);
            for (LineageEdge edge : edges) {
                lineageEdgeMapper.insert(edge);
            }

            log.info("Parsed SQL lineage successfully, graphId: {}, edgeCount: {}", graph.getId(), edges.size());
            return graph;
        } catch (Exception e) {
            log.error("Failed to parse SQL lineage", e);
            throw new BusinessException("SQL lineage parsing failed: " + e.getMessage());
        }
    }

    @Override
    public LineageGraph getGraph(String graphId) {
        LineageGraph graph = lineageGraphMapper.selectById(graphId);
        if (graph == null) {
            throw new BusinessException("Lineage graph not found: " + graphId);
        }
        return graph;
    }

    @Override
    public List<LineageEdge> getLineageByTable(String tableName) {
        return lineageEdgeMapper.selectByTableName(tableName);
    }

    @Override
    public List<LineageEdge> getUpstreamLineage(String tableName, String columnName) {
        return lineageEdgeMapper.selectUpstream(tableName, columnName);
    }

    @Override
    public List<LineageEdge> getDownstreamLineage(String tableName, String columnName) {
        return lineageEdgeMapper.selectDownstream(tableName, columnName);
    }
}
