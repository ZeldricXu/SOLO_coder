package com.chain.infrastructure.gasestimator.recorder;

import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.gasestimator.dto.GasEstimateResult;
import com.chain.infrastructure.gasestimator.repository.GasHistoryRepository;
import com.chain.infrastructure.persistence.entity.GasHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class GasPriceRecorder {

    private final GasHistoryRepository repository;

    public Mono<GasHistory> record(String chainType, GasEstimateResult result) {
        return Mono.fromCallable(() -> {
            GasHistory history = new GasHistory();
            history.setHistoryId(IdGenerator.generateId("gas"));
            history.setChainType(chainType);
            history.setSlowGasPrice(result.getSlowGasPrice());
            history.setStandardGasPrice(result.getStandardGasPrice());
            history.setFastGasPrice(result.getFastGasPrice());
            history.setBaseFee(result.getBaseFee());
            history.setPriorityFee(result.getPriorityFee());
            history.setTimestamp(System.currentTimeMillis());
            return history;
        }).flatMap(repository::save);
    }
}
