package com.homeservice.service;

import com.homeservice.builder.TestDataBuilder;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Customer;
import com.homeservice.entity.Settlement;
import com.homeservice.entity.Staff;
import com.homeservice.enums.BookingStatus;
import com.homeservice.enums.SettlementStatus;
import com.homeservice.repository.BookingRepository;
import com.homeservice.repository.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncSettlementService 异步结算服务测试")
class AsyncSettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private StaffService staffService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private ServiceHistoryService serviceHistoryService;

    @InjectMocks
    private AsyncSettlementService asyncSettlementService;

    private Staff testStaff;
    private Customer testCustomer;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounters();
        asyncSettlementService.resetCounters();
        
        testStaff = TestDataBuilder.createStaff();
        testCustomer = TestDataBuilder.createActiveCustomer();
        testBooking = TestDataBuilder.createCompletedBooking(testStaff, testCustomer);
    }

    @Test
    @DisplayName("测试服务完成后立即返回响应不阻塞 - 异步处理")
    void testAsyncSettlementReturnsImmediately() throws Exception {
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> {
            return invocation.getArgument(0);
        });
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            return invocation.getArgument(0);
        });

        long startTime = System.currentTimeMillis();
        CompletableFuture<Settlement> future = asyncSettlementService.processSettlementAsync(
            testBooking.getBookingId(),
            "settlement_test_001",
            testBooking
        );
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(elapsedTime < 100, "异步结算应该立即返回，不阻塞主线程");
        assertNotNull(future, "应该返回CompletableFuture");
    }

    @Test
    @DisplayName("测试后台Worker执行费用结算与支付处理")
    void testBackgroundWorkerExecutesSettlement() throws Exception {
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> {
            Thread.sleep(10);
            return invocation.getArgument(0);
        });
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);
        when(bookingRepository.findByBookingId(anyString())).thenReturn(Optional.of(testBooking));

        CompletableFuture<Settlement> future = asyncSettlementService.processSettlementAsync(
            testBooking.getBookingId(),
            "settlement_test_001",
            testBooking
        );

        Settlement settlement = future.get();
        
        assertNotNull(settlement);
        assertEquals("settlement_test_001", settlement.getSettlementId());
        assertEquals(testBooking.getBookingId(), settlement.getBookingId());
        assertEquals(testStaff.getStaffId(), settlement.getStaffId());
        assertEquals(SettlementStatus.PAID, settlement.getSettlementStatus());
        assertEquals(1, asyncSettlementService.getSuccessTasks());
    }

    @Test
    @DisplayName("测试结算金额计算的正确性 - 平台抽成10%")
    void testSettlementAmountCalculation() {
        double serviceAmount = 200.0;
        double expectedPlatformFee = 20.0;
        double expectedStaffAmount = 180.0;

        assertEquals(0.10, asyncSettlementService.getPlatformFeeRate(), "平台抽成率应为10%");
        assertEquals(serviceAmount * 0.10, expectedPlatformFee, "平台抽成应为20元");
        assertEquals(serviceAmount - expectedPlatformFee, expectedStaffAmount, "人员收入应为180元");
    }

    @Test
    @DisplayName("测试最大重试次数配置")
    void testMaxRetryConfiguration() {
        assertEquals(3, asyncSettlementService.getMaxRetries(), "最大重试次数应为3次");
    }

    @Test
    @DisplayName("测试结算失败时的重试机制 - 首次失败后重试成功")
    void testRetryMechanismFirstFailThenSuccess() throws Exception {
        final int[] callCount = {0};
        
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                throw new RuntimeException("Payment service temporarily unavailable");
            }
            Thread.sleep(10);
            return invocation.getArgument(0);
        });
        when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

        CompletableFuture<Settlement> future = asyncSettlementService.processSettlementAsync(
            testBooking.getBookingId(),
            "settlement_test_001",
            testBooking
        );

        Settlement settlement = future.get();
        
        assertEquals(SettlementStatus.PAID, settlement.getSettlementStatus());
        assertEquals(1, asyncSettlementService.getSuccessTasks());
        assertEquals(2, callCount[0], "应该调用了2次（1次失败+1次成功）");
    }

    @Test
    @DisplayName("测试结算失败时的重试机制 - 三次都失败")
    void testRetryMechanismAllThreeFail() throws Exception {
        when(settlementRepository.save(any(Settlement.class))).thenThrow(new RuntimeException("Payment service permanently down"));

        CompletableFuture<Settlement> future = asyncSettlementService.processSettlementAsync(
            testBooking.getBookingId(),
            "settlement_test_001",
            testBooking
        );

        Settlement settlement = future.get();
        
        assertEquals(SettlementStatus.FAILED, settlement.getSettlementStatus());
        assertEquals(1, asyncSettlementService.getFailedTasks());
        
        AsyncSettlementService.SettlementTask task = asyncSettlementService.getSettlementTask("settlement_test_001");
        assertNotNull(task);
        assertEquals(3, task.getRetryCount(), "应该重试了3次");
        assertTrue(task.getErrorMessage().contains("Payment service permanently down"));
    }

    @Test
    @DisplayName("测试成功结算后更新相关数据")
    void testSuccessfulSettlementUpdatesData() throws Exception {
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(invocation -> {
            return invocation.getArgument(0);
        });
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking b = invocation.getArgument(0);
            assertTrue(b.getIsSettled(), "预订应该被标记为已结算");
            return b;
        });

        CompletableFuture<Settlement> future = asyncSettlementService.processSettlementAsync(
            testBooking.getBookingId(),
            "settlement_test_001",
            testBooking
        );

        Settlement settlement = future.get();
        
        verify(staffService, times(1)).addStaffIncome(eq(testStaff.getStaffId()), anyDouble());
        verify(analyticsService, times(1)).addToTotalRevenue(anyDouble());
        verify(serviceHistoryService, times(1)).recordSettlementHistory(
            eq("PROCESS"),
            anyString(),
            eq(testBooking.getBookingId()),
            eq(testStaff.getStaffId()),
            eq(testCustomer.getCustomerId())
        );
    }

    @Test
    @DisplayName("测试结算任务信息记录")
    void testSettlementTaskInformation() {
        String settlementId = "settlement_test_001";
        AsyncSettlementService.SettlementTask task = new AsyncSettlementService.SettlementTask(
            settlementId,
            testBooking.getBookingId(),
            testStaff.getStaffId(),
            200.0,
            20.0,
            180.0
        );

        assertEquals(settlementId, task.getSettlementId());
        assertEquals(testBooking.getBookingId(), task.getBookingId());
        assertEquals(testStaff.getStaffId(), task.getStaffId());
        assertEquals(200.0, task.getServiceAmount());
        assertEquals(20.0, task.getPlatformFee());
        assertEquals(180.0, task.getStaffAmount());
        assertEquals(0, task.getRetryCount());
        assertEquals(SettlementStatus.PENDING, task.getStatus());
        assertFalse(task.canRetry());
        
        task.incrementRetryCount();
        task.incrementRetryCount();
        assertTrue(task.canRetry(), "重试2次后还可以重试1次");
        
        task.incrementRetryCount();
        assertFalse(task.canRetry(), "重试3次后不应再重试");
    }

    @Test
    @DisplayName("测试计数器管理")
    void testCounterManagement() {
        assertEquals(0, asyncSettlementService.getTotalTasks());
        assertEquals(0, asyncSettlementService.getSuccessTasks());
        assertEquals(0, asyncSettlementService.getFailedTasks());

        asyncSettlementService.resetCounters();

        assertEquals(0, asyncSettlementService.getTotalTasks());
        assertEquals(0, asyncSettlementService.getSuccessTasks());
        assertEquals(0, asyncSettlementService.getFailedTasks());
    }

    @Test
    @DisplayName("测试获取不存在的结算任务返回null")
    void testGetNonExistentSettlementTask() {
        AsyncSettlementService.SettlementTask task = asyncSettlementService.getSettlementTask("non_existent");
        assertNull(task);
    }
}
