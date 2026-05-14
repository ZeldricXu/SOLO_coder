package com.paycenter.service.impl;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.enums.PeriodType;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.repository.SettlementPeriodRepository;
import com.paycenter.service.MerchantConfigService;
import com.paycenter.service.SettlementPeriodService;
import com.paycenter.service.SettlementPeriodStrategy;
import com.paycenter.service.SettlementPeriodStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SettlementPeriodServiceImpl implements SettlementPeriodService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementPeriodServiceImpl.class);

    @Autowired
    private SettlementPeriodRepository settlementPeriodRepository;

    @Autowired
    private SettlementPeriodStrategyFactory strategyFactory;

    @Autowired
    private MerchantConfigService merchantConfigService;

    @Override
    public SettlementPeriod createDailyPeriod(String merchantId) {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .merchantId(merchantId)
                .periodType(PeriodType.DAILY)
                .periodStart(yesterday.atStartOfDay())
                .periodEnd(today.atStartOfDay().minusNanos(1))
                .periodDescription("每日结算周期: " + yesterday.toString())
                .status(SettlementStatus.PENDING)
                .build();
        
        return settlementPeriodRepository.save(period);
    }

    @Override
    public SettlementPeriod createWeeklyPeriod(String merchantId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusWeeks(1).with(LocalDate.of(today.minusWeeks(1).getYear(),
                today.minusWeeks(1).getMonth(), 1));
        LocalDate weekEnd = today.minusDays(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .merchantId(merchantId)
                .periodType(PeriodType.WEEKLY)
                .periodStart(weekStart.atStartOfDay())
                .periodEnd(weekEnd.atTime(23, 59, 59, 999999999))
                .periodDescription("每周结算周期")
                .status(SettlementStatus.PENDING)
                .build();
        
        return settlementPeriodRepository.save(period);
    }

    @Override
    public SettlementPeriod createMonthlyPeriod(String merchantId) {
        LocalDate today = LocalDate.now();
        LocalDate lastMonth = today.minusMonths(1);
        LocalDate monthStart = lastMonth.withDayOfMonth(1);
        LocalDate monthEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
        
        SettlementPeriod period = SettlementPeriod.builder()
                .merchantId(merchantId)
                .periodType(PeriodType.MONTHLY)
                .periodStart(monthStart.atStartOfDay())
                .periodEnd(monthEnd.atTime(23, 59, 59, 999999999))
                .periodDescription("每月结算周期")
                .status(SettlementStatus.PENDING)
                .build();
        
        return settlementPeriodRepository.save(period);
    }

    @Override
    @Transactional
    public List<SettlementPeriod> generatePeriodsForMerchant(String merchantId) {
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(merchantId);
        
        if (!Boolean.TRUE.equals(config.getAutoSettlementEnabled())) {
            logger.debug("商户自动结算未启用: merchantId={}", merchantId);
            return new ArrayList<>();
        }
        
        PeriodType periodType = config.getSettlementPeriodType();
        Optional<SettlementPeriodStrategy> strategyOpt = strategyFactory.getStrategy(periodType);
        
        if (strategyOpt.isEmpty()) {
            logger.warn("未找到结算周期策略: merchantId={}, periodType={}", merchantId, periodType);
            return new ArrayList<>();
        }
        
        SettlementPeriodStrategy strategy = strategyOpt.get();
        LocalDateTime now = LocalDateTime.now();
        
        if (!strategy.shouldExecuteSettlement(config, now)) {
            logger.debug("未达到结算触发条件: merchantId={}, periodType={}", merchantId, periodType);
            return new ArrayList<>();
        }
        
        Optional<SettlementPeriod> periodOpt = strategy.generateSettlementPeriod(merchantId, config);
        
        if (periodOpt.isEmpty()) {
            return new ArrayList<>();
        }
        
        SettlementPeriod period = periodOpt.get();
        settlementPeriodRepository.save(period);
        
        logger.info("生成结算周期: merchantId={}, periodId={}, type={}",
                merchantId, period.getPeriodId(), periodType);
        
        List<SettlementPeriod> result = new ArrayList<>();
        result.add(period);
        return result;
    }

    @Override
    public List<SettlementPeriod> generateAllPeriods() {
        List<SettlementPeriod> allPeriods = new ArrayList<>();
        List<String> merchantIds = merchantConfigService.getAllMerchantIds();
        
        logger.info("开始为 {} 个商户生成结算周期", merchantIds.size());
        
        for (String merchantId : merchantIds) {
            try {
                List<SettlementPeriod> periods = generatePeriodsForMerchant(merchantId);
                allPeriods.addAll(periods);
            } catch (Exception e) {
                logger.error("为商户生成结算周期失败: merchantId={}", merchantId, e);
            }
        }
        
        logger.info("完成生成结算周期，共 {} 个周期", allPeriods.size());
        return allPeriods;
    }

    @Override
    public Optional<SettlementPeriod> getCurrentPeriod(String merchantId) {
        return settlementPeriodRepository.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(
                merchantId, SettlementStatus.PENDING);
    }

    @Override
    public List<SettlementPeriod> getPendingPeriods() {
        return settlementPeriodRepository.findByStatus(SettlementStatus.PENDING);
    }

    @Override
    @Transactional
    public SettlementPeriod updatePeriodStatus(String periodId, SettlementStatus status) {
        SettlementPeriod period = settlementPeriodRepository.findById(periodId)
                .orElseThrow(() -> new RuntimeException("结算周期不存在"));
        
        SettlementStatus oldStatus = period.getStatus();
        period.setStatus(status);
        
        if (status == SettlementStatus.COMPLETED) {
            period.setSettledAt(LocalDateTime.now());
        }
        
        settlementPeriodRepository.save(period);
        
        logger.info("结算周期状态更新: periodId={}, 从 {} 到 {}", periodId, oldStatus, status);
        return period;
    }
}
