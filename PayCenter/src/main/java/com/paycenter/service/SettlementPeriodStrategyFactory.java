package com.paycenter.service;

import com.paycenter.enums.PeriodType;

import java.util.Map;
import java.util.Optional;

public interface SettlementPeriodStrategyFactory {
    Optional<SettlementPeriodStrategy> getStrategy(PeriodType periodType);
    void registerStrategy(SettlementPeriodStrategy strategy);
    Map<PeriodType, SettlementPeriodStrategy> getAllStrategies();
}
