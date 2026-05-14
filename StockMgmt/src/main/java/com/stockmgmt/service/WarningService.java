package com.stockmgmt.service;

import com.stockmgmt.dto.WarningHandleRequest;
import com.stockmgmt.entity.WarningAggregationConfig;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockWarning;
import com.stockmgmt.enums.WarningLevel;
import com.stockmgmt.enums.WarningStatus;
import com.stockmgmt.enums.WarningType;
import com.stockmgmt.repository.StockWarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WarningService {

    private static final Logger logger = LoggerFactory.getLogger(WarningService.class);

    @Autowired
    private StockWarningRepository warningRepository;

    @Autowired
    private WarningThresholdConfigService thresholdConfigService;

    @Autowired
    private WarningAggregationConfigService aggregationConfigService;

    private final Map<String, AggregationState> aggregationStates = new ConcurrentHashMap<>();
    private final Map<String, NotificationState> notificationStates = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public void checkAndTriggerWarning(Stock stock) {
        logger.info("检查库存预警，stockId: {}", stock.getStockId());

        thresholdConfigService.applyThresholds(stock);

        if (thresholdConfigService.shouldTriggerLowStockWarning(stock)) {
            triggerLowStockWarning(stock);
        } else {
            resolveLowStockWarning(stock);
        }

        if (thresholdConfigService.shouldTriggerOverstockWarning(stock)) {
            triggerOverstockWarning(stock);
        } else {
            resolveOverstockWarning(stock);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void triggerLowStockWarning(Stock stock) {
        logger.info("检查库存不足预警，stockId: {}", stock.getStockId());

        StockWarning existing = warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                stock.getStockId(), WarningType.LOW_STOCK, WarningStatus.ACTIVE).orElse(null);

        if (existing != null) {
            logger.debug("库存不足预警已存在，检查是否需要聚合通知: {}", stock.getStockId());
            handleAggregatedNotification(existing, stock, WarningType.LOW_STOCK);
            return;
        }

        WarningLevel level = determineWarningLevel(
                stock.getCurrentQuantity(), stock.getWarningThreshold());

        StockWarning warning = new StockWarning();
        warning.setStockId(stock.getStockId());
        warning.setProductId(stock.getProductId());
        warning.setProductName(stock.getProductName());
        warning.setWarningType(WarningType.LOW_STOCK);
        warning.setWarningLevel(level);
        warning.setCurrentQuantity(stock.getCurrentQuantity());
        warning.setThreshold(stock.getWarningThreshold());
        warning.setStatus(WarningStatus.ACTIVE);

        warningRepository.save(warning);

        sendAggregatedNotification(warning, stock, WarningType.LOW_STOCK);

        logger.info("库存不足预警触发成功，warningId: {}", warning.getWarningId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void triggerOverstockWarning(Stock stock) {
        logger.info("检查库存积压预警，stockId: {}", stock.getStockId());

        StockWarning existing = warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                stock.getStockId(), WarningType.OVERSTOCK, WarningStatus.ACTIVE).orElse(null);

        if (existing != null) {
            logger.debug("库存积压预警已存在，检查是否需要聚合通知: {}", stock.getStockId());
            handleAggregatedNotification(existing, stock, WarningType.OVERSTOCK);
            return;
        }

        WarningLevel level = determineOverstockWarningLevel(
                stock.getCurrentQuantity(), stock.getOverstockThreshold());

        StockWarning warning = new StockWarning();
        warning.setStockId(stock.getStockId());
        warning.setProductId(stock.getProductId());
        warning.setProductName(stock.getProductName());
        warning.setWarningType(WarningType.OVERSTOCK);
        warning.setWarningLevel(level);
        warning.setCurrentQuantity(stock.getCurrentQuantity());
        warning.setThreshold(stock.getOverstockThreshold());
        warning.setStatus(WarningStatus.ACTIVE);

        warningRepository.save(warning);

        sendAggregatedNotification(warning, stock, WarningType.OVERSTOCK);

        logger.info("库存积压预警触发成功，warningId: {}", warning.getWarningId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void resolveLowStockWarning(Stock stock) {
        List<StockWarning> activeWarnings = warningRepository.findByStockIdAndStatus(
                stock.getStockId(), WarningStatus.ACTIVE);

        for (StockWarning warning : activeWarnings) {
            if (warning.getWarningType() == WarningType.LOW_STOCK) {
                warning.setStatus(WarningStatus.HANDLED);
                warning.setHandledAt(LocalDateTime.now());
                warning.setHandledBy("system");
                warning.setRemark("自动解除-库存恢复正常");
                warningRepository.save(warning);
                clearAggregationState(warning);
                logger.info("库存不足预警自动解除，warningId: {}", warning.getWarningId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void resolveOverstockWarning(Stock stock) {
        List<StockWarning> activeWarnings = warningRepository.findByStockIdAndStatus(
                stock.getStockId(), WarningStatus.ACTIVE);

        for (StockWarning warning : activeWarnings) {
            if (warning.getWarningType() == WarningType.OVERSTOCK) {
                warning.setStatus(WarningStatus.HANDLED);
                warning.setHandledAt(LocalDateTime.now());
                warning.setHandledBy("system");
                warning.setRemark("自动解除-库存恢复正常");
                warningRepository.save(warning);
                clearAggregationState(warning);
                logger.info("库存积压预警自动解除，warningId: {}", warning.getWarningId());
            }
        }
    }

    private void handleAggregatedNotification(StockWarning existing, Stock stock, WarningType type) {
        String aggregationKey = buildAggregationKey(stock.getStockId(), type);
        AggregationState state = aggregationStates.get(aggregationKey);
        LocalDateTime now = LocalDateTime.now();

        WarningAggregationConfig config = aggregationConfigService.getConfig(
                existing.getWarningLevel(), type, stock.getProductId(), stock.getWarehouseId());

        int windowSeconds = config.getAggregationWindowSeconds();
        int maxNotifications = config.getMaxNotificationsPerWindow();

        if (state == null) {
            state = new AggregationState();
            state.setWindowStart(now);
            state.setNotificationCount(1);
            state.setLastNotificationAt(now);
            state.setAggregatedWarnings(1);
            state.setMinQuantity(existing.getCurrentQuantity());
            state.setMaxQuantity(existing.getCurrentQuantity());
            aggregationStates.put(aggregationKey, state);
            return;
        }

        long windowElapsedSeconds = java.time.Duration.between(state.getWindowStart(), now).getSeconds();

        if (windowElapsedSeconds >= windowSeconds) {
            logger.info("聚合窗口已过期，重置聚合状态: {}", aggregationKey);
            state.setWindowStart(now);
            state.setNotificationCount(0);
            state.setAggregatedWarnings(0);
            state.setMinQuantity(Integer.MAX_VALUE);
            state.setMaxQuantity(Integer.MIN_VALUE);
        }

        state.setAggregatedWarnings(state.getAggregatedWarnings() + 1);
        state.setMinQuantity(Math.min(state.getMinQuantity(), stock.getCurrentQuantity()));
        state.setMaxQuantity(Math.max(state.getMaxQuantity(), stock.getCurrentQuantity()));

        if (state.getNotificationCount() < maxNotifications) {
            long cooldownElapsedSeconds = java.time.Duration.between(state.getLastNotificationAt(), now).getSeconds();
            if (cooldownElapsedSeconds >= config.getNotificationCooldownSeconds()) {
                logger.info("聚合窗口内达到发送条件，发送聚合通知: {}", aggregationKey);
                sendAggregatedNotification(existing, stock, type);
                state.setNotificationCount(state.getNotificationCount() + 1);
                state.setLastNotificationAt(now);
            }
        }
    }

    private void sendAggregatedNotification(StockWarning warning, Stock stock, WarningType type) {
        String aggregationKey = buildAggregationKey(stock.getStockId(), type);
        AggregationState state = aggregationStates.get(aggregationKey);

        WarningAggregationConfig config = aggregationConfigService.getConfig(
                warning.getWarningLevel(), type, stock.getProductId(), stock.getWarehouseId());

        int aggregatedCount = state != null ? state.getAggregatedWarnings() : 1;

        StringBuilder notificationContent = new StringBuilder();
        notificationContent.append("【库存预警通知】\n");
        notificationContent.append("预警类型: ").append(type.getDesc()).append("\n");
        notificationContent.append("预警级别: ").append(warning.getWarningLevel().getDesc()).append("\n");
        notificationContent.append("商品名称: ").append(stock.getProductName()).append("\n");
        notificationContent.append("商品ID: ").append(stock.getProductId()).append("\n");
        notificationContent.append("当前库存: ").append(warning.getCurrentQuantity()).append("\n");
        notificationContent.append("预警阈值: ").append(warning.getThreshold()).append("\n");

        if (aggregatedCount > 1) {
            notificationContent.append("聚合次数: ").append(aggregatedCount).append("\n");
            if (state != null) {
                notificationContent.append("库存范围: ").append(state.getMinQuantity())
                        .append(" - ").append(state.getMaxQuantity()).append("\n");
            }
        }

        notificationContent.append("聚合窗口: ").append(config.getAggregationWindowSeconds()).append("秒\n");
        notificationContent.append("触发时间: ").append(LocalDateTime.now());

        logger.warn("发送预警通知: \n{}", notificationContent.toString());

        sendWarningNotification(warning);
    }

    private void clearAggregationState(StockWarning warning) {
        String aggregationKey = buildAggregationKey(warning.getStockId(), warning.getWarningType());
        aggregationStates.remove(aggregationKey);
        notificationStates.remove(aggregationKey);
        logger.debug("清除聚合状态: {}", aggregationKey);
    }

    private String buildAggregationKey(String stockId, WarningType type) {
        return stockId + "|" + type.getCode();
    }

    private WarningLevel determineWarningLevel(int currentQuantity, int threshold) {
        double ratio = (double) currentQuantity / threshold;
        if (ratio <= 0.3) {
            return WarningLevel.HIGH;
        } else if (ratio <= 0.6) {
            return WarningLevel.MEDIUM;
        }
        return WarningLevel.LOW;
    }

    private WarningLevel determineOverstockWarningLevel(int currentQuantity, int threshold) {
        double ratio = (double) currentQuantity / threshold;
        if (ratio >= 2.0) {
            return WarningLevel.HIGH;
        } else if (ratio >= 1.5) {
            return WarningLevel.MEDIUM;
        }
        return WarningLevel.LOW;
    }

    private void sendWarningNotification(StockWarning warning) {
        logger.warn("发送预警通知: type={}, level={}, stockId={}, current={}, threshold={}",
                warning.getWarningType().getCode(),
                warning.getWarningLevel().getCode(),
                warning.getStockId(),
                warning.getCurrentQuantity(),
                warning.getThreshold());
    }

    @Scheduled(fixedRateString = "${stock.warning.scan-interval:60000}")
    @Transactional(rollbackFor = Exception.class)
    public void scheduledWarningScan() {
        logger.info("执行定时预警扫描任务");
        try {
            List<StockWarning> warnings = warningRepository.findByStatus(WarningStatus.ACTIVE);
            for (StockWarning warning : warnings) {
                sendWarningNotification(warning);
            }
            logger.info("定时预警扫描任务完成，扫描到{}个活动预警", warnings.size());
        } catch (Exception e) {
            logger.error("定时预警扫描任务失败", e);
        }
    }

    public StockWarning getWarningById(String warningId) {
        return warningRepository.findById(warningId)
                .orElseThrow(() -> new RuntimeException("预警记录不存在: " + warningId));
    }

    public List<StockWarning> getWarningsByStockId(String stockId) {
        return warningRepository.findByStockId(stockId);
    }

    public List<StockWarning> getWarningsByType(WarningType type) {
        return warningRepository.findByWarningType(type);
    }

    public List<StockWarning> getActiveWarnings() {
        return warningRepository.findByStatus(WarningStatus.ACTIVE);
    }

    public List<StockWarning> getActiveWarningsByType(WarningType type) {
        return warningRepository.findByWarningTypeAndStatus(type, WarningStatus.ACTIVE);
    }

    public Page<StockWarning> getWarningPage(String warningType, String status, int pageNum, int pageSize) {
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "triggeredAt"));
        org.springframework.data.jpa.domain.Specification<StockWarning> spec = null;

        if (warningType != null && !warningType.isEmpty()) {
            WarningType type = WarningType.fromCode(warningType);
            if (type != null) {
                spec = (root, query, cb) -> cb.equal(root.get("warningType"), type);
            }
        }
        if (status != null && !status.isEmpty()) {
            WarningStatus ws = WarningStatus.fromCode(status);
            if (ws != null) {
                org.springframework.data.jpa.domain.Specification<StockWarning> statusSpec =
                        (root, query, cb) -> cb.equal(root.get("status"), ws);
                spec = spec != null ? spec.and(statusSpec) : statusSpec;
            }
        }

        if (spec != null) {
            return warningRepository.findAll(spec, pageable);
        }
        return warningRepository.findAll(pageable);
    }

    @Transactional(rollbackFor = Exception.class)
    public StockWarning handleWarning(String warningId, WarningHandleRequest request) {
        StockWarning warning = warningRepository.findById(warningId)
                .orElseThrow(() -> new RuntimeException("预警记录不存在: " + warningId));

        warning.setStatus(WarningStatus.HANDLED);
        warning.setHandledAt(LocalDateTime.now());
        warning.setHandledBy(request.getHandledBy());
        warning.setRemark(request.getRemark());

        clearAggregationState(warning);

        return warningRepository.save(warning);
    }

    public long getActiveWarningCount() {
        return warningRepository.countByStatus(WarningStatus.ACTIVE);
    }

    public int getAggregationWindowSeconds(WarningLevel level, WarningType type,
                                           String productId, String warehouseId) {
        return aggregationConfigService.getAggregationWindowSeconds(level, type, productId, warehouseId);
    }

    public void refreshThresholdConfigs() {
        thresholdConfigService.refreshCache();
        logger.info("预警阈值配置已刷新");
    }

    public void refreshAggregationConfigs() {
        aggregationConfigService.refreshCache();
        logger.info("预警聚合配置已刷新");
    }

    public static class AggregationState {
        private LocalDateTime windowStart;
        private int notificationCount;
        private int aggregatedWarnings;
        private LocalDateTime lastNotificationAt;
        private int minQuantity;
        private int maxQuantity;

        public LocalDateTime getWindowStart() {
            return windowStart;
        }

        public void setWindowStart(LocalDateTime windowStart) {
            this.windowStart = windowStart;
        }

        public int getNotificationCount() {
            return notificationCount;
        }

        public void setNotificationCount(int notificationCount) {
            this.notificationCount = notificationCount;
        }

        public int getAggregatedWarnings() {
            return aggregatedWarnings;
        }

        public void setAggregatedWarnings(int aggregatedWarnings) {
            this.aggregatedWarnings = aggregatedWarnings;
        }

        public LocalDateTime getLastNotificationAt() {
            return lastNotificationAt;
        }

        public void setLastNotificationAt(LocalDateTime lastNotificationAt) {
            this.lastNotificationAt = lastNotificationAt;
        }

        public int getMinQuantity() {
            return minQuantity;
        }

        public void setMinQuantity(int minQuantity) {
            this.minQuantity = minQuantity;
        }

        public int getMaxQuantity() {
            return maxQuantity;
        }

        public void setMaxQuantity(int maxQuantity) {
            this.maxQuantity = maxQuantity;
        }
    }

    public static class NotificationState {
        private LocalDateTime lastSentAt;
        private int countInWindow;

        public LocalDateTime getLastSentAt() {
            return lastSentAt;
        }

        public void setLastSentAt(LocalDateTime lastSentAt) {
            this.lastSentAt = lastSentAt;
        }

        public int getCountInWindow() {
            return countInWindow;
        }

        public void setCountInWindow(int countInWindow) {
            this.countInWindow = countInWindow;
        }
    }
}
