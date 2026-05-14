package com.paycenter.service;

import com.paycenter.entity.SettlementPeriod;
import com.paycenter.enums.PeriodType;

import java.util.List;
import java.util.Optional;

public interface SettlementPeriodService {
    SettlementPeriod createPeriod(SettlementPeriod period);
    SettlementPeriod updatePeriod(SettlementPeriod period);
    void deletePeriod(String periodId);
    Optional<SettlementPeriod> getPeriodById(String periodId);
    List<SettlementPeriod> getAllEnabledPeriods();
    boolean shouldSettleNow(SettlementPeriod period);
}
