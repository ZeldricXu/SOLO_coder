package com.datastandard.modules.lineage.storage;

import com.datastandard.modules.lineage.model.LineageEdge;

import java.util.List;

public interface LineageStorage {
    void saveEdge(LineageEdge edge);
    void saveEdges(List<LineageEdge> edges);
    List<LineageEdge> queryUpstream(String tableName, int depth);
    List<LineageEdge> queryDownstream(String tableName, int depth);
    List<LineageEdge> getAllEdges();
    void clear();
}
