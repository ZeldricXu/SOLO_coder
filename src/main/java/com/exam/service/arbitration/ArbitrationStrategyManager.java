package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ArbitrationStrategyManager {

    private final Map<String, ArbitrationStrategy> strategies = new HashMap<>();
    private ArbitrationStrategy defaultStrategy;

    @Value("${exam.grading.default-arbitration-strategy:AVERAGE}")
    private String defaultStrategyCode;

    public ArbitrationStrategyManager() {
        this.defaultStrategy = new AverageArbitrationStrategy();
        strategies.put(defaultStrategy.getStrategyCode(), defaultStrategy);
        strategies.put("MAX_SCORE", new MaxScoreArbitrationStrategy());
        strategies.put("MANUAL", new ManualArbitrationStrategy());
    }

    public ArbitrationStrategy getDefaultStrategy() {
        ArbitrationStrategy strategy = strategies.get(defaultStrategyCode);
        if (strategy != null) {
            return strategy;
        }
        log.warn("默认仲裁策略{}不存在，使用均值策略", defaultStrategyCode);
        return defaultStrategy;
    }

    public ArbitrationStrategy getStrategy(String strategyCode) {
        if (strategyCode == null) {
            return getDefaultStrategy();
        }
        ArbitrationStrategy strategy = strategies.get(strategyCode);
        if (strategy == null) {
            log.warn("仲裁策略{}不存在，使用默认策略", strategyCode);
            return getDefaultStrategy();
        }
        return strategy;
    }

    public BigDecimal arbitrate(ExamAnswer answer) {
        return getDefaultStrategy().arbitrate(answer);
    }

    public BigDecimal arbitrate(ExamAnswer answer, String strategyCode) {
        return getStrategy(strategyCode).arbitrate(answer);
    }

    public void registerStrategy(ArbitrationStrategy strategy) {
        if (strategy != null) {
            strategies.put(strategy.getStrategyCode(), strategy);
            log.info("注册仲裁策略: {} - {}", strategy.getStrategyCode(), strategy.getStrategyName());
        }
    }

    public void setDefaultDiffRatio(BigDecimal ratio) {
        ArbitrationStrategy strategy = strategies.get("AVERAGE");
        if (strategy instanceof AverageArbitrationStrategy avg) {
            avg.setDiffRatio(ratio);
            log.info("均值仲裁策略分差比例更新为: {}", ratio);
        }
    }
}
