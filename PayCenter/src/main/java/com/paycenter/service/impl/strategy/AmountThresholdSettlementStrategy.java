package com.paycenter.service.impl.strategy;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.PeriodType;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.SettlementPeriodStrategy;
import com.paycenter.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class AmountThresholdSettlementStrategy implements SettlementPeriodStrategy {

    private static final Logger logger = LoggerFactory.getLogger(AmountThresholdSettlementStrategy.class);

    @Autowired
    private TransactionRepository transactionRepository;

    @Override
    public PeriodType getPeriodType() {
        return PeriodType.AMOUNT_THRESHOLD;
    }

    @Override
    public boolean shouldExecuteSettlement(MerchantConfig config, LocalDateTime currentTime) {
        BigDecimal threshold = parseThreshold(config.getSettlementPeriodConfig());
        if (threshold.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("金额阈值无效，跳过结算判断");
            return false;
        }

        BigDecimal pendingAmount = calculatePendingAmount(config.getMerchantId());
        boolean shouldExecute = pendingAmount.compareTo(threshold) >= 0;
        
        if (shouldExecute) {
            logger.info("商户达到金额阈值触发结算: merchantId={}, threshold={}, pendingAmount={}",
                    config.getMerchantId(), threshold, pendingAmount);
        }
        
        return shouldExecute;
    }

    @Override
    public LocalDateTime calculateNextSettlementTime(MerchantConfig config, LocalDateTime fromTime) {
        BigDecimal threshold = parseThreshold(config.getSettlementPeriodConfig());
        BigDecimal pendingAmount = calculatePendingAmount(config.getMerchantId());
        
        if (pendingAmount.compareTo(threshold) >= 0) {
            return fromTime;
        }
        
        return fromTime.plusDays(1);
    }

    @Override
    public Optional<SettlementPeriod> generateSettlementPeriod(String merchantId, MerchantConfig config) {
        LocalDate today = LocalDate.now();
        LocalDate lastMonth = today.minusMonths(1);
        
        SettlementPeriod period = SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .merchantId(merchantId)
                .periodType(PeriodType.AMOUNT_THRESHOLD)
                .periodStart(lastMonth.withDayOfMonth(1).atStartOfDay())
                .periodEnd(today.atStartOfDay().minusNanos(1))
                .periodDescription("金额阈值结算: " + lastMonth.getMonthValue() + "月至今")
                .status(SettlementStatus.PENDING)
                .build();
        
        logger.info("生成金额阈值结算周期: merchantId={}", merchantId);
        
        return Optional.of(period);
    }

    @Override
    public String getPeriodConfigDescription(MerchantConfig config) {
        BigDecimal threshold = parseThreshold(config.getSettlementPeriodConfig());
        return "金额阈值结算，阈值: " + threshold + "元";
    }

    private BigDecimal parseThreshold(String config) {
        if (config == null || config.isEmpty()) {
            return new BigDecimal("10000.00");
        }
        try {
            return new BigDecimal(config.trim());
        } catch (Exception e) {
            logger.warn("解析金额阈值失败，使用默认值10000.00: {}", config);
            return new BigDecimal("10000.00");
        }
    }

    private BigDecimal calculatePendingAmount(String merchantId) {
        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);
        List<Transaction> transactions = transactionRepository.findByMerchantIdAndCreatedAtBetween(
                merchantId, oneMonthAgo, LocalDateTime.now());
        
        return transactions.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESS)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
