package com.datapipeline.gateway.logging;

import com.datapipeline.common.test.TestDataFactory;
import com.datapipeline.common.test.TestUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggerTest {

    private RequestLogger logger;

    @BeforeEach
    void setUp() {
        logger = new RequestLogger();
    }

    @Nested
    @DisplayName("基本日志功能测试")
    class BasicLoggingTests {

        @Test
        @DisplayName("应成功创建请求日志条目")
        void testCreateLogEntry() {
            Map<String, String> headers = TestDataFactory.createHeaders();
            headers.put("X-Trace-Id", "test-trace-123");

            RequestLogEntry entry = logger.createEntry("POST", "/api/v1/resources", headers);

            assertThat(entry).isNotNull();
            assertThat(entry.getMethod()).isEqualTo("POST");
            assertThat(entry.getPath()).isEqualTo("/api/v1/resources");
            assertThat(entry.getTraceId()).isEqualTo("test-trace-123");
            assertThat(entry.getSpanId()).isNotNull();
            assertThat(entry.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("无traceId时应生成新的traceId")
        void testGenerateTraceIdWhenMissing() {
            Map<String, String> headers = TestDataFactory.createHeaders();
            headers.remove("X-Trace-Id");

            RequestLogEntry entry = logger.createEntry("GET", "/api/v1/status", headers);

            assertThat(entry.getTraceId()).isNotNull();
            assertThat(entry.getTraceId()).isNotEmpty();
        }

        @Test
        @DisplayName("敏感头信息应被遮罩")
        void testMaskSensitiveHeaders() {
            Map<String, String> headers = TestDataFactory.createHeadersWithSensitiveData();

            RequestLogEntry entry = logger.createEntry("POST", "/api/auth", headers);

            assertThat(entry.getRequestHeaders()).isNotNull();
            Map<String, String> loggedHeaders = entry.getRequestHeaders();

            assertThat(loggedHeaders.get("Authorization")).isEqualTo("***");
            assertThat(loggedHeaders.get("X-API-Token")).isEqualTo("***");
            assertThat(loggedHeaders.get("password")).isEqualTo("***");
            assertThat(loggedHeaders.get("Content-Type")).isEqualTo("application/json");
            assertThat(loggedHeaders.get("Accept")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("各种大小写的敏感头都应被遮罩")
        void testMaskSensitiveHeadersCaseInsensitive() {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "secret");
            headers.put("authorization", "secret");
            headers.put("AUTHORIZATION", "secret");
            headers.put("x-auth-token", "secret");
            headers.put("X-Secret-Key", "secret");
            headers.put("USER_PASSWORD", "secret");

            RequestLogEntry entry = logger.createEntry("POST", "/api/test", headers);
            Map<String, String> loggedHeaders = entry.getRequestHeaders();

            assertThat(loggedHeaders.get("Authorization")).isEqualTo("***");
            assertThat(loggedHeaders.get("authorization")).isEqualTo("***");
            assertThat(loggedHeaders.get("AUTHORIZATION")).isEqualTo("***");
            assertThat(loggedHeaders.get("x-auth-token")).isEqualTo("***");
            assertThat(loggedHeaders.get("X-Secret-Key")).isEqualTo("***");
            assertThat(loggedHeaders.get("USER_PASSWORD")).isEqualTo("***");
        }

    }

    @Nested
    @DisplayName("日志记录方法测试")
    class LogMethodTests {

        @Test
        @DisplayName("请求日志应正常记录")
        void testLogRequest() {
            Map<String, String> headers = TestDataFactory.createHeaders();
            String traceId = UUID.randomUUID().toString();

            Assertions.assertDoesNotThrow(() ->
                    logger.logRequest("GET", "/api/v1/resources", headers, traceId)
            );
        }

        @Test
        @DisplayName("响应日志应正常记录")
        void testLogResponse() {
            String traceId = UUID.randomUUID().toString();

            Assertions.assertDoesNotThrow(() ->
                    logger.logResponse("POST", "/api/v1/process", HttpStatus.OK, 150L, traceId)
            );
        }

        @Test
        @DisplayName("错误日志应正常记录")
        void testLogError() {
            String traceId = UUID.randomUUID().toString();
            Throwable error = new RuntimeException("Test error");

            Assertions.assertDoesNotThrow(() ->
                    logger.logError("GET", "/api/v1/fail", error, traceId)
            );
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发创建日志条目应线程安全")
        void testConcurrentLogEntryCreation() throws Exception {
            int threadCount = 20;
            int iterationsPerThread = 100;
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            TestUtils.executeConcurrently(threadCount, iterationsPerThread, iteration -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("X-Trace-Id", "trace-" + iteration);
                headers.put("Authorization", "secret-" + iteration);

                RequestLogEntry entry = logger.createEntry("GET", "/api/test", headers);
                if (entry != null && entry.getTraceId() != null) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
            });

            assertThat(failureCount.get()).as("所有并发操作应成功").isEqualTo(0);
            assertThat(successCount.get()).as("所有条目应成功创建").isEqualTo(threadCount * iterationsPerThread);
        }

        @Test
        @DisplayName("并发日志记录不应相互干扰")
        void testConcurrentLogging() throws Exception {
            int threadCount = 50;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger requestLogCount = new AtomicInteger(0);
            AtomicInteger responseLogCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        String traceId = "trace-" + index;

                        logger.logRequest("GET", "/api/test", Map.of("X-Trace-Id", traceId), traceId);
                        requestLogCount.incrementAndGet();

                        TestUtils.sleepQuietly(1);

                        logger.logResponse("GET", "/api/test", HttpStatus.OK, 100L, traceId);
                        responseLogCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);

            assertThat(requestLogCount.get()).isEqualTo(threadCount);
            assertThat(responseLogCount.get()).isEqualTo(threadCount);
        }

    }

    @Nested
    @DisplayName("空值处理测试")
    class NullHandlingTests {

        @Test
        @DisplayName("空headers应安全处理")
        void testNullHeaders() {
            RequestLogEntry entry = logger.createEntry("GET", "/api/test", new HashMap<>());

            assertThat(entry).isNotNull();
            assertThat(entry.getRequestHeaders()).isNotNull();
            assertThat(entry.getRequestHeaders()).isEmpty();
        }

        @Test
        @DisplayName("空traceId应生成新的")
        void testNullTraceId() {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            RequestLogEntry entry = logger.createEntry("GET", "/api/test", headers);

            assertThat(entry.getTraceId()).isNotNull();
            assertThat(entry.getTraceId()).isNotEmpty();
        }

    }

}
