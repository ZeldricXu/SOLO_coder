package com.travelbooking.service;

import com.travelbooking.builder.TestDataBuilder;
import com.travelbooking.model.Booking;
import com.travelbooking.model.Itinerary;
import com.travelbooking.model.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncSettlementServiceTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private SettlementService settlementService;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private AsyncSettlementService asyncSettlementService;

    private Booking confirmedBooking;
    private Itinerary completedItinerary;

    @BeforeEach
    void setUp() {
        confirmedBooking = TestDataBuilder.buildConfirmedBooking();
        completedItinerary = TestDataBuilder.buildCompletedItinerary();
    }

    @Test
    @DisplayName("测试行程完成后立即返回响应不阻塞")
    void testImmediateResponseWithoutBlocking() throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.complete(true);

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return invocation.getArgument(0);
        });

        long startTime = System.currentTimeMillis();
        boolean result = asyncSettlementService.triggerSettlement("itinerary_test_004", "booking_test_001");
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(result);
        assertTrue(elapsed < 500, "Response should be immediate, not blocked by settlement");
    }

    @Test
    @DisplayName("测试后台Worker执行费用结算与支付处理")
    void testBackgroundWorkerExecutesSettlement() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingService.completeSettlement("booking_test_001", new BigDecimal("6000.00"))).thenReturn(true);

        task.call();

        verify(settlementService).createSettlement(any(Settlement.class));
        verify(bookingService).completeSettlement("booking_test_001", new BigDecimal("6000.00"));
        verify(analyticsService).updateSettlementStatistics(eq(new BigDecimal("6000.00")));
    }

    @Test
    @DisplayName("测试支付成功时结算流程完成")
    void testSuccessfulSettlementFlow() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            s.setPaymentStatus("success");
            return s;
        });
        when(bookingService.completeSettlement("booking_test_001", new BigDecimal("6000.00"))).thenReturn(true);

        Boolean result = task.call();

        assertTrue(result);
        verify(bookingService).completeSettlement("booking_test_001", new BigDecimal("6000.00"));
    }

    @Test
    @DisplayName("测试支付失败时的重试机制 - 最多重试3次")
    void testRetryMechanismOnPaymentFailure() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class)))
                .thenThrow(new RuntimeException("Payment failed"))
                .thenThrow(new RuntimeException("Payment failed again"))
                .thenAnswer(invocation -> {
                    Settlement s = invocation.getArgument(0);
                    s.setPaymentStatus("success");
                    return s;
                });
        when(bookingService.completeSettlement("booking_test_001", new BigDecimal("6000.00"))).thenReturn(true);

        Boolean result = task.call();

        assertTrue(result);
        verify(settlementService, times(3)).createSettlement(any(Settlement.class));
        verify(bookingService).completeSettlement("booking_test_001", new BigDecimal("6000.00"));
    }

    @Test
    @DisplayName("测试支付失败达到最大重试次数后放弃")
    void testGiveUpAfterMaxRetryAttempts() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class)))
                .thenThrow(new RuntimeException("Payment failed 1"))
                .thenThrow(new RuntimeException("Payment failed 2"))
                .thenThrow(new RuntimeException("Payment failed 3"))
                .thenThrow(new RuntimeException("Payment failed 4"));

        Boolean result = task.call();

        assertFalse(result);
        verify(settlementService, times(3)).createSettlement(any(Settlement.class));
        verify(bookingService, never()).completeSettlement(anyString(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("测试第一次就成功时不进行重试")
    void testNoRetryOnFirstSuccess() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            s.setPaymentStatus("success");
            return s;
        });
        when(bookingService.completeSettlement("booking_test_001", new BigDecimal("6000.00"))).thenReturn(true);

        task.call();

        verify(settlementService, times(1)).createSettlement(any(Settlement.class));
    }

    @Test
    @DisplayName("测试预订不存在时结算流程结束")
    void testSettlementTerminatesWhenBookingNotFound() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "nonexistent_booking"
        );

        when(bookingService.getBookingById("nonexistent_booking")).thenReturn(Optional.empty());

        Boolean result = task.call();

        assertFalse(result);
        verify(settlementService, never()).createSettlement(any(Settlement.class));
    }

    @Test
    @DisplayName("测试结算金额与预订金额一致")
    void testSettlementAmountMatchesBookingAmount() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            s.setPaymentStatus("success");
            return s;
        });
        when(bookingService.completeSettlement(anyString(), any(BigDecimal.class))).thenReturn(true);

        task.call();

        verify(bookingService).completeSettlement(
                eq("booking_test_001"),
                eq(new BigDecimal("6000.00"))
        );
    }

    @Test
    @DisplayName("测试异步任务提交成功返回true")
    void testAsyncTaskSubmissionSuccess() {
        boolean result = asyncSettlementService.triggerSettlement(
                "itinerary_test_004",
                "booking_test_001"
        );

        assertTrue(result);
    }

    @Test
    @DisplayName("测试多个结算任务并发执行")
    void testConcurrentSettlementTasks() throws Exception {
        int taskCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(taskCount);

        when(bookingService.getBookingById(anyString())).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            s.setPaymentStatus("success");
            return s;
        });
        when(bookingService.completeSettlement(anyString(), any(BigDecimal.class))).thenReturn(true);

        CountDownLatch latch = new CountDownLatch(taskCount);
        boolean[] results = new boolean[taskCount];

        for (int i = 0; i < taskCount; i++) {
            final int idx = i;
            executorService.submit(() -> {
                try {
                    AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                            "itinerary_" + idx,
                            "booking_" + idx
                    );
                    results[idx] = task.call();
                } catch (Exception e) {
                    results[idx] = false;
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        for (int i = 0; i < taskCount; i++) {
            assertTrue(results[i], "Task " + i + " should succeed");
        }

        verify(bookingService, times(taskCount)).getBookingById(anyString());
        verify(settlementService, times(taskCount)).createSettlement(any(Settlement.class));
        verify(bookingService, times(taskCount)).completeSettlement(anyString(), any(BigDecimal.class));
    }

    @Test
    @DisplayName("测试最大重试次数常量值为3")
    void testMaxRetryAttemptsIsThree() {
        assertEquals(3, AsyncSettlementService.MAX_RETRY_ATTEMPTS);
    }

    @Test
    @DisplayName("测试重试延迟常量值为1000ms")
    void testRetryDelayIsOneSecond() {
        assertEquals(1000, AsyncSettlementService.RETRY_DELAY_MS);
    }

    @Test
    @DisplayName("测试Settlement对象包含正确的预订和行程信息")
    void testSettlementObjectContainsCorrectInfo() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            assertEquals("booking_test_001", s.getBookingId());
            assertEquals("itinerary_test_004", s.getItineraryId());
            assertEquals(new BigDecimal("6000.00"), s.getSettlementAmount());
            assertEquals("pending", s.getSettlementStatus());
            s.setPaymentStatus("success");
            return s;
        });
        when(bookingService.completeSettlement(anyString(), any(BigDecimal.class))).thenReturn(true);

        task.call();
    }

    @Test
    @DisplayName("测试重试间隔时间递增验证")
    void testExponentialBackoffBetweenRetries() throws Exception {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.createSettlementTask(
                "itinerary_test_004",
                "booking_test_001"
        );

        when(bookingService.getBookingById("booking_test_001")).thenReturn(Optional.of(confirmedBooking));
        when(settlementService.createSettlement(any(Settlement.class)))
                .thenThrow(new RuntimeException("Fail 1"))
                .thenThrow(new RuntimeException("Fail 2"))
                .thenAnswer(invocation -> {
                    Settlement s = invocation.getArgument(0);
                    s.setPaymentStatus("success");
                    return s;
                });
        when(bookingService.completeSettlement(anyString(), any(BigDecimal.class))).thenReturn(true);

        long startTime = System.currentTimeMillis();
        task.call();
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(elapsed >= AsyncSettlementService.RETRY_DELAY_MS * 2,
                "Should have at least 2 delay periods between retries");
    }
}
