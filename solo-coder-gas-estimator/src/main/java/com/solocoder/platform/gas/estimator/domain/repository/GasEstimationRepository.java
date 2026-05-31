package com.solocoder.platform.gas.estimator.domain.repository;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;

import java.util.List;
import java.util.Optional;

public interface GasEstimationRepository {

    GasEstimation save(GasEstimation estimation);

    Optional<GasEstimation> findById(Long id);

    Optional<GasEstimation> findByEstimationId(String estimationId);

    List<GasEstimation> findByChainId(String chainId, int limit);

    List<GasEstimation> findLatest(String chainId, int limit);
}
