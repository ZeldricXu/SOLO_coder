package com.solocoder.platform.gas.estimator.domain.repository;

import com.solocoder.platform.gas.estimator.domain.model.GasHistory;

import java.util.List;
import java.util.Optional;

public interface GasHistoryRepository {

    GasHistory save(GasHistory history);

    List<GasHistory> saveAll(List<GasHistory> histories);

    Optional<GasHistory> findByChainIdAndBlockNumber(String chainId, Long blockNumber);

    List<GasHistory> findRecentByChainId(String chainId, int limit);

    Optional<GasHistory> findLatestByChainId(String chainId);
}
