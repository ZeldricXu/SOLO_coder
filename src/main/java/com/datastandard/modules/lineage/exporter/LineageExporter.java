package com.datastandard.modules.lineage.exporter;

import com.datastandard.modules.lineage.model.LineageEdge;

import java.util.List;

public interface LineageExporter {
    void export(List<LineageEdge> edges, String path);
    String getFormat();
}
