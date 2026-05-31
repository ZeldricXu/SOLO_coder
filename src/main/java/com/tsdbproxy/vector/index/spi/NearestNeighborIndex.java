package com.tsdbproxy.vector.index.spi;

import com.tsdbproxy.vector.index.model.Neighbor;
import com.tsdbproxy.vector.index.model.VectorDocument;

import java.util.List;

public interface NearestNeighborIndex {

    void build(List<VectorDocument> documents);

    void add(VectorDocument document);

    void remove(String id);

    List<Neighbor> search(float[] query, int topK);

    int size();
}
