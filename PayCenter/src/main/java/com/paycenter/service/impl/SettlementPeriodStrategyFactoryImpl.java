package com.paycenter.service.impl;

import com.paycenter.enums.PeriodType;
import com.paycenter.service.SettlementPeriodStrategy;
import com.paycenter.service.SettlementPeriodStrategyFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SettlementPeriodStrategyFactoryImpl implements SettlementPeriodStrategyFactory {

    private static final Logger logger = LoggerFactory.getLogger(SettlementPeriodStrategyFactoryImpl.class);

    private final Map<PeriodType, SettlementPeriodStrategy> strategyMap = new HashMap<>();

    @Autowired
    private List<SettlementPeriodStrategy> strategies;

    @PostConstruct
    public void init() {
        for (SettlementPeriodStrategy strategy : strategies) {
            registerStrategy(strategy);
        }
        logger.info("结算周期策略工厂初始化完成，注册策略数量: {}", strategyMap.size());
    }

    @Override
    public Optional<SettlementPeriodStrategy> getStrategy(PeriodType periodType) {
        return Optional.ofNullable(strategyMap.get(periodType));
    }

    @Override
    public void registerStrategy(SettlementPeriodStrategy strategy) {
        if (strategy != null && strategy.getPeriodType() != null) {
            strategyMap.put(strategy.getPeriodType(), strategy);
            logger.debug("注册结算周期策略: {}", strategy.getPeriodType());
        }
    }

    @Override
    public Map<PeriodType, SettlementPeriodStrategy> getAllStrategies() {
        return new HashMap<>(strategyMap);
    }
}
