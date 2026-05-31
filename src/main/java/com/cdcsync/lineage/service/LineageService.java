package com.cdcsync.lineage.service;

import com.cdcsync.lineage.domain.LineageEdge;
import com.cdcsync.lineage.domain.LineageGraph;

import java.util.List;

public interface LineageService {

    LineageGraph parseSql(String sql, String sourceIdentifier);

    LineageGraph getGraph(String graphId);

    List<LineageEdge> getLineageByTable(String tableName);

    List<LineageEdge> getUpstreamLineage(String tableName, String columnName);

    List<LineageEdge> getDownstreamLineage(String tableName, String columnName);
}
