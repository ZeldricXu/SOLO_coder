package com.paycenter.service.impl;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.Settlement;
import com.paycenter.entity.SettlementPeriod;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.SettlementRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.*;
import com.paycenter.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class SettlementServiceImpl implements SettlementService {

    private static final Logger logger = LoggerFactory.getLogger(SettlementServiceImpl.class);

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private SettlementPeriodService settlementPeriodService;

    @Autowired
    private PaymentChannelService paymentChannelService;

    @Autowired
    private TransactionStatService transactionStatService;

    @Autowired
    private PrecomputationService precomputationService;

    @Autowired
    private MerchantConfigService merchantConfigService;

    private final AtomicReference<LocalDateTime> lastPrecomputationTime = new AtomicReference<>();
    private final AtomicBoolean precomputationRunning = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        logger.info("结算服务初始化完成");
        if (precomputationService != null) {
            int precomputationMinutes = precomputationService.getCurrentPrecomputationMinutes();
            logger.info("当前预计算时间配置: {} 分钟", precomputationMinutes);
        }
    }

    @Override
    @Transactional
    public Settlement calculateAndExecuteSettlement(String merchantId, LocalDate settlementDate) {
        LocalDateTime startOfDay = settlementDate.atStartOfDay();
        LocalDateTime endOfDay = settlementDate.atTime(LocalTime.MAX);

        BigDecimal successAmount = transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                merchantId, startOfDay, endOfDay);
        BigDecimal refundAmount = transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                merchantId, startOfDay, endOfDay);
        Long successCount = transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                merchantId, startOfDay, endOfDay);

        if (successAmount == null) {
            successAmount = BigDecimal.ZERO;
        }
        if (refundAmount == null) {
            refundAmount = BigDecimal.ZERO;
        }

        BigDecimal netAmount = successAmount.subtract(refundAmount);

        List<Transaction> transactions = transactionRepository.findByMerchantIdAndStatusIn(
                merchantId, List.of(TransactionStatus.SUCCESS));

        BigDecimal feeAmount = calculateFee(transactions);
        BigDecimal settlementAmount = netAmount.subtract(feeAmount);

        SettlementPeriod period = settlementPeriodService.getAllEnabledPeriods().stream()
                .findFirst()
                .orElse(null);

        if (period != null && settlementAmount.compareTo(period.getMinSettlementAmount()) < 0) {
            logger.info("结算金额{}低于最低结算金额{}，跳过结算", settlementAmount, period.getMinSettlementAmount());
            return null;
        }

        Settlement settlement = Settlement.builder()
                .settlementId(IdGenerator.generateSettlementId())
                .merchantId(merchantId)
                .settlementPeriod(settlementDate)
                .transactionCount(successCount.intValue())
                .totalAmount(netAmount)
                .settlementAmount(settlementAmount)
                .feeAmount(feeAmount)
                .settlementStatus(SettlementStatus.PENDING)
                .build();

        settlement = settlementRepository.save(settlement);

        try {
            settlement.setSettlementStatus(SettlementStatus.PROCESSING);
            settlementRepository.save(settlement);

            if (settlementAmount.compareTo(BigDecimal.ZERO) > 0) {
                accountService.deposit(merchantId, settlementAmount, "结算入账");
            }

            settlement.setSettlementStatus(SettlementStatus.COMPLETED);
            settlement.setSettledAt(LocalDateTime.now());
            settlement = settlementRepository.save(settlement);

            logger.info("结算成功: merchantId={}, settlementId={}, amount={}", 
                    merchantId, settlement.getSettlementId(), settlementAmount);

        } catch (Exception e) {
            logger.error("结算失败: merchantId={}", merchantId, e);
            settlement.setSettlementStatus(SettlementStatus.FAILED);
            settlement.setFailureReason(e.getMessage());
            settlement = settlementRepository.save(settlement);
            throw new BusinessException("结算执行失败: " + e.getMessage());
        }

        return settlement;
    }

    private BigDecimal calculateFee(List<Transaction> transactions) {
        BigDecimal totalFee = BigDecimal.ZERO;
        
        Set<String> channelIds = transactions.stream()
                .map(Transaction::getChannelId)
                .collect(Collectors.toSet());

        for (String channelId : channelIds) {
            BigDecimal channelAmount = transactions.stream()
                    .filter(t -> channelId.equals(t.getChannelId()))
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Optional<PaymentChannel> channel = paymentChannelService.getChannelById(channelId);
            if (channel.isPresent()) {
                BigDecimal fee = channelAmount.multiply(channel.get().getFeeRate())
                        .setScale(2, RoundingMode.HALF_UP);
                totalFee = totalFee.add(fee);
            }
        }

        return totalFee;
    }

    @Override
    public Optional<Settlement> getSettlementById(String settlementId) {
        return settlementRepository.findById(settlementId);
    }

    @Override
    public List<Settlement> getSettlementsByMerchant(String merchantId) {
        return settlementRepository.findByMerchantId(merchantId);
    }

    @Override
    public List<Settlement> querySettlements(SettlementQueryRequest request) {
        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now().minusDays(30);
        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : LocalDate.now();
        
        return settlementRepository.findByMerchantIdAndSettlementPeriodBetween(
                request.getMerchantId(), startDate, endDate);
    }

    @Override
    @Scheduled(cron = "0 0 1 * * ?")
    public void processDailySettlement() {
        logger.info("开始执行每日结算任务");
        
        refreshPrecomputationSchedule();
        
        List<SettlementPeriod> periods = settlementPeriodService.getAllEnabledPeriods();
        
        for (SettlementPeriod period : periods) {
            if (settlementPeriodService.shouldSettleNow(period)) {
                logger.info("执行结算周期: {}", period.getPeriodId());
            }
        }
        
        logger.info("每日结算任务执行完成");
    }

    @Scheduled(fixedDelay = 300000)
    public void refreshPrecomputationSchedule() {
        if (precomputationService == null) {
            return;
        }
        
        try {
            precomputationService.refreshPrecomputationSchedule();
            
            long merchantCount = precomputationService.getActiveMerchantCount();
            int currentMinutes = precomputationService.getCurrentPrecomputationMinutes();
            
            logger.info("预计算调度刷新完成: 活跃商户数={}, 当前预计算时间={}分钟", merchantCount, currentMinutes);
        } catch (Exception e) {
            logger.error("预计算调度刷新失败", e);
        }
    }

    @Scheduled(cron = "0 30 23 * * ?")
    public void executePrecomputation() {
        if (!precomputationRunning.compareAndSet(false, true)) {
            logger.warn("预计算任务已在运行中，跳过本次执行");
            return;
        }
        
        try {
            LocalDateTime startTime = LocalDateTime.now();
            lastPrecomputationTime.set(startTime);
            
            long merchantCount = precomputationService.getActiveMerchantCount();
            int precomputationMinutes = precomputationService.getCurrentPrecomputationMinutes();
            
            logger.info("开始执行结算预计算: 商户数量={}, 预计耗时={}分钟", merchantCount, precomputationMinutes);
            
            List<String> merchantIds = merchantConfigService.getAllMerchantIds();
            
            for (String merchantId : merchantIds) {
                try {
                    executeMerchantPrecomputation(merchantId);
                } catch (Exception e) {
                    logger.error("商户预计算失败: merchantId={}", merchantId, e);
                }
            }
            
            LocalDateTime endTime = LocalDateTime.now();
            Duration duration = Duration.between(startTime, endTime);
            
            logger.info("结算预计算完成: 耗时={}秒, 商户数={}", 
                    duration.getSeconds(), merchantIds.size());
            
        } finally {
            precomputationRunning.set(false);
        }
    }

    private void executeMerchantPrecomputation(String merchantId) {
        logger.debug("执行商户预计算: merchantId={}", merchantId);
        
        List<SettlementPeriod> periods = settlementPeriodService.generatePeriodsForMerchant(merchantId);
        
        if (!periods.isEmpty()) {
            logger.info("商户预计算生成结算周期: merchantId={}, periods={}", merchantId, periods.size());
        }
    }

    public LocalDateTime getLastPrecomputationTime() {
        return lastPrecomputationTime.get();
    }

    public boolean isPrecomputationRunning() {
        return precomputationRunning.get();
    }
}
