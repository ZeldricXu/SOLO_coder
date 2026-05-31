package com.chain.infrastructure.gasestimator.calculator;

import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import com.chain.infrastructure.persistence.entity.GasHistory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class GasPriceCalculator {

    public Mono<GasPriceResult> calculate(List<GasHistory> history) {
        return Mono.fromCallable(() -> {
            if (history.isEmpty()) {
                return createDefaultResult();
            }

            int size = history.size();
            BigDecimal avgSlow = calculateAverage(history, GasHistory::getSlowGasPrice, size);
            BigDecimal avgStd = calculateAverage(history, GasHistory::getStandardGasPrice, size);
            BigDecimal avgFast = calculateAverage(history, GasHistory::getFastGasPrice, size);
            BigDecimal avgBaseFee = calculateBaseFeeAverage(history, size);

            return new GasPriceResult(avgSlow, avgStd, avgFast, avgBaseFee,
                    avgStd.subtract(avgBaseFee).max(BigDecimal.ZERO));
        });
    }

    private BigDecimal calculateAverage(List<GasHistory> history,
                                         java.util.function.Function<GasHistory, BigDecimal> mapper,
                                         int size) {
        return history.stream()
                .map(mapper)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(size), 18, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateBaseFeeAverage(List<GasHistory> history, int size) {
        return history.stream()
                .map(g -> g.getBaseFee() != null ? g.getBaseFee() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(size), 18, RoundingMode.HALF_UP);
    }

    private GasPriceResult createDefaultResult() {
        return new GasPriceResult(
                new BigDecimal("1"),
                new BigDecimal("2"),
                new BigDecimal("5"),
                new BigDecimal("1"),
                new BigDecimal("1")
        );
    }

    public record GasPriceResult(
            BigDecimal slowGasPrice,
            BigDecimal standardGasPrice,
            BigDecimal fastGasPrice,
            BigDecimal baseFee,
            BigDecimal priorityFee
    ) {}
}
