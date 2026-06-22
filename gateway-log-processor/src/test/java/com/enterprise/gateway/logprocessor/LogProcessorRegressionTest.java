package com.enterprise.gateway.logprocessor;

import com.enterprise.gateway.logprocessor.aggregation.AggregationState;
import com.enterprise.gateway.logprocessor.aggregation.BTreeWindowStore;
import com.enterprise.gateway.logprocessor.aggregation.RingBufferWindowStore;
import com.enterprise.gateway.logprocessor.aggregation.StreamAggregationPipeline;
import com.enterprise.gateway.logprocessor.aggregation.WindowKey;
import com.enterprise.gateway.logprocessor.detector.FastFeatureExtractor;
import com.enterprise.gateway.logprocessor.detector.FormatDetector;
import com.enterprise.gateway.logprocessor.detector.SingleBytePrefixFilter;
import com.enterprise.gateway.logprocessor.detector.TwoPhaseFormatDetector;
import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.parser.CsvLogParser;
import com.enterprise.gateway.logprocessor.parser.JsonLogParser;
import com.enterprise.gateway.logprocessor.parser.LogParser;
import com.enterprise.gateway.logprocessor.parser.LogbackLogParser;
import com.enterprise.gateway.logprocessor.parser.NginxLogParser;
import com.enterprise.gateway.logprocessor.parser.SyslogParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogProcessorRegressionTest {

    private static final long WINDOW_SIZE_MS = 1000;
    private static final int MAX_WINDOWS = 100;
    private static final long RETENTION_MS = 24 * 60 * 60 * 1000L;

    private TwoPhaseFormatDetector twoPhaseDetector;
    private FormatDetector formatDetector;
    private StreamAggregationPipeline oldPipeline;
    private StreamAggregationPipeline newPipeline;

    @BeforeEach
    void setUp() {
        List<LogParser> parsers = new ArrayList<>();
        parsers.add(new JsonLogParser());
        parsers.add(new CsvLogParser());
        parsers.add(new LogbackLogParser());
        parsers.add(new NginxLogParser());
        parsers.add(new SyslogParser());

        FastFeatureExtractor featureExtractor = new FastFeatureExtractor();
        SingleBytePrefixFilter byteFilter = new SingleBytePrefixFilter(parsers);

        twoPhaseDetector = new TwoPhaseFormatDetector(featureExtractor, byteFilter);
        formatDetector = new FormatDetector(parsers);

        BTreeWindowStore btreePrototype = new BTreeWindowStore(WINDOW_SIZE_MS, RETENTION_MS);
        oldPipeline = new StreamAggregationPipeline(btreePrototype);

        RingBufferWindowStore ringPrototype = new RingBufferWindowStore(WINDOW_SIZE_MS, MAX_WINDOWS, RETENTION_MS);
        newPipeline = new StreamAggregationPipeline(ringPrototype);
    }

    @Test
    @DisplayName("端到端回归测试: 1000条混合日志的完整处理")
    void testEndToEndRegression() {
        List<String> logLines = generateMixedLogLines(1000);

        for (String logLine : logLines) {
            LogEntry oldEntry = formatDetector.parse(logLine);
            if (oldEntry != null) {
                oldPipeline.process(oldEntry);
            }

            LogEntry newEntry = twoPhaseDetector.parse(logLine);
            if (newEntry != null) {
                newPipeline.process(newEntry);
            }
        }

        Map<WindowKey, AggregationState> oldSnapshot = oldPipeline.getSnapshot();
        Map<WindowKey, AggregationState> newSnapshot = newPipeline.getSnapshot();

        assertEquals(oldSnapshot.size(), newSnapshot.size(),
                "新旧实现的窗口数量应相同");

        for (Map.Entry<WindowKey, AggregationState> oldEntry : oldSnapshot.entrySet()) {
            WindowKey key = oldEntry.getKey();
            AggregationState oldState = oldEntry.getValue();
            AggregationState newState = newSnapshot.get(key);

            assertNotNull(newState, "新实现应包含窗口: " + key);
            assertAggregationStatesEqual(oldState, newState, key.toString());
        }

        assertEquals(oldPipeline.getDimensionCount(), newPipeline.getDimensionCount(),
                "维度数量应相同");
    }

    @Test
    @DisplayName("格式检测结果应完全一致")
    void testFormatDetectionConsistency() {
        List<String> logLines = generateMixedLogLines(1000);

        int matchCount = 0;
        for (String logLine : logLines) {
            assertEquals(
                    formatDetector.detectFormat(logLine),
                    twoPhaseDetector.detectFormat(logLine),
                    "日志行格式检测不一致: " + logLine
            );
            matchCount++;
        }

        assertEquals(1000, matchCount, "所有日志行的格式检测应匹配");
    }

    @Test
    @DisplayName("解析结果应完全一致")
    void testParsingConsistency() {
        List<String> logLines = generateMixedLogLines(1000);

        for (String logLine : logLines) {
            LogEntry oldEntry = formatDetector.parse(logLine);
            LogEntry newEntry = twoPhaseDetector.parse(logLine);

            if (oldEntry == null) {
                assertNull(newEntry, "新旧实现对同一行的解析结果应一致，行: " + logLine);
            } else {
                assertNotNull(newEntry, "新实现应能解析旧实现能解析的行: " + logLine);
                assertEquals(oldEntry.getFormat(), newEntry.getFormat());
                assertEquals(oldEntry.getService(), newEntry.getService());
                assertEquals(oldEntry.getLevel(), newEntry.getLevel());
                assertEquals(oldEntry.getDuration(), newEntry.getDuration());
                assertEquals(oldEntry.getStatusCode(), newEntry.getStatusCode());
                assertEquals(oldEntry.getMethod(), newEntry.getMethod());
                assertEquals(oldEntry.getPath(), newEntry.getPath());
            }
        }
    }

    @Test
    @DisplayName("边界情况输入不应抛出异常")
    void testEdgeCaseInputsDontThrow() {
        List<String> edgeCases = List.of(
                "",
                null,
                "   ",
                "\t\n\r",
                "{invalid json",
                "not,a,csv,line",
                "2024-13-40 25:70:80.999 [main] INFO Test - invalid date",
                "<999>Bad 99 99:99:99 host msg",
                "999.999.999.999 - - [99/Mon/9999:99:99:99 +9999] \"METHOD / HTTP/9.9\" 999 999999",
                "a".repeat(10000),
                "{\"service\": \"test\", \"level\": \"INFO\", \"message\": \"test\"}",
                "0000000000000,service,level,message,trace,200,GET,/path,100ms",
                "<1>Jan  1 00:00:00 host very long message that goes on and on and on and on and on"
        );

        for (String edgeCase : edgeCases) {
            assertDoesNotThrow(() -> {
                twoPhaseDetector.detectFormat(edgeCase);
                twoPhaseDetector.parse(edgeCase);
                formatDetector.detectFormat(edgeCase);
                formatDetector.parse(edgeCase);
            }, "处理输入时不应抛出异常: " + edgeCase);
        }
    }

    @Test
    @DisplayName("聚合结果应完全相同（窗口对齐验证）")
    void testAggregationResultsIdentical() {
        long baseTime = 1718000000000L;
        String[] services = {"api", "auth", "payment", "notification"};
        String[] levels = {"INFO", "WARN", "ERROR", "DEBUG"};

        for (int i = 0; i < 1000; i++) {
            long timestamp = baseTime + i * 10;
            String service = services[i % services.length];
            String level = levels[i % levels.length];
            int duration = (i % 500) + 1;

            LogEntry entry = LogEntry.builder()
                    .timestamp(timestamp)
                    .service(service)
                    .level(level)
                    .duration(duration + "ms")
                    .build();

            oldPipeline.process(entry);
            newPipeline.process(entry);
        }

        Map<WindowKey, AggregationState> oldSnapshot = oldPipeline.getSnapshot();
        Map<WindowKey, AggregationState> newSnapshot = newPipeline.getSnapshot();

        for (Map.Entry<WindowKey, AggregationState> oldEntry : oldSnapshot.entrySet()) {
            WindowKey key = oldEntry.getKey();
            AggregationState oldState = oldEntry.getValue();
            AggregationState newState = newSnapshot.get(key);

            assertNotNull(newState, "缺少窗口: " + key);
            assertAggregationStatesEqual(oldState, newState, key.toString());
        }

        assertEquals(oldSnapshot.size(), newSnapshot.size(), "窗口总数应相同");
    }

    @Test
    @DisplayName("并发处理时结果一致性")
    void testConcurrentProcessingConsistency() throws InterruptedException {
        int threadCount = 4;
        int logsPerThread = 250;
        long baseTime = 1718000000000L;

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < logsPerThread; i++) {
                    int seq = threadId * logsPerThread + i;
                    long timestamp = baseTime + seq * 10;
                    String service = "svc-" + (seq % 3);
                    String level = seq % 2 == 0 ? "INFO" : "ERROR";
                    String duration = (seq % 100 + 1) + "ms";

                    LogEntry entry = LogEntry.builder()
                            .timestamp(timestamp)
                            .service(service)
                            .level(level)
                            .duration(duration)
                            .build();

                    oldPipeline.process(entry);
                    newPipeline.process(entry);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        Map<WindowKey, AggregationState> oldSnapshot = oldPipeline.getSnapshot();
        Map<WindowKey, AggregationState> newSnapshot = newPipeline.getSnapshot();

        assertEquals(oldSnapshot.size(), newSnapshot.size());

        for (Map.Entry<WindowKey, AggregationState> oldEntry : oldSnapshot.entrySet()) {
            AggregationState newState = newSnapshot.get(oldEntry.getKey());
            assertNotNull(newState);
            assertAggregationStatesEqual(oldEntry.getValue(), newState, oldEntry.getKey().toString());
        }
    }

    @Test
    @DisplayName("快速路径性能指标")
    void testFastPathPerformance() {
        twoPhaseDetector.resetCounters();

        List<String> logLines = generateMixedLogLines(1000);
        for (String logLine : logLines) {
            twoPhaseDetector.detectFormat(logLine);
        }

        assertEquals(1000, twoPhaseDetector.getTotalCalls().get());
        assertTrue(twoPhaseDetector.getFastPathHitRate() > 0.90,
                "快速路径命中率应 > 90%，实际为: " + String.format("%.2f%%",
                        twoPhaseDetector.getFastPathHitRate() * 100));
        assertTrue(twoPhaseDetector.getSlowPathFallbacks().get() < 100,
                "慢速路径回退应 < 100，实际为: " + twoPhaseDetector.getSlowPathFallbacks().get());
    }

    private List<String> generateMixedLogLines(int count) {
        List<String> logs = new ArrayList<>();
        long baseTime = 1718000000000L;

        String[] jsonTemplates = {
                "{\"timestamp\": %d, \"service\": \"%s\", \"level\": \"%s\", \"message\": \"Request processed\", \"duration\": \"%dms\"}",
                "{\"timestamp\": %d, \"service\": \"%s\", \"level\": \"%s\", \"message\": \"Error occurred\", \"statusCode\": \"%d\", \"duration\": \"%dms\"}"
        };

        String[] services = {"api", "auth", "payment", "notification", "search"};
        String[] levels = {"INFO", "WARN", "ERROR", "DEBUG"};

        for (int i = 0; i < count; i++) {
            int formatType = i % 5;
            int seq = i / 5;
            long timestamp = baseTime + i;
            String service = services[i % services.length];
            String level = levels[i % levels.length];
            int duration = (i % 500) + 1;
            int statusCode = 200 + (i % 4) * 100;

            switch (formatType) {
                case 0:
                    logs.add(String.format(jsonTemplates[i % jsonTemplates.length],
                            timestamp, service, level, statusCode, duration));
                    break;
                case 1:
                    logs.add(String.format("%d,%s,%s,message_%d,trace_%d,%d,%s,/api/%s,%dms",
                            timestamp, service, level, i, i, statusCode,
                            i % 2 == 0 ? "GET" : "POST", service, duration));
                    break;
                case 2:
                    logs.add(String.format("2024-06-10 10:%02d:%02d.%03d [main] %s %s - Processing request %d",
                            (i / 60) % 60, i % 60, i % 1000, level, service, i));
                    break;
                case 3:
                    logs.add(String.format("192.168.1.%d - - [10/Jun/2024:10:%02d:%02d +0000] \"%s /api/%s HTTP/1.1\" %d %d",
                            i % 255, (i / 60) % 60, i % 60,
                            i % 2 == 0 ? "GET" : "POST", service, statusCode, duration));
                    break;
                case 4:
                    logs.add(String.format("<%d>Jun 10 10:%02d:%02d %s kernel: Message %d, level=%s",
                            100 + (i % 200), (i / 60) % 60, i % 60, service, i, level));
                    break;
            }
        }

        return logs;
    }

    private void assertAggregationStatesEqual(AggregationState expected, AggregationState actual, String context) {
        assertEquals(expected.getCount(), actual.getCount(), "计数不匹配 - " + context);
        assertEquals(expected.getSum(), actual.getSum(), 0.001, "总和不匹配 - " + context);
        assertEquals(expected.getMin(), actual.getMin(), 0.001, "最小值不匹配 - " + context);
        assertEquals(expected.getMax(), actual.getMax(), 0.001, "最大值不匹配 - " + context);
        assertEquals(expected.getAverage(), actual.getAverage(), 0.001, "平均值不匹配 - " + context);
        assertEquals(expected.getSumOfSquares(), actual.getSumOfSquares(), 0.001, "平方和不匹配 - " + context);
    }
}
