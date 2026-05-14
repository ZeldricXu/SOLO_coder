package com.healthtrack.service;

import com.healthtrack.entity.HealthAdvice;
import com.healthtrack.entity.HealthGoal;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.HealthAdviceRepository;
import com.healthtrack.repository.HealthGoalRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AdvicePushService {

    private static final Logger logger = LoggerFactory.getLogger(AdvicePushService.class);

    private final Map<String, Long> lastPushTimeMap = new ConcurrentHashMap<>();

    @Autowired
    private HealthAdviceRepository healthAdviceRepository;

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    @Autowired
    private HealthGoalRepository healthGoalRepository;

    @Autowired
    private DeduplicationConfigService deduplicationConfigService;

    @Autowired
    private AdviceRuleService adviceRuleService;

    @Async("advicePushExecutor")
    public CompletableFuture<HealthAdvice> generateAdviceAsync(String userId, String dataType) {
        logger.info("开始异步生成建议: userId={}, dataType={}", userId, dataType);
        
        Optional<HealthIndicator> indicatorOpt = 
                healthIndicatorRepository.findByUserIdAndIndicatorType(userId, dataType);
        
        if (indicatorOpt.isEmpty()) {
            logger.warn("未找到指标数据: userId={}, dataType={}", userId, dataType);
            return CompletableFuture.completedFuture(null);
        }
        
        HealthIndicator indicator = indicatorOpt.get();
        HealthAdvice advice = generateAndSaveAdvice(userId, indicator);
        
        if (advice != null) {
            pushAdviceIfNeeded(advice);
        }
        
        return CompletableFuture.completedFuture(advice);
    }

    public void generateAdviceIfNeeded(String userId, String dataType) {
        Optional<HealthIndicator> indicatorOpt = 
                healthIndicatorRepository.findByUserIdAndIndicatorType(userId, dataType);
        
        if (indicatorOpt.isPresent()) {
            HealthIndicator indicator = indicatorOpt.get();
            generateAndSaveAdvice(userId, indicator);
        }
    }

    public HealthAdvice generateAndSaveAdvice(String userId, HealthIndicator indicator) {
        String priority;
        String adviceType;
        String adviceContent;
        
        AdviceRuleService.AdviceRuleMatchResult ruleMatch = 
                adviceRuleService.matchRule(userId, indicator);
        
        if (ruleMatch != null) {
            logger.info("使用配置规则生成建议: userId={}, indicatorType={}, rule={}", 
                    userId, indicator.getIndicatorType(), ruleMatch.getRule().getRuleName());
            priority = ruleMatch.getPriority();
            adviceType = ruleMatch.getAdviceType();
            adviceContent = ruleMatch.getAdviceContent();
        } else {
            logger.info("使用默认逻辑生成建议: userId={}, indicatorType={}", 
                    userId, indicator.getIndicatorType());
            
            if ("abnormal".equals(indicator.getStatus())) {
                priority = "high";
                adviceType = determineAdviceType(indicator.getIndicatorType());
                adviceContent = generateAbnormalAdvice(indicator);
            } else if (isGoalProgressLagging(userId, indicator.getIndicatorType())) {
                priority = "medium";
                adviceType = "goal";
                adviceContent = generateGoalAdvice(indicator);
            } else {
                priority = "low";
                adviceType = "maintenance";
                adviceContent = generateMaintenanceAdvice(indicator);
            }
        }
        
        String dedupKey = buildDeduplicationKey(userId, adviceType, adviceContent, priority);
        
        if (!isDuplicateAdvice(userId, adviceType, adviceContent, priority)) {
            HealthAdvice advice = new HealthAdvice();
            advice.setAdviceId("advice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
            advice.setUserId(userId);
            advice.setAdviceType(adviceType);
            advice.setAdviceContent(adviceContent);
            advice.setPriority(priority);
            advice.setBasedIndicators(indicator.getIndicatorType());
            advice = healthAdviceRepository.save(advice);
            
            lastPushTimeMap.put(dedupKey, System.currentTimeMillis());
            logger.info("生成新建议: userId={}, adviceId={}, priority={}", userId, advice.getAdviceId(), priority);
            return advice;
        } else {
            logger.info("建议重复，跳过: userId={}, type={}, priority={}", userId, adviceType, priority);
            return null;
        }
    }

    private String buildDeduplicationKey(String userId, String adviceType, String content, String priority) {
        return userId + ":" + adviceType + ":" + priority + ":" + content.hashCode();
    }

    public boolean isDuplicateAdvice(String userId, String adviceType, String adviceContent, String priority) {
        int windowMinutes = getDeduplicationWindowMinutes(priority);
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(windowMinutes);
        
        List<HealthAdvice> recentAdvices = healthAdviceRepository.findByUserIdAndGeneratedAtAfter(userId, windowStart);
        
        return recentAdvices.stream()
                .anyMatch(a -> a.getAdviceType().equals(adviceType) 
                        && a.getAdviceContent().equals(adviceContent)
                        && a.getPriority().equals(priority));
    }

    public int getDeduplicationWindowMinutes(String priority) {
        return deduplicationConfigService.getWindowMinutes(priority);
    }

    public long getDeduplicationWindowMs(String priority) {
        return deduplicationConfigService.getWindowMillis(priority);
    }

    public long getDeduplicationWindowHours(String priority) {
        return (long) getDeduplicationWindowMinutes(priority) / 60;
    }

    public List<HealthAdvice> aggregateAdvicesByPriority(String userId, int maxCount) {
        List<HealthAdvice> allAdvices = healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId);
        
        Map<String, List<HealthAdvice>> groupedByPriority = allAdvices.stream()
                .collect(Collectors.groupingBy(HealthAdvice::getPriority));
        
        List<HealthAdvice> result = new ArrayList<>();
        
        List<String> priorityOrder = Arrays.asList("high", "medium", "low");
        for (String priority : priorityOrder) {
            List<HealthAdvice> priorityAdvices = groupedByPriority.getOrDefault(priority, Collections.emptyList());
            int remaining = maxCount - result.size();
            if (remaining > 0 && !priorityAdvices.isEmpty()) {
                List<HealthAdvice> aggregated = aggregateSimilarAdvices(priorityAdvices);
                result.addAll(aggregated.stream().limit(remaining).collect(Collectors.toList()));
            }
        }
        
        return result;
    }

    private List<HealthAdvice> aggregateSimilarAdvices(List<HealthAdvice> advices) {
        Map<String, List<HealthAdvice>> groupedByType = advices.stream()
                .collect(Collectors.groupingBy(HealthAdvice::getAdviceType));
        
        List<HealthAdvice> result = new ArrayList<>();
        for (Map.Entry<String, List<HealthAdvice>> entry : groupedByType.entrySet()) {
            List<HealthAdvice> sameType = entry.getValue();
            if (sameType.size() > 1) {
                HealthAdvice first = sameType.get(0);
                first.setAdviceContent(first.getAdviceContent() + 
                        " (共" + sameType.size() + "条同类建议)");
                result.add(first);
            } else {
                result.addAll(sameType);
            }
        }
        
        return result;
    }

    private void pushAdviceIfNeeded(HealthAdvice advice) {
        String dedupKey = buildDeduplicationKey(advice.getUserId(), advice.getAdviceType(), 
                advice.getAdviceContent(), advice.getPriority());
        long lastPush = lastPushTimeMap.getOrDefault(dedupKey, 0L);
        long windowMs = getDeduplicationWindowMs(advice.getPriority());
        
        logger.debug("检查去重窗口: adviceId={}, priority={}, windowMs={}, lastPushAgeMs={}", 
                advice.getAdviceId(), advice.getPriority(), windowMs, 
                System.currentTimeMillis() - lastPush);
        
        if (System.currentTimeMillis() - lastPush > windowMs) {
            advice.setPushed(true);
            advice.setPushedAt(LocalDateTime.now());
            healthAdviceRepository.save(advice);
            lastPushTimeMap.put(dedupKey, System.currentTimeMillis());
            logger.info("推送建议: adviceId={}, userId={}, priority={}, windowMinutes={}", 
                    advice.getAdviceId(), advice.getUserId(), advice.getPriority(),
                    getDeduplicationWindowMinutes(advice.getPriority()));
        }
    }

    private String determineAdviceType(String indicatorType) {
        switch (indicatorType.toLowerCase()) {
            case "heart_rate":
            case "blood_pressure_systolic":
            case "blood_pressure_diastolic":
                return "cardiovascular";
            case "weight":
                return "weight";
            case "steps":
                return "exercise";
            case "sleep_hours":
                return "sleep";
            case "temperature":
                return "health";
            default:
                return "general";
        }
    }

    private String generateAbnormalAdvice(HealthIndicator indicator) {
        String type = indicator.getIndicatorType().toLowerCase();
        Double value = indicator.getCurrentValue();
        
        switch (type) {
            case "heart_rate":
                if (value < 60) {
                    return "您的心率偏低（" + value + " bpm），建议适当活动身体，如有不适请及时就医。";
                } else {
                    return "您的心率偏高（" + value + " bpm），建议放松心情、减少剧烈运动，如持续偏高请咨询医生。";
                }
            case "weight":
                if (value < 40) {
                    return "您的体重偏低（" + value + " kg），建议增加营养摄入，保持均衡饮食。";
                } else {
                    return "您的体重偏高（" + value + " kg），建议控制饮食，增加运动，保持健康的生活方式。";
                }
            case "blood_pressure_systolic":
                if (value < 90) {
                    return "您的收缩压偏低（" + value + " mmHg），建议多喝水，避免突然站立。";
                } else {
                    return "您的收缩压偏高（" + value + " mmHg），建议低盐饮食，避免情绪激动，定期监测。";
                }
            case "blood_pressure_diastolic":
                if (value < 60) {
                    return "您的舒张压偏低（" + value + " mmHg），建议注意休息，保持充足睡眠。";
                } else {
                    return "您的舒张压偏高（" + value + " mmHg），建议减轻压力，戒烟限酒。";
                }
            case "temperature":
                if (value < 36.5) {
                    return "您的体温偏低（" + value + " °C），建议注意保暖，适当喝温水。";
                } else {
                    return "您的体温偏高（" + value + " °C），可能有发热情况，建议多喝水、休息，如持续发热请就医。";
                }
            case "steps":
                return "您今日步数较少，建议适当增加运动，每天至少步行30分钟。";
            case "sleep_hours":
                if (value < 6) {
                    return "您的睡眠时间不足（" + value + " 小时），建议保持规律作息，保证充足睡眠。";
                } else {
                    return "您的睡眠时间过长（" + value + " 小时），建议保持规律作息，适当增加活动。";
                }
            default:
                return "建议您关注健康指标变化，保持良好的生活习惯。";
        }
    }

    private String generateGoalAdvice(HealthIndicator indicator) {
        return "您的" + indicator.getIndicatorType() + "目标进度有待提升，当前值为" + indicator.getCurrentValue() + 
               "，目标值为" + indicator.getTargetValue() + "。建议继续努力，坚持健康的生活方式。";
    }

    private String generateMaintenanceAdvice(HealthIndicator indicator) {
        return "您的" + indicator.getIndicatorType() + "指标保持良好（" + indicator.getCurrentValue() + "），继续保持健康的生活习惯！";
    }

    private boolean isGoalProgressLagging(String userId, String indicatorType) {
        Optional<HealthGoal> goalOpt = healthGoalRepository.findByUserIdAndGoalType(userId, indicatorType);
        if (goalOpt.isPresent()) {
            HealthGoal goal = goalOpt.get();
            return goal.getProgress() != null && goal.getProgress() < 50 && "in_progress".equals(goal.getStatus());
        }
        return false;
    }

    public List<HealthAdvice> getUserAdvices(String userId) {
        return healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId);
    }

    public List<HealthAdvice> getUnreadAdvices(String userId) {
        return healthAdviceRepository.findByUserIdAndReadStatus(userId, "unread");
    }

    public void markAdviceAsRead(String adviceId) {
        healthAdviceRepository.findById(adviceId).ifPresent(advice -> {
            advice.setReadStatus("read");
            healthAdviceRepository.save(advice);
        });
    }

    public void pushPendingAdvices(String userId) {
        List<HealthAdvice> pending = healthAdviceRepository.findByUserIdAndPushedFalse(userId);
        for (HealthAdvice advice : pending) {
            advice.setPushed(true);
            advice.setPushedAt(LocalDateTime.now());
            healthAdviceRepository.save(advice);
        }
    }

    public void clearDeduplicationCache() {
        lastPushTimeMap.clear();
    }
}
