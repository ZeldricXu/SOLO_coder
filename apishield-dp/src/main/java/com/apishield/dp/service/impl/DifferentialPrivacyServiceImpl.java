package com.apishield.dp.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.dp.domain.DpQueryLog;
import com.apishield.dp.domain.PrivacyBudget;
import com.apishield.dp.dto.BudgetConsumptionRequest;
import com.apishield.dp.dto.DpQueryRequest;
import com.apishield.dp.dto.DpQueryResponse;
import com.apishield.dp.noise.NoiseGenerator;
import com.apishield.dp.service.DifferentialPrivacyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifferentialPrivacyServiceImpl implements DifferentialPrivacyService {

    private final List<NoiseGenerator> noiseGenerators;
    private final Map<String, PrivacyBudget> budgetStore = new ConcurrentHashMap<>();
    private final Map<String, DpQueryLog> logStore = new ConcurrentHashMap<>();

    @Override
    public DpQueryResponse executeQuery(DpQueryRequest request) {
        DpQueryResponse response = new DpQueryResponse();
        response.setQueryId(request.getQueryId());
        response.setOriginalResult(request.getOriginalResult());

        PrivacyBudget budget = getOrCreateBudget(request.getUserId(), request.getDataSource());
        
        if (!hasSufficientBudget(request.getUserId(), request.getDataSource(), request.getEpsilon(), request.getDelta())) {
            response.setBudgetExceeded(true);
            response.setMessage("隐私预算不足");
            response.setNoisyResult(request.getOriginalResult());
            log.warn("Privacy budget exceeded for user {} on dataSource {}", 
                    request.getUserId(), request.getDataSource());
            return response;
        }

        NoiseGenerator noiseGenerator = getNoiseGenerator(request.getNoiseType());
        double noiseScale = request.getSensitivity() / request.getEpsilon();
        double noisyResult = noiseGenerator.addNoise(
                request.getOriginalResult(), 
                request.getSensitivity(), 
                request.getEpsilon(), 
                request.getDelta());

        consumeBudget(new BudgetConsumptionRequest() {{
            setUserId(request.getUserId());
            setDataSource(request.getDataSource());
            setEpsilon(request.getEpsilon());
            setDelta(request.getDelta());
        }});

        DpQueryLog queryLog = createQueryLog(request, noisyResult, noiseScale);

        response.setNoisyResult(noisyResult);
        response.setEpsilonConsumed(request.getEpsilon());
        response.setDeltaConsumed(request.getDelta());
        response.setRemainingEpsilon(budget.getRemainingEpsilon());
        response.setRemainingDelta(budget.getRemainingDelta());
        response.setNoiseType(noiseGenerator.getNoiseType());
        response.setNoiseScale(noiseScale);
        response.setBudgetExceeded(false);
        response.setMessage("差分隐私噪声注入成功");

        log.info("Executed DP query: {}, epsilon: {}, delta: {}, noisy result: {}", 
                request.getQueryId(), request.getEpsilon(), request.getDelta(), noisyResult);
        return response;
    }

    @Override
    public PrivacyBudget createBudget(String userId, String dataSource, double totalEpsilon, 
                                       double totalDelta, String resetPeriod, boolean autoReset) {
        PrivacyBudget budget = new PrivacyBudget();
        budget.setId(IdGenerator.generateId("budget"));
        budget.setBudgetId(budget.getId());
        budget.setUserId(userId);
        budget.setDataSource(dataSource);
        budget.setTotalEpsilon(totalEpsilon);
        budget.setTotalDelta(totalDelta);
        budget.setConsumedEpsilon(0);
        budget.setConsumedDelta(0);
        budget.setRemainingEpsilon(totalEpsilon);
        budget.setRemainingDelta(totalDelta);
        budget.setResetPeriod(resetPeriod);
        budget.setAutoReset(autoReset);
        budget.setStatus("ACTIVE");
        budget.setCreatedAt(LocalDateTime.now());
        budget.setUpdatedAt(LocalDateTime.now());

        if ("DAILY".equals(resetPeriod)) {
            budget.setResetTime(LocalDateTime.now().plusDays(1));
        } else if ("WEEKLY".equals(resetPeriod)) {
            budget.setResetTime(LocalDateTime.now().plusWeeks(1));
        } else if ("MONTHLY".equals(resetPeriod)) {
            budget.setResetTime(LocalDateTime.now().plusMonths(1));
        }

        budgetStore.put(budget.getBudgetId(), budget);
        log.info("Created privacy budget: {} for user {} on dataSource {}", 
                budget.getBudgetId(), userId, dataSource);
        return budget;
    }

    @Override
    public PrivacyBudget getBudget(String budgetId) {
        PrivacyBudget budget = budgetStore.get(budgetId);
        if (budget == null) {
            throw new BusinessException("NOT_FOUND", "隐私预算不存在: " + budgetId);
        }
        checkAndResetBudget(budget);
        return budget;
    }

    @Override
    public PrivacyBudget getBudgetByUserAndDataSource(String userId, String dataSource) {
        return budgetStore.values().stream()
                .filter(b -> userId.equals(b.getUserId()) && dataSource.equals(b.getDataSource()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<PrivacyBudget> getBudgetsByUser(String userId) {
        return budgetStore.values().stream()
                .filter(b -> userId.equals(b.getUserId()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean consumeBudget(BudgetConsumptionRequest request) {
        PrivacyBudget budget = getOrCreateBudget(request.getUserId(), request.getDataSource());
        
        checkAndResetBudget(budget);

        if (budget.getRemainingEpsilon() < request.getEpsilon() || 
            budget.getRemainingDelta() < request.getDelta()) {
            throw new BusinessException("DP_001", "隐私预算不足");
        }

        budget.setConsumedEpsilon(budget.getConsumedEpsilon() + request.getEpsilon());
        budget.setConsumedDelta(budget.getConsumedDelta() + request.getDelta());
        budget.setRemainingEpsilon(budget.getRemainingEpsilon() - request.getEpsilon());
        budget.setRemainingDelta(budget.getRemainingDelta() - request.getDelta());
        budget.setUpdatedAt(LocalDateTime.now());

        log.debug("Consumed budget: epsilon={}, delta={}, remaining epsilon={}, remaining delta={}", 
                request.getEpsilon(), request.getDelta(), 
                budget.getRemainingEpsilon(), budget.getRemainingDelta());
        return true;
    }

    @Override
    public PrivacyBudget resetBudget(String budgetId) {
        PrivacyBudget budget = getBudget(budgetId);
        budget.setConsumedEpsilon(0);
        budget.setConsumedDelta(0);
        budget.setRemainingEpsilon(budget.getTotalEpsilon());
        budget.setRemainingDelta(budget.getTotalDelta());
        budget.setUpdatedAt(LocalDateTime.now());
        
        if ("DAILY".equals(budget.getResetPeriod())) {
            budget.setResetTime(LocalDateTime.now().plusDays(1));
        } else if ("WEEKLY".equals(budget.getResetPeriod())) {
            budget.setResetTime(LocalDateTime.now().plusWeeks(1));
        } else if ("MONTHLY".equals(budget.getResetPeriod())) {
            budget.setResetTime(LocalDateTime.now().plusMonths(1));
        }

        log.info("Reset privacy budget: {}", budgetId);
        return budget;
    }

    @Override
    public DpQueryLog getQueryLog(String logId) {
        DpQueryLog log = logStore.get(logId);
        if (log == null) {
            throw new BusinessException("NOT_FOUND", "查询日志不存在: " + logId);
        }
        return log;
    }

    @Override
    public List<DpQueryLog> getQueryLogsByUser(String userId, int page, int size) {
        return logStore.values().stream()
                .filter(l -> userId.equals(l.getUserId()))
                .sorted((a, b) -> b.getQueryTime().compareTo(a.getQueryTime()))
                .skip((long) (page - 1) * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public List<DpQueryLog> getQueryLogsByDataSource(String dataSource, int page, int size) {
        return logStore.values().stream()
                .filter(l -> dataSource.equals(l.getDataSource()))
                .sorted((a, b) -> b.getQueryTime().compareTo(a.getQueryTime()))
                .skip((long) (page - 1) * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public double calculateNoise(String noiseType, double sensitivity, double epsilon, double delta) {
        NoiseGenerator generator = getNoiseGenerator(noiseType);
        return generator.generateNoise(sensitivity, epsilon, delta);
    }

    @Override
    public boolean hasSufficientBudget(String userId, String dataSource, double epsilon, double delta) {
        PrivacyBudget budget = getOrCreateBudget(userId, dataSource);
        checkAndResetBudget(budget);
        return budget.getRemainingEpsilon() >= epsilon && budget.getRemainingDelta() >= delta;
    }

    private PrivacyBudget getOrCreateBudget(String userId, String dataSource) {
        PrivacyBudget budget = getBudgetByUserAndDataSource(userId, dataSource);
        if (budget == null) {
            budget = createBudget(userId, dataSource, 10.0, 0.0001, "DAILY", true);
        }
        return budget;
    }

    private void checkAndResetBudget(PrivacyBudget budget) {
        if (budget.isAutoReset() && budget.getResetTime() != null && 
            LocalDateTime.now().isAfter(budget.getResetTime())) {
            resetBudget(budget.getBudgetId());
        }
    }

    private NoiseGenerator getNoiseGenerator(String noiseType) {
        if (noiseType == null) {
            return noiseGenerators.get(0);
        }
        return noiseGenerators.stream()
                .filter(g -> noiseType.equalsIgnoreCase(g.getNoiseType()))
                .findFirst()
                .orElse(noiseGenerators.get(0));
    }

    private DpQueryLog createQueryLog(DpQueryRequest request, double noisyResult, double noiseScale) {
        DpQueryLog queryLog = new DpQueryLog();
        queryLog.setId(IdGenerator.generateId("dplog"));
        queryLog.setLogId(queryLog.getId());
        queryLog.setQueryId(request.getQueryId());
        queryLog.setUserId(request.getUserId());
        queryLog.setDataSource(request.getDataSource());
        queryLog.setQueryType(request.getQueryType());
        queryLog.setEpsilon(request.getEpsilon());
        queryLog.setDelta(request.getDelta());
        queryLog.setSensitivity(request.getSensitivity());
        queryLog.setNoiseType(request.getNoiseType());
        queryLog.setNoiseScale(noiseScale);
        queryLog.setOriginalResult(request.getOriginalResult());
        queryLog.setNoisyResult(noisyResult);
        queryLog.setQueryTime(LocalDateTime.now());
        queryLog.setBudgetExceeded(false);
        queryLog.setQueryParams(request.getQueryParams());
        queryLog.setCreatedAt(LocalDateTime.now());
        queryLog.setUpdatedAt(LocalDateTime.now());

        logStore.put(queryLog.getLogId(), queryLog);
        return queryLog;
    }
}
