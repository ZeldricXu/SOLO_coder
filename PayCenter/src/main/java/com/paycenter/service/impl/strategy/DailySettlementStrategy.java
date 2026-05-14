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
import java.time.LocalTime;
import java.util.Optional;

@Component
public class DailySettlementStrategy implements SettlementPeriodStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DailySettlementStrategy.class);

    @Override
    public PeriodType getPeriodType() {
        return PeriodType.DAILY;
    }

    @Override
    public boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime) {
        LocalTime settlementTime = parseSettlementTime(config.getSettlementPeriodConfig());
        return currentTime.toLocalTime().isAfter(settlementTime) || 
               currentTime.toLocalTime().equals(settlementTime);
    }

    @Override
    public LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime) {
        LocalTime settlementTime = parseSettlementTime(config.getSettlementPeriodConfig());
        LocalDateTime nextTime = fromTime.with(settlementTime);
        
        if (fromTime.isAfter(nextTime) || fromTime.equals(nextTime)) {
            nextTime = nextTime.plusDays(1);
        }
        
        return nextTime;
    }

    @Override
    public Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config) {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .merchantId(merchantId)
                .periodType(PeriodType.DAILY)
                .periodStart(yesterday.atStartOfDay())
                .periodEnd(today.atStartOfDay().minusNanos(1))
                .periodDescription("每日结算: " + yesterday.toString())
                .status(SettlementStatus.PENDING)
                .build();
        
        logger.info("生成每日结算周期: merchantId={}, period={} ~ {}",
                merchantId, period.getPeriodStart(), period.getPeriodEnd());
        
        return Optional.of(period);
    }

    @Override
    public String getPeriodConfigDescription(MerchantConfig config) {
        LocalTime settlementTime = parseSettlementTime(config.getSettlementPeriodConfig());
        return "每日结算，结算时间: " + settlementTime.toString();
    }

    private LocalTime parseSettlementTime(String config) {
        if (config == null || config.isEmpty()) {
            return LocalTime.of(0, 0);
        }
        try {
            String[] parts = config.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            logger.warn("解析结算时间失败，使用默认值: {}", config);
            return LocalTime.of(0, 0);
        }
    }
}
