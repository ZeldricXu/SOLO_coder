package com.datastandard.modules.lineage.extractor;

import com.datastandard.modules.lineage.model.LineageEdge;

import java.util.List;

public interface LineageExtractor {
    List<LineageEdge> extract(Object input);
    String getExtractorType();
}
