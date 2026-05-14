package com.assetmanage.cache;

import com.assetmanage.config.DepreciationConfigProperties;
import com.assetmanage.depreciation.DepreciationCalculatorFactory;
import com.assetmanage.entity.Asset;
import com.assetmanage.service.AssetService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DepreciationPreCalculator {

    private final AssetService assetService;
    private final DepreciationCalculatorFactory calculatorFactory;
    private final DepreciationConfigProperties config;

    private final Map<String, PreCalculatedValue> cache = new ConcurrentHashMap<>();
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @Getter
    public static class PreCalculatedValue {
        private final BigDecimal depreciationValue;
        private final BigDecimal accumulatedDepreciation;
        private final BigDecimal currentValue;
        private final LocalDateTime calculatedAt;

        public PreCalculatedValue(BigDecimal depreciationValue, 
                                  BigDecimal accumulatedDepreciation, 
                                  BigDecimal currentValue) {
            this.depreciationValue = depreciationValue;
            this.accumulatedDepreciation = accumulatedDepreciation;
            this.currentValue = currentValue;
            this.calculatedAt = LocalDateTime.now();
        }

        public boolean isValid(int expireMinutes) {
            java.time.Duration age = java.time.Duration.between(calculatedAt, LocalDateTime.now());
            return age.toMinutes() < expireMinutes;
        }
    }

    public String generateCacheKey(String assetId, String period) {
        return assetId + "_" + period;
    }

    public PreCalculatedValue getFromCache(String assetId, String period) {
        if (!config.getPreCalculate().isEnabled()) {
            return null;
        }
        String key = generateCacheKey(assetId, period);
        PreCalculatedValue value = cache.get(key);
        if (value != null && value.isValid(config.getPreCalculate().getCacheExpireMinutes())) {
            log.debug("从缓存获取折旧预计算结果: assetId={}, period={}", assetId, period);
            return value;
        }
        if (value != null) {
            cache.remove(key);
            log.debug("缓存已过期，移除: assetId={}, period={}", assetId, period);
        }
        return null;
    }

    public PreCalculatedValue preCalculate(Asset asset, String period) {
        if (!config.getPreCalculate().isEnabled()) {
            return null;
        }

        String key = generateCacheKey(asset.getAssetId(), period);

        BigDecimal depreciationValue = calculatorFactory.getCalculator(asset.getDepreciationMethod())
                .calculateMonthlyDepreciation(asset);
        
        BigDecimal accumulatedDepreciation = asset.getAccumulatedDepreciation() != null
                ? asset.getAccumulatedDepreciation().add(depreciationValue)
                : depreciationValue;
        BigDecimal currentValue = asset.getPurchasePrice().subtract(accumulatedDepreciation);
        
        if (currentValue.compareTo(BigDecimal.ZERO) < 0) {
            currentValue = BigDecimal.ZERO;
        }

        PreCalculatedValue value = new PreCalculatedValue(depreciationValue, accumulatedDepreciation, currentValue);
        cache.put(key, value);
        
        log.debug("预计算折旧完成: assetId={}, period={}, value={}", 
                asset.getAssetId(), period, depreciationValue);
        
        return value;
    }

    public PreCalculatedValue getOrCalculate(Asset asset, String period) {
        PreCalculatedValue cached = getFromCache(asset.getAssetId(), period);
        if (cached != null) {
            return cached;
        }
        return preCalculate(asset, period);
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledPreCalculate() {
        if (!config.getPreCalculate().isEnabled()) {
            log.info("折旧预计算已禁用，跳过定时任务");
            return;
        }

        int preCalculateDays = config.getPreCalculate().getPreCalculateDays();
        String nextPeriod = LocalDate.now().plusDays(preCalculateDays).format(PERIOD_FORMATTER);

        log.info("开始折旧预计算定时任务，目标周期: {}", nextPeriod);

        java.util.List<Asset> activeAssets = assetService.getActiveAssets();
        int successCount = 0;
        int failCount = 0;
        long startTime = System.currentTimeMillis();

        for (Asset asset : activeAssets) {
            try {
                preCalculate(asset, nextPeriod);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("预计算折旧失败: assetId={}", asset.getAssetId(), e);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("折旧预计算定时任务完成: 总计={}, 成功={}, 失败={}, 耗时={}ms",
                activeAssets.size(), successCount, failCount, elapsed);
    }

    public void preCalculateForBatch(java.util.List<Asset> assets, String period) {
        if (!config.getPreCalculate().isEnabled()) {
            return;
        }

        log.info("批量预计算折旧: 资产数量={}, 周期={}", assets.size(), period);
        long startTime = System.currentTimeMillis();

        for (Asset asset : assets) {
            try {
                preCalculate(asset, period);
            } catch (Exception e) {
                log.warn("批量预计算失败: assetId={}", asset.getAssetId(), e);
            }
        }

        log.info("批量预计算完成，耗时={}ms", System.currentTimeMillis() - startTime);
    }

    public void evictCache(String assetId, String period) {
        String key = generateCacheKey(assetId, period);
        cache.remove(key);
        log.debug("清除缓存: assetId={}, period={}", assetId, period);
    }

    public void evictAllCache() {
        cache.clear();
        log.info("清除所有折旧预计算缓存");
    }

    public int getCacheSize() {
        return cache.size();
    }

    public int cleanExpiredCache() {
        int expireMinutes = config.getPreCalculate().getCacheExpireMinutes();
        int removedCount = 0;
        
        for (java.util.Map.Entry<String, PreCalculatedValue> entry : cache.entrySet()) {
            if (!entry.getValue().isValid(expireMinutes)) {
                cache.remove(entry.getKey());
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            log.info("清理过期缓存: {} 条", removedCount);
        }
        
        return removedCount;
    }

    @Scheduled(fixedRate = 300000)
    public void scheduledCleanup() {
        if (config.getPreCalculate().isEnabled()) {
            cleanExpiredCache();
        }
    }
}
