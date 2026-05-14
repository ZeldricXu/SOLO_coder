package com.adplatform.service;

import com.adplatform.entity.AdBudget;
import com.adplatform.entity.AdConsume;
import com.adplatform.entity.AdHistory;
import com.adplatform.entity.AdInfo;
import com.adplatform.exception.BusinessException;
import com.adplatform.lock.DistributedLockService;
import com.adplatform.repository.AdBudgetRepository;
import com.adplatform.repository.AdConsumeRepository;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class BudgetService {
    private static final Logger logger = LoggerFactory.getLogger(BudgetService.class);
    private static final String BUDGET_LOCK_PREFIX = "budget:lock:";
    
    private final AdBudgetRepository adBudgetRepository;
    private final AdConsumeRepository adConsumeRepository;
    private final AdInfoRepository adInfoRepository;
    private final AdHistoryRepository adHistoryRepository;
    private final DistributedLockService distributedLockService;
    private final StatusService statusService;

    public BudgetService(AdBudgetRepository adBudgetRepository,
                        AdConsumeRepository adConsumeRepository,
                        AdInfoRepository adInfoRepository,
                        AdHistoryRepository adHistoryRepository,
                        DistributedLockService distributedLockService,
                        StatusService statusService) {
        this.adBudgetRepository = adBudgetRepository;
        this.adConsumeRepository = adConsumeRepository;
        this.adInfoRepository = adInfoRepository;
        this.adHistoryRepository = adHistoryRepository;
        this.distributedLockService = distributedLockService;
        this.statusService = statusService;
    }

    @Transactional
    public AdBudget createBudget(String adId, String budgetType, BigDecimal budgetAmount, BigDecimal budgetThreshold) {
        if (!adInfoRepository.existsById(adId)) {
            throw new BusinessException(404, "广告不存在");
        }

        if (budgetAmount == null || budgetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "预算金额必须大于0");
        }

        if (budgetThreshold == null) {
            budgetThreshold = budgetAmount.multiply(new BigDecimal("0.1"));
        }

        AdBudget adBudget = AdBudget.builder()
                .budgetId(IdGenerator.generateId("budget"))
                .adId(adId)
                .budgetType(budgetType != null ? budgetType : "total")
                .budgetAmount(budgetAmount)
                .budgetRemaining(budgetAmount)
                .budgetThreshold(budgetThreshold)
                .build();
        
        adBudgetRepository.save(adBudget);
        logger.info("预算配置创建成功: adId={}, budgetId={}, amount={}", adId, adBudget.getBudgetId(), budgetAmount);

        recordHistory(adId, adBudget, "BUDGET_CREATED");
        return adBudget;
    }

    public boolean consumeBudget(String adId, String consumeType, BigDecimal consumeAmount) {
        String emergencyLevel = getAdEmergencyLevel(adId);
        String lockKey = BUDGET_LOCK_PREFIX + adId;
        
        logger.debug("开始预算扣减: adId={}, emergencyLevel={}, consumeAmount={}", 
                adId, emergencyLevel, consumeAmount);
        
        return distributedLockService.executeWithLock(lockKey, emergencyLevel, () -> {
            Optional<AdBudget> budgetOpt = adBudgetRepository.findActiveBudgetByAdId(adId);
            if (budgetOpt.isEmpty()) {
                logger.warn("广告无可用预算: {}", adId);
                return false;
            }

            AdBudget budget = budgetOpt.get();
            
            if (budget.getBudgetRemaining().compareTo(consumeAmount) < 0) {
                logger.warn("广告预算不足: adId={}, remaining={}, consume={}", adId, budget.getBudgetRemaining(), consumeAmount);
                handleBudgetExhausted(adId);
                return false;
            }

            budget.setBudgetConsumed(budget.getBudgetConsumed().add(consumeAmount));
            budget.setBudgetRemaining(budget.getBudgetRemaining().subtract(consumeAmount));
            adBudgetRepository.save(budget);

            recordConsume(adId, consumeType, consumeAmount);

            checkBudgetThreshold(adId, budget);

            if (budget.getBudgetRemaining().compareTo(BigDecimal.ZERO) <= 0) {
                handleBudgetExhausted(adId);
            }

            logger.debug("预算扣减成功: adId={}, amount={}, remaining={}", adId, consumeAmount, budget.getBudgetRemaining());
            return true;
        });
    }

    public Optional<AdBudget> getBudgetByAdId(String adId) {
        return adBudgetRepository.findActiveBudgetByAdId(adId);
    }

    public BigDecimal getBudgetRemaining(String adId) {
        return getBudgetByAdId(adId)
                .map(AdBudget::getBudgetRemaining)
                .orElse(BigDecimal.ZERO);
    }

    public boolean hasEnoughBudget(String adId, BigDecimal amount) {
        return getBudgetRemaining(adId).compareTo(amount) >= 0;
    }

    public String getAdEmergencyLevel(String adId) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isPresent()) {
            AdInfo adInfo = adInfoOpt.get();
            String emergencyLevel = adInfo.getEmergencyLevel();
            return emergencyLevel != null ? emergencyLevel : "normal";
        }
        logger.warn("广告不存在，使用默认紧急程度: normal");
        return "normal";
    }

    private void handleBudgetExhausted(String adId) {
        logger.warn("广告预算耗尽: {}", adId);
        if (statusService.isAdRunning(adId)) {
            statusService.updateStatus(adId, "ended", "预算耗尽");
        }
        
        Optional<AdBudget> budgetOpt = adBudgetRepository.findActiveBudgetByAdId(adId);
        budgetOpt.ifPresent(budget -> recordHistory(adId, budget, "BUDGET_EXHAUSTED"));
    }

    private void checkBudgetThreshold(String adId, AdBudget budget) {
        if (budget.getBudgetRemaining().compareTo(budget.getBudgetThreshold()) <= 0) {
            logger.warn("广告预算预警: adId={}, remaining={}, threshold={}", 
                    adId, budget.getBudgetRemaining(), budget.getBudgetThreshold());
            recordHistory(adId, budget, "BUDGET_THRESHOLD_REACHED");
        }
    }

    private void recordConsume(String adId, String consumeType, BigDecimal consumeAmount) {
        AdConsume consume = AdConsume.builder()
                .consumeId(IdGenerator.generateId("consume"))
                .adId(adId)
                .consumeType(consumeType)
                .consumeAmount(consumeAmount)
                .consumeTime(LocalDateTime.now())
                .build();
        adConsumeRepository.save(consume);
    }

    private void recordHistory(String adId, AdBudget budget, String historyType) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("budgetId", budget.getBudgetId());
        historyData.put("budgetType", budget.getBudgetType());
        historyData.put("budgetAmount", budget.getBudgetAmount());
        historyData.put("budgetConsumed", budget.getBudgetConsumed());
        historyData.put("budgetRemaining", budget.getBudgetRemaining());
        
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType(historyType)
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
