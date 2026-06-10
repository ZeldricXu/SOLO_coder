package com.loganalytics.detector.drain;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DrainTree - 日志模式聚类")
class DrainTreeTest {

    private DrainTree drainTree;
    private DetectorConfig config;

    @BeforeEach
    void setUp() {
        config = new DetectorConfig();
        config.setSimilarityThreshold(0.7);
        config.setMaxTreeDepth(4);
        config.setMaxChildren(100);
        config.setSigmaThreshold(3.0);
        config.setFrequencyWindowMinutes(5);
        config.setBaselineHistoryDays(14);
        config.setMinBaselinePoints(10);
        config.setAnomalyCooldownMinutes(5);

        drainTree = new DrainTree(config);
    }

    @Test
    @DisplayName("相同模式的日志被聚类到同一个模式模板")
    void shouldClusterSamePatternLogsIntoSameTemplate() {
        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_123", "192.168.1.100")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_456", "10.0.0.50")
                .build();

        LogEvent event3 = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_789", "172.16.0.1")
                .build();

        LogPattern pattern1 = drainTree.process(event1);
        LogPattern pattern2 = drainTree.process(event2);
        LogPattern pattern3 = drainTree.process(event3);

        assertThat(pattern1).isNotNull();
        assertThat(pattern2).isNotNull();
        assertThat(pattern3).isNotNull();

        assertThat(pattern1.getId()).isEqualTo(pattern2.getId())
                .isEqualTo(pattern3.getId());

        assertThat(pattern1.getTemplate()).contains("User")
                .contains("login")
                .contains("from")
                .contains("<*>");

        assertThat(pattern1.getTotalCount()).isEqualTo(3);
        assertThat(event1.getPatternId()).isEqualTo(pattern1.getId());
        assertThat(event2.getPatternId()).isEqualTo(pattern1.getId());
        assertThat(event3.getPatternId()).isEqualTo(pattern1.getId());
    }

    @Test
    @DisplayName("新模式首次出现被正确标记为novel pattern并记录")
    void shouldMarkNewPatternAsNovelAndRecord() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withConnectionTimeoutMessage("db-server-01", 5432, 30)
                .build();

        int initialPatternCount = drainTree.getPatternCount();
        LogPattern pattern = drainTree.process(event);

        assertThat(pattern).isNotNull();
        assertThat(drainTree.getPatternCount()).isEqualTo(initialPatternCount + 1);

        List<LogPattern> newPatterns = drainTree.getNewPatterns();
        assertThat(newPatterns).hasSize(1);
        assertThat(newPatterns.get(0).getId()).isEqualTo(pattern.getId());

        LogEvent samePatternEvent = LogEventBuilder.aLogEvent()
                .withConnectionTimeoutMessage("db-server-02", 3306, 60)
                .build();

        LogPattern existingPattern = drainTree.process(samePatternEvent);
        assertThat(existingPattern.getId()).isEqualTo(pattern.getId());
        assertThat(drainTree.getNewPatterns()).isEmpty();
    }

    @Test
    @DisplayName("不同模式的日志创建不同的模式模板")
    void shouldCreateDifferentTemplatesForDifferentPatterns() {
        LogEvent loginEvent = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .build();

        LogEvent timeoutEvent = LogEventBuilder.aLogEvent()
                .withConnectionTimeoutMessage("server-1", 8080, 10)
                .build();

        LogEvent paymentEvent = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withMessage("Payment processed successfully for order 12345")
                .build();

        LogPattern loginPattern = drainTree.process(loginEvent);
        LogPattern timeoutPattern = drainTree.process(timeoutEvent);
        LogPattern paymentPattern = drainTree.process(paymentEvent);

        assertThat(drainTree.getPatternCount()).isEqualTo(3);
        assertThat(loginPattern.getId()).isNotEqualTo(timeoutPattern.getId());
        assertThat(timeoutPattern.getId()).isNotEqualTo(paymentPattern.getId());
        assertThat(loginPattern.getId()).isNotEqualTo(paymentPattern.getId());
    }

    @Test
    @DisplayName("IP地址被正确识别为变量")
    void shouldTreatIpAddressesAsVariables() {
        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withMessage("Request from 192.168.1.100 processed in 50ms")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withMessage("Request from 10.0.0.1 processed in 100ms")
                .build();

        LogPattern pattern1 = drainTree.process(event1);
        LogPattern pattern2 = drainTree.process(event2);

        assertThat(pattern1.getId()).isEqualTo(pattern2.getId());
        assertThat(pattern1.getTemplate()).contains("<*>");
    }

    @Test
    @DisplayName("UUID被正确识别为变量")
    void shouldTreatUuidsAsVariables() {
        String uuid1 = "550e8400-e29b-41d4-a716-446655440000";
        String uuid2 = "660e8400-e29b-41d4-a716-446655440001";

        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withMessage("Transaction " + uuid1 + " completed")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withMessage("Transaction " + uuid2 + " completed")
                .build();

        LogPattern pattern1 = drainTree.process(event1);
        LogPattern pattern2 = drainTree.process(event2);

        assertThat(pattern1.getId()).isEqualTo(pattern2.getId());
        assertThat(pattern1.getTemplate()).contains("Transaction")
                .contains("<*>")
                .contains("completed");
    }

    @Test
    @DisplayName("数字ID被正确识别为变量")
    void shouldTreatLongDigitStringsAsVariables() {
        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withMessage("User 123456789 logged in")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withMessage("User 987654321 logged in")
                .build();

        LogPattern pattern1 = drainTree.process(event1);
        LogPattern pattern2 = drainTree.process(event2);

        assertThat(pattern1.getId()).isEqualTo(pattern2.getId());
        assertThat(pattern1.getTemplate()).contains("User")
                .contains("<*>")
                .contains("logged");
    }

    @Test
    @DisplayName("模式更新时泛化模板")
    void shouldGeneralizeTemplateWhenPatternUpdates() {
        LogEvent event1 = LogEventBuilder.aLogEvent()
                .withMessage("User login from 192.168.1.1 success")
                .build();

        LogEvent event2 = LogEventBuilder.aLogEvent()
                .withMessage("User login from 10.0.0.1 failed")
                .build();

        drainTree.process(event1);
        LogPattern pattern = drainTree.process(event2);

        assertThat(pattern.getTemplate()).contains("User")
                .contains("login")
                .contains("from")
                .contains("<*>");
    }

    @Test
    @DisplayName("空消息或空白消息返回null")
    void shouldReturnNullForEmptyOrBlankMessages() {
        LogEvent emptyEvent = LogEventBuilder.aLogEvent().withMessage("").build();
        LogEvent blankEvent = LogEventBuilder.aLogEvent().withMessage("   ").build();
        LogEvent nullEvent = LogEventBuilder.aLogEvent().withMessage(null).build();

        assertThat(drainTree.process(emptyEvent)).isNull();
        assertThat(drainTree.process(blankEvent)).isNull();
        assertThat(drainTree.process(nullEvent)).isNull();
    }

    @Test
    @DisplayName("按频率排序获取Top K模式")
    void shouldGetTopKPatternsSortedByFrequency() {
        for (int i = 0; i < 10; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withUserLoginMessage("user_" + i, "192.168.1." + i)
                    .build();
            drainTree.process(event);
        }

        for (int i = 0; i < 5; i++) {
            LogEvent event = LogEventBuilder.aLogEvent()
                    .withConnectionTimeoutMessage("server-" + i, 8080, i)
                    .build();
            drainTree.process(event);
        }

        LogEvent singleEvent = LogEventBuilder.aLogEvent()
                .withMessage("Single unique event")
                .build();
        drainTree.process(singleEvent);

        List<LogPattern> top2 = drainTree.getTopKPatterns(2);

        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).getTotalCount()).isGreaterThanOrEqualTo(10);
        assertThat(top2.get(1).getTotalCount()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("快速路径缓存命中")
    void shouldUseFastPathCacheForRepeatedMessages() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_cache", "10.0.0.1")
                .build();

        LogPattern firstPattern = drainTree.process(event);
        LogPattern cachedPattern = drainTree.process(event);

        assertThat(cachedPattern).isSameAs(firstPattern);
        assertThat(firstPattern.getTotalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("正确识别静态token和变量slot")
    void shouldIdentifyStaticTokensAndVariableSlots() {
        LogEvent event = LogEventBuilder.aLogEvent()
                .withUserLoginMessage("user_1", "192.168.1.1")
                .build();

        LogPattern pattern = drainTree.process(event);

        assertThat(pattern.getStaticTokens()).isNotEmpty();
        assertThat(pattern.getVariableSlots()).isNotEmpty();
        assertThat(pattern.getStaticTokens()).contains("User", "login", "from");
    }
}
