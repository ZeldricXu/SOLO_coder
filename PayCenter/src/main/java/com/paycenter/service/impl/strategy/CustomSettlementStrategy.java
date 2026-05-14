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
import java.util.Optional;

@Component
public class CustomSettlementStrategy implements SettlementPeriodStrategy {

    private static final Logger logger = LoggerFactory.getLogger(CustomSettlementStrategy.class);

    @Override
    public PeriodType getPeriodType() {
        return PeriodType.CUSTOM;
    }

    @Override
    public boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime) {
        CustomConfig customConfig = parseCustomConfig(config.getSettlementPeriodConfig());
        
        if (customConfig.intervalDays > 0) {
            return isIntervalDay(currentTime, customConfig.intervalDays);
        }
        
        return false;
    }

    @Override
    public LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime) {
        CustomConfig customConfig = parseCustomConfig(config.getSettlementPeriodConfig());
        return fromTime.plusDays(customConfig.intervalDays > 0 ? customConfig.intervalDays : 1);
    }

    @Override
    public Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config) {
        CustomConfig customConfig = parseCustomConfig(config.getSettlementPeriodConfig());
        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.minusDays(customConfig.intervalDays);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .merchantId(merchantId)
                .periodType(PeriodType.CUSTOM)
                .periodStart(periodStart.atStartOfDay())
                .periodEnd(today.atStartOfDay().minusNanos(1))
                .periodDescription("自定义结算: 每" + customConfig.intervalDays + "天")
                .status(SettlementStatus.PENDING)
                .build();
        
        logger.info("生成自定义结算周期: merchantId={}, interval={}天", merchantId, customConfig.intervalDays);
        
        return Optional.of(period);
    }

    @Override
    public String getPeriodConfigDescription(MerchantConfig config) {
        CustomConfig customConfig = parseCustomConfig(config.getSettlementPeriodConfig());
        return "自定义结算，间隔: " + customConfig.intervalDays + "天";
    }

    private CustomConfig parseCustomConfig(String config) {
        CustomConfig result = new CustomConfig();
        result.intervalDays = 7;
        
        if (config == null || config.isEmpty()) {
            return result;
        }
        
        try {
            String[] parts = config.split(",");
            for (String part : parts) {
                String[] keyValue = part.trim().split(":");
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim();
                    String value = keyValue[1].trim();
                    if ("interval".equalsIgnoreCase(key)) {
                        result.intervalDays = Integer.parseInt(value);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("解析自定义配置失败，使用默认值: {}", config);
        }
        
        return result;
    }

    private boolean isIntervalDay(LocalDateTime currentTime, int intervalDays) {
        long epochDays = currentTime.toLocalDate().toEpochDay();
        return epochDays % intervalDays == 0;
    }

    private static class CustomConfig {
        int intervalDays = 7;
    }
}
