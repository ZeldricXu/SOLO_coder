package com.paycenter.service.impl;

import com.paycenter.repository.MerchantConfigRepository;
import com.paycenter.service.PrecomputationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PrecomputationServiceImpl implements PrecomputationService {

    private static final Logger logger = LoggerFactory.getLogger(PrecomputationServiceImpl.class);

    @Value("${settlement.precomputation.base-time-minutes:30}")
    private int basePrecomputationMinutes;

    @Value("${settlement.precomputation.min-merchants:10}")
    private int minMerchants;

    @Value("${settlement.precomputation.max-merchants:1000}")
    private int maxMerchants;

    @Value("${settlement.precomputation.min-minutes:10}")
    private int minPrecomputationMinutes;

    @Value("${settlement.precomputation.max-minutes:120}")
    private int maxPrecomputationMinutes;

    @Value("${settlement.precomputation.start-time:00:00}")
    private String startTimeStr;

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    private final AtomicInteger currentPrecomputationMinutes = new AtomicInteger(30);
    private final AtomicReference<LocalTime> precomputationStartTime = new AtomicReference<>(LocalTime.of(0, 0));
    private final AtomicReference<LocalDateTime> lastRefreshTime = new AtomicReference<>(null);

    @PostConstruct
    public void init() {
        parseStartTime();
        refreshPrecomputationSchedule();
        logger.info("预计算服务初始化完成，基础预计算时间: {} 分钟", basePrecomputationMinutes);
    }

    private void parseStartTime() {
        try {
            String[] parts = startTimeStr.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            precomputationStartTime.set(LocalTime.of(hour, minute));
            logger.info("预计算开始时间配置: {}", precomputationStartTime.get());
        } catch (Exception e) {
            logger.warn("解析预计算开始时间失败，使用默认值 00:00", e);
            precomputationStartTime.set(LocalTime.of(0, 0));
        }
    }

    @Override
    public Duration calculatePrecomputationTime(int merchantCount) {
        int minutes = calculatePrecomputationMinutes(merchantCount);
        return Duration.ofMinutes(minutes);
    }

    private int calculatePrecomputationMinutes(int merchantCount) {
        if (merchantCount <= 0) {
            return minPrecomputationMinutes;
        }

        if (merchantCount <= minMerchants) {
            return minPrecomputationMinutes;
        }

        if (merchantCount >= maxMerchants) {
            return maxPrecomputationMinutes;
        }

        double ratio = (double) (merchantCount - minMerchants) / (maxMerchants - minMerchants);
        int dynamicMinutes = (int) (minPrecomputationMinutes + 
                (maxPrecomputationMinutes - minPrecomputationMinutes) * ratio);

        return Math.min(maxPrecomputationMinutes, Math.max(minPrecomputationMinutes, dynamicMinutes));
    }

    @Override
    public LocalTime getPrecomputationStartTime() {
        return precomputationStartTime.get();
    }

    @Override
    public LocalDateTime calculateNextPrecomputationTime() {
        LocalTime startTime = precomputationStartTime.get();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStartTime = now.with(startTime);
        
        if (now.isAfter(todayStartTime)) {
            return todayStartTime.plusDays(1);
        }
        return todayStartTime;
    }

    @Override
    public long getActiveMerchantCount() {
        try {
            return merchantConfigRepository.countActiveMerchants();
        } catch (Exception e) {
            logger.warn("获取活跃商户数量失败，使用默认值", e);
            return minMerchants;
        }
    }

    @Override
    public int getCurrentPrecomputationMinutes() {
        return currentPrecomputationMinutes.get();
    }

    @Override
    public void refreshPrecomputationSchedule() {
        long merchantCount = getActiveMerchantCount();
        int newMinutes = calculatePrecomputationMinutes((int) merchantCount);
        
        int oldMinutes = currentPrecomputationMinutes.getAndSet(newMinutes);
        lastRefreshTime.set(LocalDateTime.now());

        if (oldMinutes != newMinutes) {
            logger.info("预计算时间已动态调整: 商户数量={}, 原时间={}分钟, 新时间={}分钟",
                    merchantCount, oldMinutes, newMinutes);
        } else {
            logger.debug("预计算时间无需调整: 商户数量={}, 当前时间={}分钟",
                    merchantCount, newMinutes);
        }
    }
}
