package com.paycenter.service;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.enums.PeriodType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface SettlementPeriodStrategy {
    PeriodType getPeriodType();
    boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime);
    LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime);
    Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config);
    String getPeriodConfigDescription(MerchantConfig config);
}
