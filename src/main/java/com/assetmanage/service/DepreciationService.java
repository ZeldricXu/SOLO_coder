package com.assetmanage.service;

import com.assetmanage.cache.DepreciationPreCalculator;
import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.DepreciationData;
import com.assetmanage.dto.DepreciationItem;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.DepreciationRecord;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.DepreciationRecordRepository;
import com.assetmanage.depreciation.DepreciationCalculatorFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepreciationService {

    private final DepreciationRecordRepository depreciationRepository;
    private final AssetService assetService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final DepreciationCalculatorFactory calculatorFactory;
    private final DepreciationPreCalculator preCalculator;

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal MINIMUM_NET_VALUE = new BigDecimal("100");

    @Transactional
    public void calculateDepreciation(boolean fullCalculation) {
        List<Asset> assets;
        String currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);

        if (fullCalculation) {
            assets = assetService.getActiveAssets();
            log.info("开始全量折旧计算，资产数量: {}", assets.size());
        } else {
            LocalDate today = LocalDate.now();
            assets = assetService.getActiveAssets().stream()
                    .filter(asset -> asset.getCreatedAt() != null &&
                            asset.getCreatedAt().toLocalDate().isAfter(today.minusMonths(1)))
                    .collect(Collectors.toList());
            log.info("开始增量折旧计算，新增资产数量: {}", assets.size());
        }

        if (preCalculator.getCacheSize() == 0) {
            preCalculator.preCalculateForBatch(assets, currentPeriod);
        }

        int cacheHitCount = 0;
        int cacheMissCount = 0;
        long startTime = System.currentTimeMillis();

        for (Asset asset : assets) {
            try {
                boolean usedCache = calculateAssetDepreciationWithCache(asset, currentPeriod);
                if (usedCache) {
                    cacheHitCount++;
                } else {
                    cacheMissCount++;
                }
            } catch (Exception e) {
                log.error("资产折旧计算异常: assetId={}", asset.getAssetId(), e);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        analysisService.updateStatistics();

        log.info("折旧计算完成: 总计={}, 缓存命中={}, 缓存未命中={}, 耗时={}ms",
                assets.size(), cacheHitCount, cacheMissCount, elapsed);
    }

    private boolean calculateAssetDepreciationWithCache(Asset asset, String period) {
        Optional<DepreciationRecord> existingRecord = depreciationRepository.findByAssetIdAndPeriod(asset.getAssetId(), period);
        if (existingRecord.isPresent()) {
            log.debug("当期折旧已计算: assetId={}, period={}", asset.getAssetId(), period);
            return true;
        }

        DepreciationPreCalculator.PreCalculatedValue cached = preCalculator.getFromCache(asset.getAssetId(), period);
        boolean usedCache = cached != null;

        BigDecimal depreciationValue;
        BigDecimal accumulatedDepreciation;
        BigDecimal currentValue;

        if (cached != null) {
            depreciationValue = cached.getDepreciationValue();
            accumulatedDepreciation = cached.getAccumulatedDepreciation();
            currentValue = cached.getCurrentValue();
            log.debug("使用缓存的折旧预计算结果: assetId={}, period={}", asset.getAssetId(), period);
        } else {
            depreciationValue = calculateDepreciationValue(asset);
            accumulatedDepreciation = asset.getAccumulatedDepreciation() != null
                    ? asset.getAccumulatedDepreciation().add(depreciationValue)
                    : depreciationValue;
            currentValue = asset.getPurchasePrice().subtract(accumulatedDepreciation);

            if (currentValue.compareTo(BigDecimal.ZERO) < 0) {
                currentValue = BigDecimal.ZERO;
            }
        }

        createDepreciationRecord(asset, period, depreciationValue, accumulatedDepreciation, currentValue);

        assetService.updateAssetValue(asset.getAssetId(), currentValue, accumulatedDepreciation);

        if (currentValue.compareTo(MINIMUM_NET_VALUE) < 0) {
            log.warn("资产净值过低预警: assetId={}, currentValue={}", asset.getAssetId(), currentValue);
        }

        historyService.recordHistory(asset.getAssetId(), "depreciation",
                "折旧计算: " + period + ", 折旧额: " + depreciationValue + (usedCache ? " (缓存)" : ""), null);

        log.debug("资产折旧计算完成: assetId={}, period={}, value={}, cached={}",
                asset.getAssetId(), period, depreciationValue, usedCache);

        return usedCache;
    }

    @Transactional
    public void calculateAssetDepreciation(Asset asset) {
        String currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);
        calculateAssetDepreciationWithCache(asset, currentPeriod);
    }

    private void createDepreciationRecord(Asset asset, String period, BigDecimal depreciationValue,
                                          BigDecimal accumulatedDepreciation, BigDecimal currentValue) {
        DepreciationRecord record = new DepreciationRecord();
        record.setDepreciationId(IdGenerator.generateDepreciationId());
        record.setAssetId(asset.getAssetId());
        record.setDepreciationPeriod(period);
        record.setDepreciationValue(depreciationValue);
        record.setAccumulatedDepreciation(accumulatedDepreciation);
        record.setCurrentValue(currentValue);
        depreciationRepository.save(record);
    }

    public BigDecimal calculateDepreciationValue(Asset asset) {
        return calculatorFactory.getCalculator(asset.getDepreciationMethod())
                .calculateMonthlyDepreciation(asset);
    }

    public DepreciationData getDepreciationByAssetAndPeriod(String assetId, String startPeriod, String endPeriod) {
        assetService.getAssetById(assetId);

        List<DepreciationRecord> records;
        if (startPeriod != null && endPeriod != null) {
            records = depreciationRepository.findByAssetIdAndPeriodBetween(assetId, startPeriod, endPeriod);
        } else {
            records = depreciationRepository.findByAssetIdOrderByCalculatedAtDesc(assetId);
        }

        List<DepreciationItem> items = records.stream()
                .map(r -> new DepreciationItem(r.getDepreciationPeriod(), r.getDepreciationValue()))
                .collect(Collectors.toList());

        DepreciationData data = new DepreciationData();
        data.setDepreciation(items);
        return data;
    }

    public List<DepreciationRecord> getDepreciationRecordsByAsset(String assetId) {
        return depreciationRepository.findByAssetIdOrderByCalculatedAtDesc(assetId);
    }

    public List<DepreciationRecord> getDepreciationByPeriod(String period) {
        return depreciationRepository.findByDepreciationPeriod(period);
    }

    public List<DepreciationRecord> getAllDepreciationRecords() {
        return depreciationRepository.findAll();
    }

    public BigDecimal getTotalDepreciationForPeriod(String period) {
        List<DepreciationRecord> records = getDepreciationByPeriod(period);
        return records.stream()
                .map(DepreciationRecord::getDepreciationValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public java.util.Map<String, String> getAvailableDepreciationMethods() {
        return calculatorFactory.getAvailableMethods();
    }

    public void preCalculateNextPeriod() {
        String nextPeriod = LocalDate.now().plusMonths(1).format(PERIOD_FORMATTER);
        List<Asset> assets = assetService.getActiveAssets();
        preCalculator.preCalculateForBatch(assets, nextPeriod);
    }

    public int getCacheSize() {
        return preCalculator.getCacheSize();
    }

    public void clearCache() {
        preCalculator.evictAllCache();
    }

    public boolean isMethodEnabled(String methodCode) {
        return calculatorFactory.isMethodEnabled(methodCode);
    }

    public boolean isMethodSupported(String methodCode) {
        return calculatorFactory.isMethodSupported(methodCode);
    }

    public List<String> getAllMethodCodes() {
        return calculatorFactory.getAllMethodCodes();
    }
}
