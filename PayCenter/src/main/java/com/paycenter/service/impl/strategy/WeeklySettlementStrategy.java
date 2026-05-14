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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

@Component
public class WeeklySettlementStrategy implements SettlementPeriodStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WeeklySettlementStrategy.class);

    @Override
    public PeriodType getPeriodType() {
        return PeriodType.WEEKLY;
    }

    @Override
    public boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime) {
        DayOfWeek settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        DayOfWeek currentDay = currentTime.getDayOfWeek();
        
        return currentDay.equals(settlementDay);
    }

    @Override
    public LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime) {
        DayOfWeek settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        LocalDate fromDate = fromTime.toLocalDate();
        
        LocalDate nextSettlementDate;
        if (fromDate.getDayOfWeek().equals(settlementDay)) {
            nextSettlementDate = fromDate.plusWeeks(1);
        } else {
            nextSettlementDate = fromDate.with(TemporalAdjusters.next(settlementDay));
        }
        
        return nextSettlementDate.atStartOfDay();
    }

    @Override
    public Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config) {
        DayOfWeek settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        LocalDate today = LocalDate.now();
        LocalDate weekEnd = today.with(TemporalAdjusters.previous(settlementDay));
        LocalDate weekStart = weekEnd.minusWeeks(1).plusDays(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .merchantId(merchantId)
                .periodType(PeriodType.WEEKLY)
                .periodStart(weekStart.atStartOfDay())
                .periodEnd(weekEnd.atTime(23, 59, 59, 999999999))
                .periodDescription("每周结算: " + weekStart.toString() + " ~ " + weekEnd.toString())
                .status(SettlementStatus.PENDING)
                .build();
        
        logger.info("生成每周结算周期: merchantId={}, period={} ~ {}",
                merchantId, period.getPeriodStart(), period.getPeriodEnd());
        
        return Optional.of(period);
    }

    @Override
    public String getPeriodConfigDescription(MerchantConfig config) {
        DayOfWeek settlementDay = parseSettlementDay(config.getSettlementPeriodConfig());
        return "每周结算，结算日: 周" + getChineseWeekDay(settlementDay);
    }

    private DayOfWeek parseSettlementDay(String config) {
        if (config == null || config.isEmpty()) {
            return DayOfWeek.MONDAY;
        }
        try {
            int dayNum = Integer.parseInt(config.trim());
            if (dayNum >= 1 && dayNum <= 7) {
                return DayOfWeek.of(dayNum);
            }
        } catch (Exception e) {
            logger.warn("解析每周结算日失败，使用默认周一: {}", config);
        }
        return DayOfWeek.MONDAY;
    }

    private String getChineseWeekDay(DayOfWeek day) {
        String[] days = {"一", "二", "三", "四", "五", "六", "日"};
        return days[day.getValue() - 1];
    }
}
