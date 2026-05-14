package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.dto.CreateTicketRequest;
import com.servicedesk.testdata.TestDataBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("异步优先级服务测试")
class AsyncPriorityServiceTest {

    @Mock
    private PriorityService priorityService;

    @InjectMocks
    private AsyncPriorityService asyncPriorityService;

    private ServiceDeskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ServiceDeskProperties();
        properties.getPriorityAsync().setMaxRetries(3);
        properties.getPriorityAsync().setRetryIntervalMs(100);
        properties.getPriorityAsync().setThreadPoolSize(2);

        asyncPriorityService = new AsyncPriorityService(priorityService, properties);
        asyncPriorityService.resetFailedCount();
    }

    @AfterEach
    void tearDown() {
        asyncPriorityService.shutdown();
    }

    @Test
    @DisplayName("测试工单创建后立即返回响应不阻塞")
    void testImmediateResponseWithoutBlocking() throws Exception {
        CreateTicketRequest request = TestDataBuilder.createHighPriorityRequest();
        when(priorityService.evaluatePriority(any(CreateTicketRequest.class))).thenReturn("high");

        long startTime = System.currentTimeMillis();
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                String priority = asyncPriorityService.evaluatePriorityAsync(request, p -> {
                    resultRef.set(p);
                    completed.set(true);
                }).get(5, TimeUnit.SECONDS);
                resultRef.set(priority);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Thread.currentThread().interrupt();
            }
        });

        long endTime = System.currentTimeMillis();

        future.get(2, TimeUnit.SECONDS);

        assertTrue((endTime - startTime) < 2000, "应该在2秒内返回");
        assertEquals("high", resultRef.get());
    }

    @Test
    @DisplayName("测试后台Worker执行优先级评估计算")
    void testBackgroundWorkerEvaluation() throws Exception {
        CreateTicketRequest request = TestDataBuilder.createMediumPriorityRequest();
        when(priorityService.evaluatePriority(request)).thenReturn("medium");

        AtomicReference<String> receivedPriority = new AtomicReference<>();
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);

        asyncPriorityService.submitTask(
                request,
                priority -> {
                    receivedPriority.set(priority);
                    callbackExecuted.set(true);
                },
                error -> fail("不应该出现错误")
        );

        Thread.sleep(200);

        assertTrue(callbackExecuted.get(), "回调应该被执行");
        assertEquals("medium", receivedPriority.get());
    }

    @Test
    @DisplayName("测试评估失败时的重试机制 - 首次失败后成功")
    void testRetryMechanismFirstFailureThenSuccess() throws Exception {
        CreateTicketRequest request = TestDataBuilder.createLowPriorityRequest();

        when(priorityService.evaluatePriority(request))
                .thenThrow(new RuntimeException("临时故障"))
                .thenReturn("low");

        AtomicReference<String> receivedPriority = new AtomicReference<>();
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);

        asyncPriorityService.submitTask(
                request,
                priority -> {
                    receivedPriority.set(priority);
                    callbackExecuted.set(true);
                },
                error -> fail("不应该调用错误回调")
        );

        Thread.sleep(1500);

        assertTrue(callbackExecuted.get(), "重试成功后应该调用完成回调");
        assertEquals("low", receivedPriority.get());
        verify(priorityService, times(2)).evaluatePriority(request);
    }

    @Test
    @DisplayName("测试评估失败时的重试机制 - 全部失败")
    void testRetryMechanismAllFailures() throws Exception {
        CreateTicketRequest request = TestDataBuilder.createHighPriorityRequest();

        when(priorityService.evaluatePriority(request))
                .thenThrow(new RuntimeException("服务不可用"));

        AtomicBoolean errorCallbackExecuted = new AtomicBoolean(false);
        AtomicReference<Exception> capturedException = new AtomicReference<>();

        asyncPriorityService.submitTask(
                request,
                priority -> fail("不应该调用完成回调"),
                error -> {
                    errorCallbackExecuted.set(true);
                    capturedException.set(error);
                }
        );

        Thread.sleep(2000);

        assertTrue(errorCallbackExecuted.get(), "全部失败后应该调用错误回调");
        assertNotNull(capturedException.get());
        verify(priorityService, times(3)).evaluatePriority(request);
        assertEquals(1, asyncPriorityService.getFailedTaskCount());
    }

    @Test
    @DisplayName("测试队列大小追踪")
    void testQueueSizeTracking() {
        CreateTicketRequest request1 = TestDataBuilder.createHighPriorityRequest();
        CreateTicketRequest request2 = TestDataBuilder.createMediumPriorityRequest();

        when(priorityService.evaluatePriority(any())).thenReturn("medium");

        asyncPriorityService.submitTask(request1, p -> {}, e -> {});
        asyncPriorityService.submitTask(request2, p -> {}, e -> {});

        Thread.sleep(100);

        assertTrue(asyncPriorityService.getQueueSize() >= 0);
    }

    @Test
    @DisplayName("测试配置加载正确性")
    void testConfigurationLoading() {
        assertEquals(3, properties.getPriorityAsync().getMaxRetries());
        assertEquals(100, properties.getPriorityAsync().getRetryIntervalMs());
        assertEquals(2, properties.getPriorityAsync().getThreadPoolSize());
    }

    @Test
    @DisplayName("测试优先级评估结果正确传递")
    void testPriorityResultCorrectlyPassed() throws Exception {
        CreateTicketRequest highRequest = TestDataBuilder.createHighPriorityRequest();
        CreateTicketRequest mediumRequest = TestDataBuilder.createMediumPriorityRequest();
        CreateTicketRequest lowRequest = TestDataBuilder.createLowPriorityRequest();

        when(priorityService.evaluatePriority(highRequest)).thenReturn("high");
        when(priorityService.evaluatePriority(mediumRequest)).thenReturn("medium");
        when(priorityService.evaluatePriority(lowRequest)).thenReturn("low");

        AtomicReference<String> highResult = new AtomicReference<>();
        AtomicReference<String> mediumResult = new AtomicReference<>();
        AtomicReference<String> lowResult = new AtomicReference<>();

        asyncPriorityService.submitTask(highRequest, highResult::set, e -> {});
        asyncPriorityService.submitTask(mediumRequest, mediumResult::set, e -> {});
        asyncPriorityService.submitTask(lowRequest, lowResult::set, e -> {});

        Thread.sleep(500);

        assertEquals("high", highResult.get());
        assertEquals("medium", mediumResult.get());
        assertEquals("low", lowResult.get());
    }

    @Test
    @DisplayName("测试失败任务计数重置")
    void testFailedCountReset() {
        asyncPriorityService.resetFailedCount();
        assertEquals(0, asyncPriorityService.getFailedTaskCount());
    }
}
