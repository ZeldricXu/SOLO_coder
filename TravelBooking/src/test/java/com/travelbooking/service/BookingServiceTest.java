package com.travelbooking.service;

import com.travelbooking.builder.TestDataBuilder;
import com.travelbooking.dto.CreateBookingRequest;
import com.travelbooking.dto.CreateBookingResponse;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.*;
import com.travelbooking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RouteService routeService;

    @Mock
    private TouristService touristService;

    @Mock
    private TeamService teamService;

    @Mock
    private ItineraryService itineraryService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private HistoryService historyService;

    @Mock
    private DistributedLockService lockService;

    @InjectMocks
    private BookingService bookingService;

    private Route availableRoute;
    private Tourist tourist;
    private Team availableTeam;
    private Booking savedBooking;
    private CreateBookingRequest validRequest;

    @BeforeEach
    void setUp() {
        availableRoute = TestDataBuilder.buildDomesticRoute();
        tourist = TestDataBuilder.buildTourist();
        availableTeam = TestDataBuilder.buildAvailableTeam();
        savedBooking = TestDataBuilder.buildConfirmedBooking();
        validRequest = TestDataBuilder.buildValidBookingRequest();
    }

    @Test
    @DisplayName("测试预订前获取分布式锁的正确性")
    void testAcquireDistributedLockBeforeBooking() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(eq("route_test_001"), anyString(), eq(DistributedLockService.BookingUrgency.NORMAL)))
                .thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        CreateBookingResponse response = bookingService.createBooking(validRequest);

        assertNotNull(response);
        assertEquals("booking_test_001", response.getBookingId());
        assertEquals("confirmed", response.getStatus());

        verify(lockService, times(1)).acquireLock(
                eq("route_test_001"),
                anyString(),
                eq(DistributedLockService.BookingUrgency.NORMAL)
        );
        verify(lockService, times(1)).releaseLock(eq("route_test_001"), anyString());
        verify(routeService, times(1)).decreaseQuota("route_test_001", 2);
    }

    @Test
    @DisplayName("测试获取锁失败时拒绝预订")
    void testBookingFailsWhenLockNotAcquired() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(lockService.acquireLock(anyString(), anyString(), any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            bookingService.createBooking(validRequest);
        });

        assertEquals(409, exception.getCode());
        assertTrue(exception.getMessage().contains("获取锁失败"));

        verify(bookingRepository, never()).save(any(Booking.class));
        verify(routeService, never()).decreaseQuota(anyString(), anyInt());
    }

    @Test
    @DisplayName("测试紧急预订使用短超时")
    void testEmergencyBookingUsesShortTimeout() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(anyString(), anyString(), eq(DistributedLockService.BookingUrgency.EMERGENCY)))
                .thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        CreateBookingRequest emergencyRequest = TestDataBuilder.buildEmergencyBookingRequest();

        CreateBookingResponse response = bookingService.createBooking(
                emergencyRequest,
                DistributedLockService.BookingUrgency.EMERGENCY
        );

        assertNotNull(response);

        verify(lockService).acquireLock(
                anyString(),
                anyString(),
                eq(DistributedLockService.BookingUrgency.EMERGENCY)
        );

        assertEquals(5, DistributedLockService.BookingUrgency.EMERGENCY.getTimeout());
        assertEquals(30, DistributedLockService.BookingUrgency.NORMAL.getTimeout());
    }

    @Test
    @DisplayName("测试普通预订使用长超时")
    void testNormalBookingUsesLongTimeout() {
        assertEquals(30, DistributedLockService.BookingUrgency.NORMAL.getTimeout());
        assertEquals(TimeUnit.SECONDS, DistributedLockService.BookingUrgency.NORMAL.getUnit());
        assertEquals(5, DistributedLockService.BookingUrgency.EMERGENCY.getTimeout());

        assertTrue(DistributedLockService.BookingUrgency.NORMAL.getTimeout() >
                DistributedLockService.BookingUrgency.EMERGENCY.getTimeout());
    }

    @Test
    @DisplayName("测试并发预订时锁冲突处理")
    void testConcurrentBookingLockConflict() throws InterruptedException {
        DistributedLockService realLockService = new DistributedLockService();
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean acquired = realLockService.acquireLock(
                            "concurrent_route",
                            "booking_" + index,
                            DistributedLockService.BookingUrgency.EMERGENCY
                    );
                    if (acquired) {
                        successCount.incrementAndGet();
                        Thread.sleep(100);
                        realLockService.releaseLock("concurrent_route", "booking_" + index);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, successCount.get() + failCount.get());
        assertTrue(successCount.get() > 0);
    }

    @Test
    @DisplayName("测试名额扣减的正确性")
    void testQuotaDecreaseCorrectness() {
        Route route = TestDataBuilder.buildRouteWithQuota(50, 30);
        when(routeService.getRouteById("route_quota_50_30")).thenReturn(Optional.of(route));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(anyString(), anyString(), any())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        CreateBookingRequest request = TestDataBuilder.buildBookingRequest(
                "route_quota_50_30",
                "测试游客",
                5
        );

        CreateBookingResponse response = bookingService.createBooking(request);

        assertNotNull(response);
        verify(routeService).decreaseQuota("route_quota_50_30", 5);
    }

    @Test
    @DisplayName("测试取消预订后名额恢复")
    void testQuotaRestoreAfterCancellation() {
        Booking bookingToCancel = TestDataBuilder.buildBooking(
                "booking_cancel_001",
                "route_restore_001",
                "tourist_001",
                3,
                "confirmed"
        );

        Route route = TestDataBuilder.buildRouteWithQuota(50, 20);

        when(bookingRepository.findById("booking_cancel_001")).thenReturn(Optional.of(bookingToCancel));
        when(routeService.getRouteById("route_restore_001")).thenReturn(Optional.of(route));
        when(routeService.updateRoute(eq("route_restore_001"), any(Route.class))).thenAnswer(invocation -> {
            Route updated = invocation.getArgument(1);
            assertEquals(23, updated.getRouteAvailable());
            assertEquals("available", updated.getRouteStatus());
            return updated;
        });

        boolean result = bookingService.cancelBookingAndRestoreQuota("booking_cancel_001");

        assertTrue(result);

        verify(bookingRepository).save(argThat(b -> "cancelled".equals(b.getBookingStatus())));
        verify(routeService).updateRoute(eq("route_restore_001"), any(Route.class));
    }

    @Test
    @DisplayName("测试名额已满时拒绝预订")
    void testBookingRejectedWhenFull() {
        Route fullRoute = TestDataBuilder.buildFullRoute();
        when(routeService.getRouteById("route_test_003")).thenReturn(Optional.of(fullRoute));

        CreateBookingRequest request = TestDataBuilder.buildBookingRequest(
                "route_test_003",
                "测试游客",
                1
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            bookingService.createBooking(request);
        });

        assertEquals(400, exception.getCode());
        assertEquals("名额已满", exception.getMessage());
        verify(lockService, never()).acquireLock(anyString(), anyString(), any());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("测试线路已关闭时拒绝预订")
    void testBookingRejectedWhenClosed() {
        Route closedRoute = TestDataBuilder.buildClosedRoute();
        when(routeService.getRouteById("route_test_004")).thenReturn(Optional.of(closedRoute));

        CreateBookingRequest request = TestDataBuilder.buildBookingRequest(
                "route_test_004",
                "测试游客",
                1
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            bookingService.createBooking(request);
        });

        assertEquals(400, exception.getCode());
        assertEquals("线路已关闭", exception.getMessage());
        verify(lockService, never()).acquireLock(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("测试线路不存在时拒绝预订")
    void testBookingRejectedWhenRouteNotFound() {
        when(routeService.getRouteById("nonexistent_route")).thenReturn(Optional.empty());

        CreateBookingRequest request = TestDataBuilder.buildBookingRequest(
                "nonexistent_route",
                "测试游客",
                1
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            bookingService.createBooking(request);
        });

        assertEquals(404, exception.getCode());
        assertEquals("线路不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试预订金额计算正确性")
    void testBookingAmountCalculation() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(anyString(), anyString(), any())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking b = invocation.getArgument(0);
            assertEquals(new BigDecimal("6000.00"), b.getBookingAmount());
            return savedBooking;
        });
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        CreateBookingRequest request = TestDataBuilder.buildBookingRequest(
                "route_test_001",
                "测试游客",
                2
        );

        bookingService.createBooking(request);

        verify(bookingRepository).save(argThat(b ->
                b.getBookingAmount().equals(new BigDecimal("6000.00"))
        ));
    }

    @Test
    @DisplayName("测试预订成功后更新统计")
    void testAnalyticsUpdatedAfterSuccessfulBooking() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(anyString(), anyString(), any())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        bookingService.createBooking(validRequest);

        verify(analyticsService).updateBookingStatistics(
                eq(new BigDecimal("6000.00")),
                eq(2)
        );
    }

    @Test
    @DisplayName("测试预订成功后记录历史")
    void testHistoryRecordedAfterSuccessfulBooking() {
        when(routeService.getRouteById("route_test_001")).thenReturn(Optional.of(availableRoute));
        when(touristService.findOrCreateTourist(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tourist);
        when(lockService.acquireLock(anyString(), anyString(), any())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(teamService.assignTeam()).thenReturn(availableTeam);
        when(itineraryService.createItinerary(any(Booking.class), any(Route.class), any(Team.class)))
                .thenReturn(TestDataBuilder.buildPendingItinerary());

        bookingService.createBooking(validRequest);

        verify(historyService).recordHistory(
                eq("booking"),
                eq("booking_test_001"),
                eq("create"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试重复取消预订抛出异常")
    void testDoubleCancellationThrowsException() {
        Booking alreadyCancelled = TestDataBuilder.buildBooking(
                "booking_cancel_002",
                "route_001",
                "tourist_001",
                2,
                "cancelled"
        );

        when(bookingRepository.findById("booking_cancel_002")).thenReturn(Optional.of(alreadyCancelled));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            bookingService.cancelBookingAndRestoreQuota("booking_cancel_002");
        });

        assertEquals(400, exception.getCode());
        assertEquals("预订已取消", exception.getMessage());
    }

    @Test
    @DisplayName("测试已满线路恢复名额后状态变为可用")
    void testFullRouteBecomesAvailableAfterRestoration() {
        Booking booking = TestDataBuilder.buildBooking(
                "booking_restore_001",
                "route_full_001",
                "tourist_001",
                5,
                "confirmed"
        );

        Route fullRoute = new Route();
        fullRoute.setRouteId("route_full_001");
        fullRoute.setRouteQuota(10);
        fullRoute.setRouteAvailable(0);
        fullRoute.setRouteStatus("full");

        when(bookingRepository.findById("booking_restore_001")).thenReturn(Optional.of(booking));
        when(routeService.getRouteById("route_full_001")).thenReturn(Optional.of(fullRoute));
        when(routeService.updateRoute(eq("route_full_001"), any(Route.class))).thenAnswer(invocation -> {
            Route updated = invocation.getArgument(1);
            assertEquals(5, updated.getRouteAvailable());
            assertEquals("available", updated.getRouteStatus());
            return updated;
        });

        bookingService.cancelBookingAndRestoreQuota("booking_restore_001");

        verify(routeService).updateRoute(eq("route_full_001"), any(Route.class));
    }
}
