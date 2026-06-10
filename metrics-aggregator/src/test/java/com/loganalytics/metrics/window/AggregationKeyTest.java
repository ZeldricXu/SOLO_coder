package com.loganalytics.metrics.window;

import com.loganalytics.common.model.LogLevel;
import com.loganalytics.metrics.window.WindowedAggregator.AggregationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AggregationKey - 聚合键")
class AggregationKeyTest {

    @Test
    @DisplayName("正确创建复合键")
    void shouldCreateCompositeKeyCorrectly() {
        AggregationKey key = new AggregationKey(
                "payment-service",
                LogLevel.ERROR,
                "E500",
                "pattern-123"
        );

        String composite = key.toCompositeKey();
        assertThat(composite).isEqualTo("payment-service|ERROR|E500|pattern-123");
    }

    @Test
    @DisplayName("null值使用默认占位符")
    void shouldUseDefaultPlaceholdersForNullValues() {
        AggregationKey key = new AggregationKey(
                null,
                null,
                null,
                null
        );

        String composite = key.toCompositeKey();
        assertThat(composite).isEqualTo("unknown|UNKNOWN|none|none");
    }

    @Test
    @DisplayName("从复合键正确解析")
    void shouldParseFromCompositeKeyCorrectly() {
        String composite = "payment-service|ERROR|E500|pattern-123";
        AggregationKey key = AggregationKey.fromCompositeKey(composite);

        assertThat(key.getServiceName()).isEqualTo("payment-service");
        assertThat(key.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(key.getErrorCode()).isEqualTo("E500");
        assertThat(key.getPatternId()).isEqualTo("pattern-123");
    }

    @Test
    @DisplayName("从复合键正确解析占位符值")
    void shouldParsePlaceholderValuesFromCompositeKey() {
        String composite = "unknown|UNKNOWN|none|none";
        AggregationKey key = AggregationKey.fromCompositeKey(composite);

        assertThat(key.getServiceName()).isNull();
        assertThat(key.getLevel()).isEqualTo(LogLevel.UNKNOWN);
        assertThat(key.getErrorCode()).isNull();
        assertThat(key.getPatternId()).isNull();
    }

    @Test
    @DisplayName("不同服务生成不同的键")
    void shouldGenerateDifferentKeysForDifferentServices() {
        AggregationKey key1 = new AggregationKey(
                "payment-service", LogLevel.INFO, null, null
        );
        AggregationKey key2 = new AggregationKey(
                "order-service", LogLevel.INFO, null, null
        );

        assertThat(key1.toCompositeKey()).isNotEqualTo(key2.toCompositeKey());
    }

    @Test
    @DisplayName("不同级别生成不同的键")
    void shouldGenerateDifferentKeysForDifferentLevels() {
        AggregationKey key1 = new AggregationKey(
                "payment-service", LogLevel.INFO, null, null
        );
        AggregationKey key2 = new AggregationKey(
                "payment-service", LogLevel.ERROR, null, null
        );

        assertThat(key1.toCompositeKey()).isNotEqualTo(key2.toCompositeKey());
    }

    @Test
    @DisplayName("不同错误码生成不同的键")
    void shouldGenerateDifferentKeysForDifferentErrorCodes() {
        AggregationKey key1 = new AggregationKey(
                "payment-service", LogLevel.ERROR, "E500", null
        );
        AggregationKey key2 = new AggregationKey(
                "payment-service", LogLevel.ERROR, "E404", null
        );

        assertThat(key1.toCompositeKey()).isNotEqualTo(key2.toCompositeKey());
    }

    @Test
    @DisplayName("不同模式ID生成不同的键")
    void shouldGenerateDifferentKeysForDifferentPatternIds() {
        AggregationKey key1 = new AggregationKey(
                "payment-service", LogLevel.INFO, null, "pattern-1"
        );
        AggregationKey key2 = new AggregationKey(
                "payment-service", LogLevel.INFO, null, "pattern-2"
        );

        assertThat(key1.toCompositeKey()).isNotEqualTo(key2.toCompositeKey());
    }

    @Test
    @DisplayName("相同参数生成相同的键")
    void shouldGenerateSameKeyForSameParameters() {
        AggregationKey key1 = new AggregationKey(
                "payment-service", LogLevel.ERROR, "E500", "pattern-123"
        );
        AggregationKey key2 = new AggregationKey(
                "payment-service", LogLevel.ERROR, "E500", "pattern-123"
        );

        assertThat(key1.toCompositeKey()).isEqualTo(key2.toCompositeKey());
    }

    @Test
    @DisplayName("往返转换保持一致性")
    void shouldMaintainConsistencyAfterRoundTrip() {
        AggregationKey original = new AggregationKey(
                "payment-service",
                LogLevel.WARN,
                "W100",
                "pattern-456"
        );

        String composite = original.toCompositeKey();
        AggregationKey parsed = AggregationKey.fromCompositeKey(composite);

        assertThat(parsed.getServiceName()).isEqualTo(original.getServiceName());
        assertThat(parsed.getLevel()).isEqualTo(original.getLevel());
        assertThat(parsed.getErrorCode()).isEqualTo(original.getErrorCode());
        assertThat(parsed.getPatternId()).isEqualTo(original.getPatternId());
        assertThat(parsed.toCompositeKey()).isEqualTo(composite);
    }
}
