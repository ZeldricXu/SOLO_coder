package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.enterprise.gateway.logprocessor.parser.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TwoPhaseFormatDetectorTest {

    private TwoPhaseFormatDetector twoPhaseDetector;
    private FormatDetector formatDetector;
    private FastFeatureExtractor featureExtractor;
    private SingleBytePrefixFilter byteFilter;
    private List<LogParser> allParsers;

    @BeforeEach
    void setUp() {
        allParsers = new ArrayList<>();
        allParsers.add(new JsonLogParser());
        allParsers.add(new CsvLogParser());
        allParsers.add(new LogbackLogParser());
        allParsers.add(new NginxLogParser());
        allParsers.add(new SyslogParser());

        featureExtractor = new FastFeatureExtractor();
        byteFilter = new SingleBytePrefixFilter(allParsers);
        twoPhaseDetector = new TwoPhaseFormatDetector(featureExtractor, byteFilter);
        formatDetector = new FormatDetector(allParsers);
    }

    @ParameterizedTest
    @MethodSource("provideVariousLogLines")
    @DisplayName("TwoPhaseFormatDetector 应与 FormatDetector 产生完全相同的结果")
    void testDetectFormatConsistency(String logLine) {
        LogFormat expected = formatDetector.detectFormat(logLine);
        LogFormat actual = twoPhaseDetector.detectFormat(logLine);
        assertEquals(expected, actual, "对于日志行: " + logLine);
    }

    @Test
    @DisplayName("快速路径命中率应大于90%")
    void testFastPathHitRate() {
        twoPhaseDetector.resetCounters();

        List<String> mixedLogs = generateMixedLogs(1000);
        for (String log : mixedLogs) {
            twoPhaseDetector.detectFormat(log);
        }

        double hitRate = twoPhaseDetector.getFastPathHitRate();
        assertTrue(hitRate > 0.90,
                "快速路径命中率应为 > 90%，实际为: " + String.format("%.2f%%", hitRate * 100));
    }

    @Test
    @DisplayName("边界情况测试: 空行、null、超长行、格式错误的JSON")
    void testEdgeCases() {
        assertEquals(LogFormat.UNKNOWN, twoPhaseDetector.detectFormat(""));
        assertEquals(LogFormat.UNKNOWN, twoPhaseDetector.detectFormat(null));
        assertNull(twoPhaseDetector.parse(""));
        assertNull(twoPhaseDetector.parse(null));

        String veryLongLine = "a".repeat(10000);
        assertEquals(LogFormat.UNKNOWN, twoPhaseDetector.detectFormat(veryLongLine));

        String malformedJson = "{\"service\": \"test\", \"level\": \"INFO\"";
        assertEquals(LogFormat.UNKNOWN, twoPhaseDetector.detectFormat(malformedJson));
    }

    @Test
    @DisplayName("计数器应正确递增")
    void testCountersIncrementCorrectly() {
        twoPhaseDetector.resetCounters();

        assertEquals(0, twoPhaseDetector.getTotalCalls().get());
        assertEquals(0, twoPhaseDetector.getFastPathHits().get());
        assertEquals(0, twoPhaseDetector.getSlowPathFallbacks().get());

        String jsonLog = "{\"timestamp\": 1718000000000, \"service\": \"api\", \"level\": \"INFO\", \"message\": \"test\"}";
        twoPhaseDetector.detectFormat(jsonLog);

        assertEquals(1, twoPhaseDetector.getTotalCalls().get());
        assertEquals(1, twoPhaseDetector.getFastPathHits().get());
        assertEquals(0, twoPhaseDetector.getSlowPathFallbacks().get());

        String unknownLog = "this is an unknown format log line";
        twoPhaseDetector.detectFormat(unknownLog);

        assertEquals(2, twoPhaseDetector.getTotalCalls().get());
        assertEquals(1, twoPhaseDetector.getFastPathHits().get());
        assertEquals(1, twoPhaseDetector.getSlowPathFallbacks().get());

        twoPhaseDetector.resetCounters();
        assertEquals(0, twoPhaseDetector.getTotalCalls().get());
        assertEquals(0, twoPhaseDetector.getFastPathHits().get());
        assertEquals(0, twoPhaseDetector.getSlowPathFallbacks().get());
    }

    @Test
    @DisplayName("parse方法应产生与detectFormat一致的结果")
    void testParseConsistency() {
        String jsonLog = "{\"timestamp\": 1718000000000, \"service\": \"api\", \"level\": \"INFO\", \"message\": \"test\", \"duration\": \"100ms\"}";
        assertEquals(formatDetector.detectFormat(jsonLog), twoPhaseDetector.detectFormat(jsonLog));
        assertNotNull(twoPhaseDetector.parse(jsonLog));
        assertEquals(formatDetector.parse(jsonLog).getService(), twoPhaseDetector.parse(jsonLog).getService());

        String invalidLog = "invalid log format";
        assertNull(twoPhaseDetector.parse(invalidLog));
    }

    @Test
    @DisplayName("构造函数参数验证")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new TwoPhaseFormatDetector(null, byteFilter));
        assertThrows(IllegalArgumentException.class, () ->
                new TwoPhaseFormatDetector(featureExtractor, null));
    }

    @Test
    @DisplayName("空输入时命中率应为0")
    void testHitRateWithNoCalls() {
        twoPhaseDetector.resetCounters();
        assertEquals(0.0, twoPhaseDetector.getFastPathHitRate(), 0.001);
    }

    private static Stream<String> provideVariousLogLines() {
        return Stream.of(
                "{\"timestamp\": 1718000000000, \"service\": \"api\", \"level\": \"INFO\", \"message\": \"test\"}",
                "{\"timestamp\": 1718000000001, \"service\": \"auth\", \"level\": \"ERROR\", \"message\": \"error\", \"statusCode\": \"500\"}",
                "1718000000000,api,INFO,test message,trace123,200,GET,/api/test,100ms",
                "1718000000001,auth,ERROR,auth failed,trace456,401,POST,/api/auth,50ms",
                "2024-06-10 10:30:00.123 [main] INFO com.example.Service - Application started",
                "2024-06-10 10:30:01.456 [thread-1] ERROR com.example.Service - Database connection failed",
                "192.168.1.1 - - [10/Jun/2024:10:30:00 +0000] \"GET /api/test HTTP/1.1\" 200 1024",
                "10.0.0.1 - - [10/Jun/2024:10:30:01 +0000] \"POST /api/auth HTTP/1.1\" 401 256",
                "<123>Jun 10 10:30:00 hostname kernel: CPU temperature is high",
                "<456>Jun 10 10:30:01 server sshd: Failed password for root",
                "this is a plain text log with no format",
                "random garbage that doesn't match anything"
        );
    }

    private List<String> generateMixedLogs(int count) {
        List<String> logs = new ArrayList<>();
        String[] jsonLogs = {
                "{\"timestamp\": %d, \"service\": \"api\", \"level\": \"INFO\", \"message\": \"request processed\", \"duration\": \"%dms\"}",
                "{\"timestamp\": %d, \"service\": \"auth\", \"level\": \"ERROR\", \"message\": \"authentication failed\", \"statusCode\": \"401\", \"duration\": \"%dms\"}",
                "{\"timestamp\": %d, \"service\": \"payment\", \"level\": \"DEBUG\", \"message\": \"transaction processed\", \"duration\": \"%dms\"}"
        };
        String[] csvLogs = {
                "%d,api,INFO,request processed,trace%d,200,GET,/api/test,%dms",
                "%d,auth,ERROR,login failed,trace%d,401,POST,/api/login,%dms"
        };
        String[] logbackLogs = {
                "2024-06-10 10:30:%02d.%03d [main] INFO com.example.Service - Processing request %d",
                "2024-06-10 10:30:%02d.%03d [thread-1] ERROR com.example.Service - Error in request %d"
        };
        String[] nginxLogs = {
                "192.168.1.%d - - [10/Jun/2024:10:30:%02d +0000] \"GET /api/test HTTP/1.1\" 200 %d",
                "10.0.0.%d - - [10/Jun/2024:10:30:%02d +0000] \"POST /api/auth HTTP/1.1\" 401 %d"
        };
        String[] syslogLogs = {
                "<123>Jun 10 10:30:%02d host%d kernel: Message %d",
                "<456>Jun 10 10:30:%02d server%d sshd: Connection %d"
        };

        long baseTime = 1718000000000L;
        for (int i = 0; i < count; i++) {
            int formatType = i % 5;
            int seq = i / 5;
            switch (formatType) {
                case 0:
                    logs.add(String.format(jsonLogs[seq % jsonLogs.length], baseTime + i, seq % 500));
                    break;
                case 1:
                    logs.add(String.format(csvLogs[seq % csvLogs.length], baseTime + i, seq, seq % 500));
                    break;
                case 2:
                    logs.add(String.format(logbackLogs[seq % logbackLogs.length], seq % 60, seq % 1000, seq));
                    break;
                case 3:
                    logs.add(String.format(nginxLogs[seq % nginxLogs.length], seq % 255, seq % 60, seq % 10000));
                    break;
                case 4:
                    logs.add(String.format(syslogLogs[seq % syslogLogs.length], seq % 60, seq % 10, seq));
                    break;
            }
        }
        return logs;
    }
}
