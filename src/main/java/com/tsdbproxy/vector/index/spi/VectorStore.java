package com.tsdbproxy.vector.index.spi;

import com.tsdbproxy.vector.index.model.VectorDocument;

import java.util.List;

public interface VectorStore {

    void save(Long indexId, List<VectorDocument> documents);

    List<VectorDocument> load(Long indexId);
}
