package com.chain.infrastructure.gasestimator.repository;

import com.chain.infrastructure.persistence.entity.GasHistory;
import reactor.core.publisher.Mono;

import java.util.List;

public interface GasHistoryRepository {

    Mono<List<GasHistory>> findRecentHistory(String chainType, int blocksBack);

    Mono<GasHistory> save(GasHistory gasHistory);
}
