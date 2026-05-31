package com.cdcsync.vectorindex.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.vectorindex.domain.VectorIndex;

import java.util.List;

public interface VectorIndexService extends BaseService<VectorIndex, String> {

    void buildIndex(String id, List<float[]> vectors);

    List<Long> search(String id, float[] queryVector, int topK);

    void addVectors(String id, List<float[]> vectors);

    void deleteVectors(String id, List<Long> ids);
}
