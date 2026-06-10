package com.loganalytics.pipeline.route;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogRouterTest {

    private PipelineConfig config;
    private LogRouter router;

    @BeforeEach
    void setUp() {
        config = new PipelineConfig();
        router = new LogRouter(config);
    }

    @Test
    void shouldRouteErrorLogsToErrorLogsTopic() {
        LogEvent errorEvent = LogEventBuilder.aLogEvent()
                .withLevelError()
                .withMessage("Database connection failed")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(errorEvent);

        assertThat(targets).contains(LogRouter.RouteTarget.ERROR_LOGS);
        assertThat(targets).contains(LogRouter.RouteTarget.ANOMALY_DETECTION);
        assertThat(targets).contains(LogRouter.RouteTarget.ANALYTICS);
        assertThat(targets).contains(LogRouter.RouteTarget.ARCHIVE);
        assertThat(errorEvent.getTags()).containsEntry("routed_error_logs", "true");
    }

    @Test
    void shouldRouteWarnLogsToAnomalyDetection() {
        LogEvent warnEvent = LogEventBuilder.aLogEvent()
                .withLevelWarn()
                .withMessage("High memory usage detected")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(warnEvent);

        assertThat(targets).doesNotContain(LogRouter.RouteTarget.ERROR_LOGS);
        assertThat(targets).contains(LogRouter.RouteTarget.ANOMALY_DETECTION);
        assertThat(targets).contains(LogRouter.RouteTarget.ANALYTICS);
        assertThat(targets).contains(LogRouter.RouteTarget.ARCHIVE);
    }

    @Test
    void shouldRouteInfoLogsOnlyToAnalyticsAndArchive() {
        LogEvent infoEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Request processed successfully")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(infoEvent);

        assertThat(targets).doesNotContain(LogRouter.RouteTarget.ERROR_LOGS);
        assertThat(targets).doesNotContain(LogRouter.RouteTarget.ANOMALY_DETECTION);
        assertThat(targets).contains(LogRouter.RouteTarget.ANALYTICS);
        assertThat(targets).contains(LogRouter.RouteTarget.ARCHIVE);
    }

    @Test
    void shouldRouteDebugLogsOnlyToArchive() {
        LogEvent debugEvent = LogEventBuilder.aLogEvent()
                .withLevelDebug()
                .withMessage("Debug trace information")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(debugEvent);

        assertThat(targets).containsExactly(LogRouter.RouteTarget.ARCHIVE);
        assertThat(debugEvent.getTags()).containsEntry("routed_archive", "true");
    }

    @Test
    void shouldNotDuplicateRouteTargets() {
        LogEvent errorEvent = LogEventBuilder.aLogEvent()
                .withLevelError()
                .build();

        List<LogRouter.RouteTarget> targets = router.route(errorEvent);

        assertThat(targets).doesNotHaveDuplicates();
    }

    @Test
    void shouldApplyCustomRoutingRuleWithHigherPriority() {
        LogRouter.RoutingRule customRule = new LogRouter.RoutingRule();
        customRule.setName("critical-payment-errors");
        customRule.setTarget(LogRouter.RouteTarget.ERROR_LOGS);
        customRule.setServiceMatch("payment-service");
        customRule.setMinLevel(LogLevel.ERROR);
        customRule.setPriority(200);
        router.addRule(customRule);

        LogEvent paymentError = LogEventBuilder.aLogEvent()
                .withPaymentService()
                .withLevelError()
                .build();

        LogEvent otherError = LogEventBuilder.aLogEvent()
                .withUserService()
                .withLevelError()
                .build();

        router.route(paymentError);
        router.route(otherError);

        assertThat(router.getRouteCounts().get(LogRouter.RouteTarget.ERROR_LOGS)).isEqualTo(2);
    }

    @Test
    void shouldRouteBasedOnPatternMatch() {
        LogRouter.RoutingRule patternRule = new LogRouter.RoutingRule();
        patternRule.setName("timeout-errors");
        patternRule.setTarget(LogRouter.RouteTarget.ANOMALY_DETECTION);
        patternRule.setPatternMatch(".*timeout.*");
        patternRule.setPriority(150);
        router.addRule(patternRule);

        LogEvent timeoutEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Connection timeout after 30s")
                .build();

        LogEvent normalEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withMessage("Request processed")
                .build();

        List<LogRouter.RouteTarget> timeoutTargets = router.route(timeoutEvent);
        List<LogRouter.RouteTarget> normalTargets = router.route(normalEvent);

        assertThat(timeoutTargets).contains(LogRouter.RouteTarget.ANOMALY_DETECTION);
        assertThat(normalTargets).doesNotContain(LogRouter.RouteTarget.ANOMALY_DETECTION);
    }

    @Test
    void shouldRouteBasedOnTagMatch() {
        LogRouter.RoutingRule tagRule = new LogRouter.RoutingRule();
        tagRule.setName("suspicious-activity");
        tagRule.setTarget(LogRouter.RouteTarget.ANOMALY_DETECTION);
        tagRule.setTagMatch("suspicious");
        tagRule.setPriority(150);
        router.addRule(tagRule);

        LogEvent taggedEvent = LogEventBuilder.aLogEvent()
                .withLevelInfo()
                .withTag("suspicious", "true")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(taggedEvent);

        assertThat(targets).contains(LogRouter.RouteTarget.ANOMALY_DETECTION);
    }

    @Test
    void shouldDropLogsMatchingDropRule() {
        LogRouter.RoutingRule dropRule = new LogRouter.RoutingRule();
        dropRule.setName("drop-noise");
        dropRule.setTarget(LogRouter.RouteTarget.DROP);
        dropRule.setPatternMatch(".*DEBUG.*verbose.*");
        dropRule.setPriority(500);
        router.addRule(dropRule);

        LogEvent noiseEvent = LogEventBuilder.aLogEvent()
                .withLevelDebug()
                .withMessage("DEBUG: verbose tracing information")
                .build();

        List<LogRouter.RouteTarget> targets = router.route(noiseEvent);

        assertThat(targets).isEmpty();
        assertThat(noiseEvent.getTags()).containsEntry("routed", "dropped");
    }

    @Test
    void shouldAlwaysRouteToArchiveWithCatchAllRule() {
        LogEvent[] allEvents = {
                LogEventBuilder.aLogEvent().withLevelTrace().build(),
                LogEventBuilder.aLogEvent().withLevelDebug().build(),
                LogEventBuilder.aLogEvent().withLevelInfo().build(),
                LogEventBuilder.aLogEvent().withLevelWarn().build(),
                LogEventBuilder.aLogEvent().withLevelError().build(),
                LogEventBuilder.aLogEvent().withLevel(LogLevel.FATAL).build()
        };

        for (LogEvent event : allEvents) {
            List<LogRouter.RouteTarget> targets = router.route(event);
            assertThat(targets).contains(LogRouter.RouteTarget.ARCHIVE);
        }
    }

    @Test
    void shouldMaintainRoutingStatistics() {
        int errorCount = 10;
        int warnCount = 20;
        int infoCount = 50;
        int debugCount = 100;

        for (int i = 0; i < errorCount; i++) {
            router.route(LogEventBuilder.aLogEvent().withLevelError().build());
        }
        for (int i = 0; i < warnCount; i++) {
            router.route(LogEventBuilder.aLogEvent().withLevelWarn().build());
        }
        for (int i = 0; i < infoCount; i++) {
            router.route(LogEventBuilder.aLogEvent().withLevelInfo().build());
        }
        for (int i = 0; i < debugCount; i++) {
            router.route(LogEventBuilder.aLogEvent().withLevelDebug().build());
        }

        Map<LogRouter.RouteTarget, Long> counts = router.getRouteCounts();
        assertThat(counts.get(LogRouter.RouteTarget.ERROR_LOGS)).isEqualTo(errorCount);
        assertThat(counts.get(LogRouter.RouteTarget.ANOMALY_DETECTION)).isEqualTo(errorCount + warnCount);
        assertThat(counts.get(LogRouter.RouteTarget.ANALYTICS)).isEqualTo(errorCount + warnCount + infoCount);
        assertThat(counts.get(LogRouter.RouteTarget.ARCHIVE)).isEqualTo(errorCount + warnCount + infoCount + debugCount);
    }

    @Test
    void shouldHandleNullLevelGracefully() {
        LogEvent nullLevelEvent = new LogEvent();
        nullLevelEvent.setLevel(null);

        List<LogRouter.RouteTarget> targets = router.route(nullLevelEvent);

        assertThat(targets).contains(LogRouter.RouteTarget.ARCHIVE);
    }

    @Test
    void shouldHandleNullServiceNameInServiceMatch() {
        LogRouter.RoutingRule serviceRule = new LogRouter.RoutingRule();
        serviceRule.setName("specific-service");
        serviceRule.setTarget(LogRouter.RouteTarget.ANALYTICS);
        serviceRule.setServiceMatch("payment-service");
        serviceRule.setPriority(100);
        router.addRule(serviceRule);

        LogEvent nullServiceEvent = new LogEvent();
        nullServiceEvent.setServiceName(null);
        nullServiceEvent.setLevel(LogLevel.INFO);

        List<LogRouter.RouteTarget> targets = router.route(nullServiceEvent);

        assertThat(targets).doesNotContainNull();
    }

    @Test
    void shouldSortRulesByPriority() {
        LogRouter.RoutingRule lowPriority = new LogRouter.RoutingRule();
        lowPriority.setName("low");
        lowPriority.setPriority(10);
        LogRouter.RoutingRule highPriority = new LogRouter.RoutingRule();
        highPriority.setName("high");
        highPriority.setPriority(100);

        router.addRule(lowPriority);
        router.addRule(highPriority);

        List<LogRouter.RoutingRule> rules = router.getRules();
        assertThat(rules.get(0).getPriority()).isGreaterThanOrEqualTo(rules.get(1).getPriority());
    }
}
