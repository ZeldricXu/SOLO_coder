package com.datapipeline.gateway.tracing;

import com.datapipeline.common.test.TestDataFactory;
import com.datapipeline.common.test.TestUtils;
import com.datapipeline.common.tracing.TraceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TraceManagerTest {

    private TraceManager manager;

    @BeforeEach
    void setUp() {
        manager = new TraceManager();
    }

    @Nested
    @DisplayName("基本追踪功能测试")
    class BasicTracingTests {

        @Test
        @DisplayName("应成功创建新的追踪上下文")
        void testStartTrace() {
            Map<String, String> headers = TestDataFactory.createHeaders();

            TraceContext ctx = manager.startTrace("api_request", headers);

            assertThat(ctx).isNotNull();
            assertThat(ctx.getTraceId()).isNotNull();
            assertThat(ctx.getSpanId()).isNotNull();
            assertThat(ctx.getOperation()).isEqualTo("api_request");
        }

        @Test
        @DisplayName("应使用请求头中提供的traceId")
        void testUseProvidedTraceId() {
            String expectedTraceId = "trace-12345";
            Map<String, String> headers = TestDataFactory.createHeaders();
            headers.put("X-Trace-Id", expectedTraceId);

            TraceContext ctx = manager.startTrace("api_request", headers);

            assertThat(ctx.getTraceId()).isEqualTo(expectedTraceId);
        }

        @Test
        @DisplayName("无traceId时应生成新的UUID")
        void testGenerateTraceId() {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            TraceContext ctx = manager.startTrace("api_request", headers);

            assertThat(ctx.getTraceId()).isNotNull();
            assertThat(ctx.getTraceId()).isNotEmpty();
        }

        @Test
        @DisplayName("应保留父spanId")
        void testPreserveParentSpanId() {
            String parentSpanId = "parent-span-789";
            Map<String, String> headers = TestDataFactory.createHeaders();
            headers.put("X-Parent-Span-Id", parentSpanId);

            TraceContext ctx = manager.startTrace("child_operation", headers);

            assertThat(ctx.getParentSpanId()).isEqualTo(parentSpanId);
        }

        @Test
        @DisplayName("空headers应正常工作")
        void testEmptyHeaders() {
            TraceContext ctx = manager.startTrace("test_operation");

            assertThat(ctx).isNotNull();
            assertThat(ctx.getTraceId()).isNotNull();
        }

    }

    @Nested
    @DisplayName("追踪生命周期测试")
    class LifecycleTests {

        @Test
        @DisplayName("成功结束追踪应标记成功")
        void testEndTraceSuccess() {
            TraceContext ctx = manager.startTrace("test_op", new HashMap<>());
            String traceId = ctx.getTraceId();

            assertThat(manager.getActiveTraceCount()).isEqualTo(1);

            manager.endTrace(ctx, true, null);

            assertThat(manager.getActiveTraceCount()).isEqualTo(0);
            assertThat(ctx.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("失败结束追踪应标记错误")
        void testEndTraceFailure() {
            TraceContext ctx = manager.startTrace("test_op", new HashMap<>());
            String errorCode = "VALIDATION_ERROR";

            manager.endTrace(ctx, false, errorCode);

            assertThat(manager.getActiveTraceCount()).isEqualTo(0);
            assertThat(ctx.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("空上下文应安全处理")
        void testEndNullTrace() {
            Assertions.assertDoesNotThrow(() -> manager.endTrace(null, true, null));
        }

        @Test
        @DisplayName("获取不存在的trace应返回null")
        void testGetNonExistentTrace() {
            TraceContext ctx = manager.getTrace("non-existent-trace");
            assertThat(ctx).isNull();
        }

    }

    @Nested
    @DisplayName("追踪传播测试")
    class PropagationTests {

        @Test
        @DisplayName("应创建传播用的headers")
        void testCreatePropagationHeaders() {
            Map<String, String> headers = TestDataFactory.createHeaders();
            headers.put("X-Parent-Span-Id", "parent-123");

            TraceContext ctx = manager.startTrace("child_op", headers);

            Map<String, String> propagationHeaders = manager.createPropagationHeaders(ctx);

            assertThat(propagationHeaders).containsKey("X-Trace-Id");
            assertThat(propagationHeaders).containsKey("X-Span-Id");
            assertThat(propagationHeaders).containsKey("X-Parent-Span-Id");
            assertThat(propagationHeaders.get("X-Trace-Id")).isEqualTo(ctx.getTraceId());
            assertThat(propagationHeaders.get("X-Span-Id")).isEqualTo(ctx.getSpanId());
            assertThat(propagationHeaders.get("X-Parent-Span-Id")).isEqualTo("parent-123");
        }

        @Test
        @DisplayName("无父span时不应包含X-Parent-Span-Id")
        void testPropagationHeadersNoParent() {
            Map<String, String> headers = new HashMap<>();

            TraceContext ctx = manager.startTrace("root_op", headers);

            Map<String, String> propagationHeaders = manager.createPropagationHeaders(ctx);

            assertThat(propagationHeaders).containsKey("X-Trace-Id");
            assertThat(propagationHeaders).containsKey("X-Span-Id");
            assertThat(propagationHeaders).doesNotContainKey("X-Parent-Span-Id");
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发追踪应保持正确状态")
        void testConcurrentTracing() throws Exception {
            int threadCount = 30;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger activeCount = new AtomicInteger(0);
            AtomicInteger maxActive = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        startLatch.await();
                        String traceId = "trace-" + index;
                        Map<String, String> headers = new HashMap<>();
                        headers.put("X-Trace-Id", traceId);

                        TraceContext ctx = manager.startTrace("test_op_" + index, headers);

                        int currentActive = manager.getActiveTraceCount();
                        maxActive.set(Math.max(maxActive.get(), currentActive));
                        activeCount.incrementAndGet();

                        TestUtils.sleepQuietly(10);

                        manager.endTrace(ctx, true, null);
                        activeCount.decrementAndGet();

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);

            assertThat(manager.getActiveTraceCount()).as("所有追踪应已结束").isEqualTo(0);
            assertThat(maxActive.get()).as("峰值追踪数应大于0").isGreaterThan(0);
        }

        @Test
        @DisplayName("并发获取传播headers应线程安全")
        void testConcurrentPropagationHeaders() throws Exception {
            int threadCount = 50;
            int iterationsPerThread = 100;

            TestUtils.executeConcurrently(threadCount, iterationsPerThread, iteration -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("X-Trace-Id", "trace-" + iteration);
                if (iteration % 2 == 0) {
                    headers.put("X-Parent-Span-Id", "parent-" + iteration);
                }

                TraceContext ctx = manager.startTrace("op-" + iteration, headers);
                Map<String, String> propagationHeaders = manager.createPropagationHeaders(ctx);

                assertThat(propagationHeaders).containsKey("X-Trace-Id");
                assertThat(propagationHeaders.get("X-Trace-Id")).isEqualTo(ctx.getTraceId());

                manager.endTrace(ctx, true, null);
            });

            assertThat(manager.getActiveTraceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("getTrace在并发下应返回正确结果")
        void testConcurrentGetTrace() throws Exception {
            int threadCount = 20;
            Map<String, TraceContext> traceContexts = new java.util.concurrent.ConcurrentHashMap<>();

            for (int i = 0; i < threadCount; i++) {
                String traceId = "trace-" + i;
                Map<String, String> headers = new HashMap<>();
                headers.put("X-Trace-Id", traceId);
                TraceContext ctx = manager.startTrace("op-" + i, headers);
                traceContexts.put(traceId, ctx);
            }

            assertThat(manager.getActiveTraceCount()).isEqualTo(threadCount);

            TestUtils.executeConcurrently(5, threadCount, iteration -> {
                String traceId = "trace-" + iteration;
                TraceContext retrieved = manager.getTrace(traceId);
                assertThat(retrieved).isNotNull();
                assertThat(retrieved.getTraceId()).isEqualTo(traceId);
            });

            traceContexts.values().forEach(ctx -> manager.endTrace(ctx, true, null));
            assertThat(manager.getActiveTraceCount()).isEqualTo(0);
        }

    }

    @Nested
    @DisplayName("活跃追踪计数测试")
    class ActiveTraceCountTests {

        @Test
        @DisplayName("开始追踪应增加活跃计数")
        void testStartTraceIncrementsCount() {
            assertThat(manager.getActiveTraceCount()).isEqualTo(0);

            manager.startTrace("op1", new HashMap<>());
            assertThat(manager.getActiveTraceCount()).isEqualTo(1);

            manager.startTrace("op2", new HashMap<>());
            assertThat(manager.getActiveTraceCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("结束追踪应减少活跃计数")
        void testEndTraceDecrementsCount() {
            TraceContext ctx1 = manager.startTrace("op1", new HashMap<>());
            TraceContext ctx2 = manager.startTrace("op2", new HashMap<>());

            assertThat(manager.getActiveTraceCount()).isEqualTo(2);

            manager.endTrace(ctx1, true, null);
            assertThat(manager.getActiveTraceCount()).isEqualTo(1);

            manager.endTrace(ctx2, true, null);
            assertThat(manager.getActiveTraceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("重复traceId应覆盖")
        void testDuplicateTraceId() {
            Map<String, String> headers1 = new HashMap<>();
            headers1.put("X-Trace-Id", "shared-trace");
            TraceContext ctx1 = manager.startTrace("op1", headers1);

            assertThat(manager.getActiveTraceCount()).isEqualTo(1);

            Map<String, String> headers2 = new HashMap<>();
            headers2.put("X-Trace-Id", "shared-trace");
            TraceContext ctx2 = manager.startTrace("op2", headers2);

            assertThat(manager.getActiveTraceCount()).isEqualTo(1);
            assertThat(manager.getTrace("shared-trace")).isEqualTo(ctx2);
        }

    }

}
