package com.supplychain.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.config.InventorySyncStrategyConfig;
import com.supplychain.common.dto.InventorySyncRequest;
import com.supplychain.common.entity.Inventory;
import com.supplychain.common.entity.InventorySync;
import com.supplychain.common.entity.InventoryWarning;
import com.supplychain.common.enums.WarningLevel;
import com.supplychain.common.enums.WarningType;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.inventory.mapper.InventoryMapper;
import com.supplychain.inventory.mapper.InventorySyncMapper;
import com.supplychain.inventory.mapper.InventoryWarningMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryMapper inventoryMapper;
    private final InventorySyncMapper syncMapper;
    private final InventoryWarningMapper warningMapper;

    private final Map<String, LocalDateTime> lastSyncTimeMap = new ConcurrentHashMap<>();
    private final List<String> warningNotifications = new ArrayList<>();
    private final Map<String, Integer> syncFrequencyMap = new ConcurrentHashMap<>();

    private final Map<String, InventorySyncStrategyConfig> strategyConfigCache = new ConcurrentHashMap<>();

    private final Map<String, List<InventorySyncRequest>> batchPendingMap = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastBatchProcessTimeMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalChangeCountMap = new ConcurrentHashMap<>();

    @Transactional
    public InventorySync syncInventory(InventorySyncRequest request) {
        String supplierId = request.getSupplierId();
        Map<String, Map<String, Object>> syncData = request.getSyncData();

        for (Map.Entry<String, Map<String, Object>> entry : syncData.entrySet()) {
            String itemId = entry.getKey();
            Map<String, Object> itemData = entry.getValue();
            Integer quantity = ((Number) itemData.getOrDefault("quantity", 0)).intValue();
            String itemName = (String) itemData.getOrDefault("item_name", itemId);
            BigDecimal price = itemData.containsKey("price")
                ? new BigDecimal(itemData.get("price").toString())
                : BigDecimal.ZERO;
            Integer threshold = ((Number) itemData.getOrDefault("warning_threshold", 50)).intValue();

            LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Inventory::getItemId, itemId).eq(Inventory::getSupplierId, supplierId);
            Inventory existing = inventoryMapper.selectOne(wrapper);

            if (existing == null) {
                Inventory inv = Inventory.builder()
                    .inventoryId(IdGenerator.generateInventoryId())
                    .itemId(itemId)
                    .itemName(itemName)
                    .supplierId(supplierId)
                    .quantity(quantity)
                    .unitPrice(price)
                    .warningThreshold(threshold)
                    .lastSyncTime(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
                inventoryMapper.insert(inv);
            } else {
                existing.setQuantity(quantity);
                existing.setUnitPrice(price != null && price.compareTo(BigDecimal.ZERO) > 0 ? price : existing.getUnitPrice());
                existing.setWarningThreshold(threshold);
                existing.setLastSyncTime(LocalDateTime.now());
                existing.setUpdatedAt(LocalDateTime.now());
                inventoryMapper.updateById(existing);
            }
        }

        checkAndCreateWarnings(supplierId, syncData);

        InventorySync sync = InventorySync.builder()
            .syncId(IdGenerator.generateSyncId())
            .supplierId(supplierId)
            .syncType(request.getSyncType() != null ? request.getSyncType() : "inventory")
            .syncData(syncData)
            .syncTime(LocalDateTime.now())
            .build();
        syncMapper.insert(sync);

        log.info("库存同步完成: syncId={}, supplierId={}", sync.getSyncId(), supplierId);
        return sync;
    }

    private void checkAndCreateWarnings(String supplierId, Map<String, Map<String, Object>> syncData) {
        List<InventoryWarning> warnings = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : syncData.entrySet()) {
            String itemId = entry.getKey();
            Map<String, Object> itemData = entry.getValue();
            Integer quantity = ((Number) itemData.getOrDefault("quantity", 0)).intValue();
            Integer threshold = ((Number) itemData.getOrDefault("warning_threshold", 50)).intValue();

            if (quantity < threshold) {
                String level = quantity < threshold / 2 ? WarningLevel.HIGH.getCode()
                    : quantity < threshold * 0.8 ? WarningLevel.MEDIUM.getCode()
                    : WarningLevel.LOW.getCode();

                InventoryWarning warning = InventoryWarning.builder()
                    .warningId(IdGenerator.generateWarningId())
                    .itemId(itemId)
                    .warningType(WarningType.LOW_STOCK.getCode())
                    .warningLevel(level)
                    .currentQuantity(quantity)
                    .warningThreshold(threshold)
                    .triggeredAt(LocalDateTime.now())
                    .status("active")
                    .build();
                warnings.add(warning);
                warningMapper.insert(warning);
                log.warn("库存预警: itemId={}, current={}, threshold={}", itemId, quantity, threshold);
            }
        }
    }

    public List<Inventory> listInventories(String supplierId, String itemId) {
        LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
        if (supplierId != null && !supplierId.isEmpty()) {
            wrapper.eq(Inventory::getSupplierId, supplierId);
        }
        if (itemId != null && !itemId.isEmpty()) {
            wrapper.eq(Inventory::getItemId, itemId);
        }
        wrapper.orderByDesc(Inventory::getUpdatedAt);
        return inventoryMapper.selectList(wrapper);
    }

    public List<InventoryWarning> listWarnings(String status, String type) {
        LambdaQueryWrapper<InventoryWarning> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(InventoryWarning::getStatus, status);
        }
        if (type != null && !type.isEmpty()) {
            wrapper.eq(InventoryWarning::getWarningType, type);
        }
        wrapper.orderByDesc(InventoryWarning::getTriggeredAt);
        return warningMapper.selectList(wrapper);
    }

    public InventoryWarning handleWarning(String warningId, String handler) {
        InventoryWarning warning = warningMapper.selectById(warningId);
        if (warning != null) {
            warning.setStatus("handled");
            warning.setHandler(handler);
            warning.setHandledAt(LocalDateTime.now());
            warningMapper.updateById(warning);
        }
        return warning;
    }

    public List<InventorySync> listSyncRecords(String supplierId) {
        LambdaQueryWrapper<InventorySync> wrapper = new LambdaQueryWrapper<>();
        if (supplierId != null && !supplierId.isEmpty()) {
            wrapper.eq(InventorySync::getSupplierId, supplierId);
        }
        wrapper.orderByDesc(InventorySync::getSyncTime);
        return syncMapper.selectList(wrapper);
    }

    @Transactional
    public InventorySync syncInventoryRealTime(InventorySyncRequest request) {
        String supplierId = request.getSupplierId();
        Map<String, Map<String, Object>> syncData = request.getSyncData();

        LocalDateTime now = LocalDateTime.now();
        String syncKey = supplierId + ":" + now.getMinute();

        Integer frequency = syncFrequencyMap.getOrDefault(syncKey, 0);
        int newFrequency = frequency + syncData.size();
        syncFrequencyMap.put(syncKey, newFrequency);

        totalChangeCountMap.merge(supplierId, syncData.size(), Integer::sum);

        lastSyncTimeMap.put(supplierId, now);

        InventorySyncStrategyConfig strategy = determineSyncStrategyBySupplier(supplierId);
        log.info("库存变动频率: supplierId={}, 当前频率={}, 策略={}",
                supplierId, newFrequency, strategy.getStrategyName());

        switch (strategy.getSyncMode()) {
            case REAL_TIME:
                return executeRealTimeSync(supplierId, syncData, now);
            case BATCH:
                return queueForBatchSync(supplierId, request, strategy);
            case DEFERRED:
                return executeDeferredSync(supplierId, request, strategy);
            case HYBRID:
                return executeHybridSync(supplierId, request, strategy);
            default:
                return executeRealTimeSync(supplierId, syncData, now);
        }
    }

    private InventorySync executeRealTimeSync(String supplierId,
            Map<String, Map<String, Object>> syncData, LocalDateTime now) {
        log.info("执行实时同步: supplierId={}, itemCount={}", supplierId, syncData.size());

        processInventoryChanges(supplierId, syncData, now);
        checkAndCreateWarningsWithNotification(supplierId, syncData);

        InventorySync sync = InventorySync.builder()
            .syncId(IdGenerator.generateSyncId())
            .supplierId(supplierId)
            .syncType("real_time_inventory")
            .syncData(syncData)
            .syncTime(now)
            .build();
        syncMapper.insert(sync);

        log.info("库存实时同步完成: syncId={}, supplierId={}", sync.getSyncId(), supplierId);
        return sync;
    }

    private InventorySync queueForBatchSync(String supplierId,
            InventorySyncRequest request, InventorySyncStrategyConfig strategy) {
        List<InventorySyncRequest> pendingRequests = batchPendingMap
                .computeIfAbsent(supplierId, k -> new ArrayList<>());
        pendingRequests.add(request);

        log.info("加入批量同步队列: supplierId={}, pendingCount={}, batchSize={}",
                supplierId, pendingRequests.size(), strategy.getBatchSize());

        LocalDateTime lastProcessTime = lastBatchProcessTimeMap.get(supplierId);
        boolean shouldProcessNow = pendingRequests.size() >= strategy.getBatchSize() ||
                (lastProcessTime != null &&
                        Duration.between(lastProcessTime, LocalDateTime.now()).getSeconds() >= strategy.getSyncIntervalSeconds());

        if (shouldProcessNow) {
            return processBatchSync(supplierId);
        }

        return InventorySync.builder()
            .syncId(IdGenerator.generateSyncId())
            .supplierId(supplierId)
            .syncType("batch_queued")
            .syncData(request.getSyncData())
            .syncTime(LocalDateTime.now())
            .build();
    }

    private InventorySync processBatchSync(String supplierId) {
        List<InventorySyncRequest> pendingRequests = batchPendingMap.remove(supplierId);
        if (pendingRequests == null || pendingRequests.isEmpty()) {
            log.info("批量同步队列为空: supplierId={}", supplierId);
            return null;
        }

        log.info("执行批量同步: supplierId={}, batchCount={}", supplierId, pendingRequests.size());

        Map<String, Map<String, Object>> mergedData = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (InventorySyncRequest request : pendingRequests) {
            mergedData.putAll(request.getSyncData());
        }

        processInventoryChanges(supplierId, mergedData, now);
        checkAndCreateWarningsWithNotification(supplierId, mergedData);

        lastBatchProcessTimeMap.put(supplierId, now);

        InventorySync sync = InventorySync.builder()
            .syncId(IdGenerator.generateSyncId())
            .supplierId(supplierId)
            .syncType("batch_inventory")
            .syncData(mergedData)
            .syncTime(now)
            .build();
        syncMapper.insert(sync);

        log.info("库存批量同步完成: syncId={}, supplierId={}, itemCount={}",
                sync.getSyncId(), supplierId, mergedData.size());
        return sync;
    }

    private InventorySync executeDeferredSync(String supplierId,
            InventorySyncRequest request, InventorySyncStrategyConfig strategy) {
        log.info("执行延迟同步: supplierId={}, interval={}s",
                supplierId, strategy.getSyncIntervalSeconds());

        LocalDateTime lastProcessTime = lastBatchProcessTimeMap.get(supplierId);
        if (lastProcessTime == null ||
                Duration.between(lastProcessTime, LocalDateTime.now()).getSeconds() >= strategy.getSyncIntervalSeconds()) {
            return queueForBatchSync(supplierId, request, strategy);
        }

        return executeRealTimeSync(supplierId, request.getSyncData(), LocalDateTime.now());
    }

    private InventorySync executeHybridSync(String supplierId,
            InventorySyncRequest request, InventorySyncStrategyConfig strategy) {
        int itemCount = request.getSyncData().size();

        if (itemCount >= strategy.getBatchSize()) {
            log.info("混合模式 - 数据量较大，使用批量同步: supplierId={}, itemCount={}",
                    supplierId, itemCount);
            return queueForBatchSync(supplierId, request, strategy);
        } else {
            log.info("混合模式 - 数据量较小，使用实时同步: supplierId={}, itemCount={}",
                    supplierId, itemCount);
            return executeRealTimeSync(supplierId, request.getSyncData(), LocalDateTime.now());
        }
    }

    private void processInventoryChanges(String supplierId,
            Map<String, Map<String, Object>> syncData, LocalDateTime now) {
        for (Map.Entry<String, Map<String, Object>> entry : syncData.entrySet()) {
            String itemId = entry.getKey();
            Map<String, Object> itemData = entry.getValue();
            Integer quantity = ((Number) itemData.getOrDefault("quantity", 0)).intValue();
            String itemName = (String) itemData.getOrDefault("item_name", itemId);
            BigDecimal price = itemData.containsKey("price")
                ? new BigDecimal(itemData.get("price").toString())
                : BigDecimal.ZERO;
            Integer threshold = ((Number) itemData.getOrDefault("warning_threshold", 50)).intValue();

            LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Inventory::getItemId, itemId).eq(Inventory::getSupplierId, supplierId);
            Inventory existing = inventoryMapper.selectOne(wrapper);

            boolean isNew = existing == null;
            if (isNew) {
                Inventory inv = Inventory.builder()
                    .inventoryId(IdGenerator.generateInventoryId())
                    .itemId(itemId)
                    .itemName(itemName)
                    .supplierId(supplierId)
                    .quantity(quantity)
                    .unitPrice(price)
                    .warningThreshold(threshold)
                    .lastSyncTime(now)
                    .updatedAt(now)
                    .build();
                inventoryMapper.insert(inv);
                log.info("动态同步 - 新增库存: itemId={}, quantity={}", itemId, quantity);
            } else {
                int oldQuantity = existing.getQuantity();
                existing.setQuantity(quantity);
                existing.setUnitPrice(price != null && price.compareTo(BigDecimal.ZERO) > 0 ? price : existing.getUnitPrice());
                existing.setWarningThreshold(threshold);
                existing.setLastSyncTime(now);
                existing.setUpdatedAt(now);
                inventoryMapper.updateById(existing);
                log.info("动态同步 - 更新库存: itemId={}, {} -> {}", itemId, oldQuantity, quantity);
            }
        }
    }

    private void checkAndCreateWarningsWithNotification(String supplierId, Map<String, Map<String, Object>> syncData) {
        for (Map.Entry<String, Map<String, Object>> entry : syncData.entrySet()) {
            String itemId = entry.getKey();
            Map<String, Object> itemData = entry.getValue();
            Integer quantity = ((Number) itemData.getOrDefault("quantity", 0)).intValue();
            Integer threshold = ((Number) itemData.getOrDefault("warning_threshold", 50)).intValue();

            if (quantity < threshold) {
                String level = determineWarningLevel(quantity, threshold);

                InventoryWarning warning = InventoryWarning.builder()
                    .warningId(IdGenerator.generateWarningId())
                    .itemId(itemId)
                    .warningType(WarningType.LOW_STOCK.getCode())
                    .warningLevel(level)
                    .currentQuantity(quantity)
                    .warningThreshold(threshold)
                    .triggeredAt(LocalDateTime.now())
                    .status("active")
                    .build();
                warningMapper.insert(warning);

                sendWarningNotification(warning, supplierId);
                log.warn("库存预警触发: itemId={}, current={}, threshold={}, level={}",
                    itemId, quantity, threshold, level);
            }
        }
    }

    private String determineWarningLevel(int quantity, int threshold) {
        double ratio = (double) quantity / threshold;
        if (ratio <= 0.25) return WarningLevel.CRITICAL.getCode();
        if (ratio <= 0.5) return WarningLevel.HIGH.getCode();
        if (ratio <= 0.8) return WarningLevel.MEDIUM.getCode();
        return WarningLevel.LOW.getCode();
    }

    private void sendWarningNotification(InventoryWarning warning, String supplierId) {
        String notification = String.format(
            "[库存预警] 供应商=%s, 商品=%s, 类型=%s, 级别=%s, 当前库存=%d, 阈值=%d",
            supplierId, warning.getItemId(), warning.getWarningType(),
            warning.getWarningLevel(), warning.getCurrentQuantity(), warning.getWarningThreshold()
        );
        warningNotifications.add(notification);
        log.warn("发送预警通知: {}", notification);
    }

    public List<String> getWarningNotifications() {
        return new ArrayList<>(warningNotifications);
    }

    public void clearWarningNotifications() {
        warningNotifications.clear();
    }

    public boolean isRealTimeSync(String supplierId) {
        LocalDateTime lastSync = lastSyncTimeMap.get(supplierId);
        if (lastSync == null) {
            return false;
        }
        Duration duration = Duration.between(lastSync, LocalDateTime.now());
        return duration.toSeconds() < 60;
    }

    public Map<String, Object> getSyncMetrics(String supplierId) {
        Map<String, Object> metrics = new HashMap<>();
        LocalDateTime lastSync = lastSyncTimeMap.get(supplierId);

        metrics.put("supplierId", supplierId);
        metrics.put("lastSyncTime", lastSync);
        metrics.put("isRealTime", isRealTimeSync(supplierId));

        int totalFrequency = 0;
        for (Map.Entry<String, Integer> entry : syncFrequencyMap.entrySet()) {
            if (entry.getKey().startsWith(supplierId + ":")) {
                totalFrequency += entry.getValue();
            }
        }

        Integer totalChanges = totalChangeCountMap.get(supplierId);
        metrics.put("syncFrequency", totalFrequency);
        metrics.put("totalChangeCount", totalChanges != null ? totalChanges : 0);

        InventorySyncStrategyConfig strategy = determineSyncStrategyBySupplier(supplierId);
        metrics.put("syncStrategy", strategy.getStrategyName());
        metrics.put("syncMode", strategy.getSyncMode().getCode());
        metrics.put("syncModeDescription", strategy.getSyncMode().getDescription());
        metrics.put("strategyDescription", strategy.getDescription());
        metrics.put("batchSize", strategy.getBatchSize());
        metrics.put("syncIntervalSeconds", strategy.getSyncIntervalSeconds());

        Integer pendingBatchCount = batchPendingMap.containsKey(supplierId)
                ? batchPendingMap.get(supplierId).size() : 0;
        metrics.put("pendingBatchCount", pendingBatchCount);

        return metrics;
    }

    public InventorySyncStrategyConfig determineSyncStrategyBySupplier(String supplierId) {
        int frequency = calculateSupplierFrequency(supplierId);
        return InventorySyncStrategyConfig.getStrategyByFrequency(frequency);
    }

    private int calculateSupplierFrequency(String supplierId) {
        Integer totalChanges = totalChangeCountMap.get(supplierId);
        if (totalChanges == null) {
            return 0;
        }
        return totalChanges;
    }

    public String determineSyncStrategy(int frequency) {
        InventorySyncStrategyConfig strategy = InventorySyncStrategyConfig.getStrategyByFrequency(frequency);
        return strategy.getStrategyId();
    }

    public InventorySyncStrategyConfig getSyncStrategyConfig(String frequencyLevel) {
        return strategyConfigCache.computeIfAbsent(frequencyLevel,
                key -> loadStrategyConfigFromSource(key));
    }

    private InventorySyncStrategyConfig loadStrategyConfigFromSource(String frequencyLevel) {
        Map<String, InventorySyncStrategyConfig> strategies = InventorySyncStrategyConfig.getDefaultStrategies();
        InventorySyncStrategyConfig config = strategies.get(frequencyLevel);
        if (config == null) {
            config = strategies.get("MEDIUM");
        }
        log.debug("加载库存同步策略: level={}, strategy={}",
                frequencyLevel, config.getStrategyName());
        return config;
    }

    public void updateSyncStrategyConfig(InventorySyncStrategyConfig config) {
        String level = InventorySyncStrategyConfig.getFrequencyLevel(config.getMinFrequency());
        strategyConfigCache.put(level, config);
        log.info("库存同步策略已更新: level={}, strategy={}", level, config.getStrategyName());
    }

    public Map<String, InventorySyncStrategyConfig> getAllSyncStrategyConfigs() {
        return new HashMap<>(strategyConfigCache);
    }

    public void refreshAllSyncStrategyConfigs() {
        strategyConfigCache.clear();
        log.info("库存同步策略缓存已刷新");
    }

    public Map<String, Integer> getSyncFrequencyMap() {
        return new HashMap<>(syncFrequencyMap);
    }

    public Map<String, List<InventorySyncRequest>> getBatchPendingMap() {
        return new HashMap<>(batchPendingMap);
    }

    public void clearSyncMetrics() {
        lastSyncTimeMap.clear();
        syncFrequencyMap.clear();
        totalChangeCountMap.clear();
        batchPendingMap.clear();
        lastBatchProcessTimeMap.clear();
    }

    public boolean checkWarningThreshold(int quantity, int threshold) {
        return quantity < threshold;
    }

    public String getWarningLevel(int quantity, int threshold) {
        return determineWarningLevel(quantity, threshold);
    }

    public Inventory getInventory(String supplierId, String itemId) {
        LambdaQueryWrapper<Inventory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Inventory::getItemId, itemId).eq(Inventory::getSupplierId, supplierId);
        return inventoryMapper.selectOne(wrapper);
    }

    public Map<String, Object> processPendingBatchSyncs() {
        Map<String, Object> result = new HashMap<>();
        int processedCount = 0;
        int totalItems = 0;

        Set<String> suppliersToProcess = new HashSet<>(batchPendingMap.keySet());

        for (String supplierId : suppliersToProcess) {
            InventorySyncStrategyConfig strategy = determineSyncStrategyBySupplier(supplierId);
            LocalDateTime lastProcessTime = lastBatchProcessTimeMap.get(supplierId);

            if (lastProcessTime == null ||
                    Duration.between(lastProcessTime, LocalDateTime.now()).getSeconds() >= strategy.getSyncIntervalSeconds()) {
                InventorySync sync = processBatchSync(supplierId);
                if (sync != null) {
                    processedCount++;
                    totalItems += sync.getSyncData() != null ? sync.getSyncData().size() : 0;
                }
            }
        }

        result.put("processedBatchCount", processedCount);
        result.put("totalItemsProcessed", totalItems);
        result.put("remainingPendingCount", batchPendingMap.size());

        log.info("批量同步处理完成: processed={}, items={}", processedCount, totalItems);
        return result;
    }

    public Map<String, Object> getSyncStrategyDashboard() {
        Map<String, Object> dashboard = new HashMap<>();

        Map<String, Object> supplierMetrics = new HashMap<>();
        for (String supplierId : totalChangeCountMap.keySet()) {
            supplierMetrics.put(supplierId, getSyncMetrics(supplierId));
        }
        dashboard.put("supplierMetrics", supplierMetrics);

        Map<String, Map<String, Object>> strategySummary = new HashMap<>();
        for (Map.Entry<String, InventorySyncStrategyConfig> entry :
                InventorySyncStrategyConfig.getDefaultStrategies().entrySet()) {
            InventorySyncStrategyConfig config = entry.getValue();
            strategySummary.put(entry.getKey(), Map.of(
                    "strategyName", config.getStrategyName(),
                    "description", config.getDescription(),
                    "frequencyRange", config.getMinFrequency() + "-" +
                            (config.getMaxFrequency() == Integer.MAX_VALUE ? "∞" : config.getMaxFrequency()),
                    "syncMode", config.getSyncMode().getCode(),
                    "batchSize", config.getBatchSize(),
                    "syncIntervalSeconds", config.getSyncIntervalSeconds(),
                    "enabled", config.isEnabled()
            ));
        }
        dashboard.put("strategies", strategySummary);

        return dashboard;
    }
}
