package com.supplychain.common.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySyncStrategyConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String strategyId;

    private String strategyName;

    private String description;

    private int minFrequency;

    private int maxFrequency;

    private SyncMode syncMode;

    private int batchSize;

    private int syncIntervalSeconds;

    private boolean enabled;

    private Map<String, Object> params;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum SyncMode {
        REAL_TIME("real_time", "实时同步 - 每次变动立即同步"),
        BATCH("batch", "批量同步 - 合并多个变动后批量同步"),
        DEFERRED("deferred", "延迟同步 - 延迟后合并同步"),
        HYBRID("hybrid", "混合模式 - 根据数据量动态选择");

        private final String code;
        private final String description;

        SyncMode(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    public static Map<String, InventorySyncStrategyConfig> getDefaultStrategies() {
        Map<String, InventorySyncStrategyConfig> strategies = new HashMap<>();

        strategies.put("EXTREME_HIGH", InventorySyncStrategyConfig.builder()
                .strategyId("strategy_extreme_high")
                .strategyName("极高频策略")
                .description("变动频率极高 - 采用批量合并同步")
                .minFrequency(100)
                .maxFrequency(Integer.MAX_VALUE)
                .syncMode(SyncMode.BATCH)
                .batchSize(50)
                .syncIntervalSeconds(60)
                .enabled(true)
                .params(Map.of("mergeWindow", "60s", "maxBatchDelay", "120s", "priority", "low"))
                .build());

        strategies.put("HIGH", InventorySyncStrategyConfig.builder()
                .strategyId("strategy_high")
                .strategyName("高频策略")
                .description("变动频率较高 - 采用混合模式同步")
                .minFrequency(50)
                .maxFrequency(99)
                .syncMode(SyncMode.HYBRID)
                .batchSize(20)
                .syncIntervalSeconds(30)
                .enabled(true)
                .params(Map.of("mergeWindow", "30s", "maxBatchDelay", "60s", "priority", "medium_low"))
                .build());

        strategies.put("MEDIUM", InventorySyncStrategyConfig.builder()
                .strategyId("strategy_medium")
                .strategyName("中频率策略")
                .description("变动频率中等 - 采用延迟同步")
                .minFrequency(10)
                .maxFrequency(49)
                .syncMode(SyncMode.DEFERRED)
                .batchSize(10)
                .syncIntervalSeconds(15)
                .enabled(true)
                .params(Map.of("mergeWindow", "15s", "maxBatchDelay", "30s", "priority", "medium"))
                .build());

        strategies.put("LOW", InventorySyncStrategyConfig.builder()
                .strategyId("strategy_low")
                .strategyName("低频率策略")
                .description("变动频率较低 - 采用实时同步")
                .minFrequency(1)
                .maxFrequency(9)
                .syncMode(SyncMode.REAL_TIME)
                .batchSize(1)
                .syncIntervalSeconds(0)
                .enabled(true)
                .params(Map.of("mergeWindow", "0s", "maxBatchDelay", "0s", "priority", "high"))
                .build());

        strategies.put("IDLE", InventorySyncStrategyConfig.builder()
                .strategyId("strategy_idle")
                .strategyName("空闲策略")
                .description("无变动 - 实时同步确保即时性")
                .minFrequency(0)
                .maxFrequency(0)
                .syncMode(SyncMode.REAL_TIME)
                .batchSize(1)
                .syncIntervalSeconds(0)
                .enabled(true)
                .params(Map.of("mergeWindow", "0s", "maxBatchDelay", "0s", "priority", "critical"))
                .build());

        return strategies;
    }

    public static InventorySyncStrategyConfig getStrategyByFrequency(int frequency) {
        Map<String, InventorySyncStrategyConfig> strategies = getDefaultStrategies();

        if (frequency >= 100) {
            return strategies.get("EXTREME_HIGH");
        } else if (frequency >= 50) {
            return strategies.get("HIGH");
        } else if (frequency >= 10) {
            return strategies.get("MEDIUM");
        } else if (frequency >= 1) {
            return strategies.get("LOW");
        } else {
            return strategies.get("IDLE");
        }
    }

    public static String getFrequencyLevel(int frequency) {
        if (frequency >= 100) return "EXTREME_HIGH";
        if (frequency >= 50) return "HIGH";
        if (frequency >= 10) return "MEDIUM";
        if (frequency >= 1) return "LOW";
        return "IDLE";
    }
}
