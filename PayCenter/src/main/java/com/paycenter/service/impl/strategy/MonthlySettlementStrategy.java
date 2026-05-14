package com.paycenter.service.impl.strategy;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.enums.PeriodType;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.service.SettlementPeriodStrategy;
import com.paycenter.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

@Component
public class MonthlySettlementStrategy implements SettlementPeriodStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MonthlySettlementStrategy.class);

    @Override
    public PeriodType getPeriodType() {
        return PeriodType.MONTHLY;
    }

    @Override
    public boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime) {
        int settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        int currentDay = currentTime.getDayOfMonth();
        int monthEndDay = currentTime.toLocalDate().lengthOfMonth();
        
        if (settlementDay > monthEndDay) {
            return currentDay == monthEndDay;
        }
        return currentDay == settlementDay;
    }

    @Override
    public LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime) {
        int settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        LocalDate fromDate = fromTime.toLocalDate();
        int monthEndDay = fromDate.lengthOfMonth();
        
        int actualSettlementDay = Math.min(settlementDay, monthEndDay);
        
        LocalDate nextSettlementDate;
        if (fromDate.getDayOfMonth() >= actualSettlementDay) {
            LocalDate nextMonth = fromDate.plusMonths(1);
            int nextMonthEndDay = nextMonth.lengthOfMonth();
            nextSettlementDate = nextMonth.withDayOfMonth(Math.min(settlementDay, nextMonthEndDay));
        } else {
            nextSettlementDate = fromDate.withDayOfMonth(actualSettlementDay);
        }
        
        return nextSettlementDate.atStartOfDay();
    }

    @Override
    public Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config) {
        int settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        LocalDate today = LocalDate.now();
        LocalDate lastMonth = today.minusMonths(1);
        
        int lastMonthSettlementDay = Math.min(settlementDay, lastMonth.lengthOfMonth());
        int thisMonthSettlementDay = Math.min(settlementDay, today.lengthOfMonth());
        
        LocalDate periodEnd = lastMonth.withDayOfMonth(lastMonthSettlementDay);
        LocalDate periodStart = lastMonth.withDayOfMonth(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .merchantId(merchantId)
                .periodType(PeriodType.MONTHLY)
                .periodStart(periodStart.atStartOfDay())
                .periodEnd(periodEnd.atTime(23, 59, 59, 999999999))
                .periodDescription("每月结算: " + periodStart.toString() + " ~ " + periodEnd.toString())
                .status(SettlementStatus.PENDING)
                .build();
        
        logger.info("生成每月结算周期: merchantId={}, period={} ~ {}",
                merchantId, period.getPeriodStart(), period.getPeriodEnd());
        
        return Optional.of(period);
    }

    @Override
    public String getPeriodConfigDescription(MerchantConfig config) {
        int settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        return "每月结算，结算日: 每月" + settlementDay + "日";
    }

    private int parseSettlementDay(String config) {
        if (config == null || config.isEmpty()) {
            return 1;
        }
        try {
            int day = Integer.parseInt(config.trim());
            if (day >= 1 && day <= 31) {
                return day;
            }
        } catch (Exception e) {
            logger.warn("解析每月结算日失败，使用默认1日: {}", config);
        }
        return 1;
    }
}
