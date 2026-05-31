package com.tsdbproxy.vector.index.spi;

import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;

public interface IndexRepository {

    Long create(IndexConfig config);

    IndexStats getStats(Long indexId);

    void updateStatus(Long indexId, String status, int totalVectors);
}
