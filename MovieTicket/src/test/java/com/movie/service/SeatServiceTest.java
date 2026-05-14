package com.movie.service;

import com.movie.builder.TestDataBuilder;
import com.movie.entity.Seat;
import com.movie.entity.User;
import com.movie.exception.MovieException;
import com.movie.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
@DisplayName("座位模块单元测试")
public class SeatServiceTest {

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private SeatService seatService;

    private Seat availableSeat;
    private Seat lockedSeat;
    private Seat soldSeat;
    private User normalUser;
    private User vipUser;

    @BeforeEach
    void setUp() {
        availableSeat = TestDataBuilder.buildSeat();
        availableSeat.setSeatStatus(SeatService.STATUS_AVAILABLE);

        lockedSeat = TestDataBuilder.buildSeatLocked("schedule_001", 1, 2, "user_001");
        soldSeat = TestDataBuilder.buildSeatSold("schedule_001", 1, 3, "ticket_001");

        normalUser = TestDataBuilder.buildUser();
        vipUser = TestDataBuilder.buildVipUser();
    }

    @Nested
    @DisplayName("座位锁定机制测试")
    class SeatLockingTests {

        @Test
        @DisplayName("验证座位选择前获取分布式锁的正确性")
        void testAcquireDistributedLock() {
            String seatId = "seat_001";
            
            boolean lockAcquired = seatService.acquireDistributedLock(seatId, normalUser);
            
            assertTrue(lockAcquired, "应该能成功获取锁");
            
            seatService.releaseDistributedLock(seatId);
        }

        @Test
        @DisplayName("验证空闲座位可以成功锁定")
        void testLockAvailableSeat() {
            List<String> seatIds = Arrays.asList(availableSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(availableSeat));

            assertDoesNotThrow(() -> seatService.lockSeats(seatIds, "user_001"));

            assertEquals(SeatService.STATUS_LOCKED, availableSeat.getSeatStatus());
            verify(seatRepository, times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("验证已锁定座位无法再次锁定 - 锁冲突处理")
        void testLockConflictThrowsException() {
            List<String> seatIds = Arrays.asList(lockedSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(lockedSeat));

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.lockSeats(seatIds, "user_002"));

            assertTrue(exception.getMessage().contains("座位不可用") || 
                      exception.getMessage().contains("座位已锁定"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("并发选座时锁冲突处理 - 只有一个线程能获取锁")
        void testConcurrentSeatLocking() throws InterruptedException {
            int threadCount = 10;
            String seatId = "concurrent_seat_001";
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        boolean acquired = seatService.acquireDistributedLock(seatId, normalUser);
                        if (acquired) {
                            successCount.incrementAndGet();
                            Thread.sleep(50);
                            seatService.releaseDistributedLock(seatId);
                        }
                    } catch (Exception e) {
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(1, successCount.get(), "同一时间应该只有一个线程能获取锁");
        }
    }

    @Nested
    @DisplayName("用户等级锁定超时差异测试")
    class LockTimeoutTests {

        @Test
        @DisplayName("VIP用户锁定超时时间更短 - 120秒")
        void testVipUserLockTimeout() {
            int timeout = seatService.getLockTimeoutSeconds(vipUser);
            
            assertEquals(SeatService.LOCK_TIMEOUT_SECONDS_VIP, timeout);
            assertEquals(120, timeout);
        }

        @Test
        @DisplayName("普通用户锁定超时时间 - 300秒")
        void testNormalUserLockTimeout() {
            int timeout = seatService.getLockTimeoutSeconds(normalUser);
            
            assertEquals(SeatService.LOCK_TIMEOUT_SECONDS_NORMAL, timeout);
            assertEquals(300, timeout);
        }

        @Test
        @DisplayName("验证VIP和普通用户超时时间差异")
        void testLockTimeoutDifference() {
            int vipTimeout = seatService.getLockTimeoutSeconds(vipUser);
            int normalTimeout = seatService.getLockTimeoutSeconds(normalUser);
            
            assertTrue(vipTimeout < normalTimeout, "VIP超时应该更短");
            assertEquals(180, normalTimeout - vipTimeout);
        }

        @Test
        @DisplayName("验证锁定超时判断 - 未超时")
        void testLockNotExpired() {
            Seat seat = TestDataBuilder.buildSeatLocked("s_001", 1, 1, "user_001");
            seat.setLockTime(LocalDateTime.now().minusSeconds(60));
            
            boolean expired = seatService.isLockExpired(seat, normalUser);
            
            assertFalse(expired);
        }

        @Test
        @DisplayName("验证锁定超时判断 - 已超时")
        void testLockExpired() {
            Seat seat = TestDataBuilder.buildSeatLocked("s_001", 1, 1, "user_001");
            seat.setLockTime(LocalDateTime.now().minusSeconds(400));
            
            boolean expired = seatService.isLockExpired(seat, normalUser);
            
            assertTrue(expired);
        }

        @Test
        @DisplayName("VIP用户锁定在普通用户视角可能已超时")
        void testVipLockExpiresSooner() {
            Seat seat = TestDataBuilder.buildSeatLocked("s_001", 1, 1, vipUser.getUserId());
            seat.setLockTime(LocalDateTime.now().minusSeconds(150));
            
            boolean expiredForVip = seatService.isLockExpired(seat, vipUser);
            boolean expiredForNormal = seatService.isLockExpired(seat, normalUser);
            
            assertTrue(expiredForVip, "VIP锁定150秒后应超时");
            assertFalse(expiredForNormal, "普通用户视角150秒不应超时");
        }
    }

    @Nested
    @DisplayName("锁定释放与恢复测试")
    class LockReleaseTests {

        @Test
        @DisplayName("验证锁定座位可以成功释放")
        void testReleaseLock() {
            lockedSeat.setLockUserId("user_001");
            lockedSeat.setLockTime(LocalDateTime.now());
            List<String> seatIds = Arrays.asList(lockedSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(lockedSeat));

            seatService.releaseLock(seatIds);

            assertEquals(SeatService.STATUS_AVAILABLE, lockedSeat.getSeatStatus());
            assertNull(lockedSeat.getLockUserId());
            assertNull(lockedSeat.getLockTime());
            verify(seatRepository, times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("验证已售座位释放后恢复空闲状态")
        void testReleaseSoldSeat() {
            List<String> seatIds = Arrays.asList(soldSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(soldSeat));

            seatService.releaseSoldSeats(seatIds);

            assertEquals(SeatService.STATUS_AVAILABLE, soldSeat.getSeatStatus());
            assertNull(soldSeat.getTicketId());
            verify(seatRepository, times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("验证非锁定座位不会被错误修改")
        void testReleaseNonLockedSeat() {
            List<String> seatIds = Arrays.asList(availableSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(availableSeat));

            String originalStatus = availableSeat.getSeatStatus();
            seatService.releaseLock(seatIds);

            assertEquals(originalStatus, availableSeat.getSeatStatus());
            verify(seatRepository, times(1)).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("座位已售拒绝处理测试")
    class SoldSeatRejectionTests {

        @Test
        @DisplayName("验证已售座位锁定时被拒绝")
        void testLockSoldSeatThrowsException() {
            List<String> seatIds = Arrays.asList(soldSeat.getSeatId());
            when(seatRepository.findAllById(seatIds)).thenReturn(Arrays.asList(soldSeat));

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.lockSeats(seatIds, "user_001"));

            assertTrue(exception.getMessage().contains("已售") || 
                      exception.getMessage().contains("不可用"));
        }

        @Test
        @DisplayName("验证已售座位验证时抛出异常")
        void testValidateSoldSeatThrowsException() {
            List<Seat> seats = Arrays.asList(soldSeat);

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.validateSeatsAvailable(seats));

            assertTrue(exception.getMessage().contains("座位已售出"));
        }

        @Test
        @DisplayName("验证混合状态座位列表的验证")
        void testValidateMixedSeats() {
            Seat available = TestDataBuilder.buildSeat();
            available.setSeatStatus(SeatService.STATUS_AVAILABLE);
            Seat locked = TestDataBuilder.buildSeatLocked("s_001", 2, 1, "user_001");
            
            List<Seat> seats = Arrays.asList(available, locked);

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.validateSeatsAvailable(seats));

            assertTrue(exception.getMessage().contains("已锁定"));
        }
    }

    @Nested
    @DisplayName("座位状态流转生命周期测试")
    class SeatStateTransitionTests {

        @Test
        @DisplayName("验证完整生命周期: 空闲 -> 锁定 -> 已选 -> 已售")
        void testFullSeatLifecycle() {
            String seatId = "lifecycle_seat_001";
            String userId = "user_001";
            String ticketId = "ticket_001";
            
            Seat seat = TestDataBuilder.buildSeatWithId(seatId, "schedule_001", 1, 1);
            seat.setSeatStatus(SeatService.STATUS_AVAILABLE);

            when(seatRepository.findBySeatId(seatId)).thenReturn(Optional.of(seat));
            when(seatRepository.save(any(Seat.class))).thenReturn(seat);

            assertEquals(SeatService.STATUS_AVAILABLE, seat.getSeatStatus(), "初始状态应为空闲");

            seatService.transitionSeatToLocked(seatId, userId);
            assertEquals(SeatService.STATUS_LOCKED, seat.getSeatStatus(), "锁定后应为锁定状态");
            assertEquals(userId, seat.getLockUserId());
            assertNotNull(seat.getLockTime());

            seatService.transitionSeatToSelected(seatId);
            assertEquals(SeatService.STATUS_SELECTED, seat.getSeatStatus(), "选择后应为已选状态");

            seatService.transitionSeatToSold(seatId, ticketId);
            assertEquals(SeatService.STATUS_SOLD, seat.getSeatStatus(), "售出后应为已售状态");
            assertEquals(ticketId, seat.getTicketId());
            assertNull(seat.getLockUserId());
        }

        @Test
        @DisplayName("验证状态流转约束: 非空闲座位不能直接锁定")
        void testTransitionToLockedFromInvalidState() {
            String seatId = "invalid_seat_001";
            Seat seat = TestDataBuilder.buildSeatWithId(seatId, "schedule_001", 1, 1);
            seat.setSeatStatus(SeatService.STATUS_SOLD);

            when(seatRepository.findBySeatId(seatId)).thenReturn(Optional.of(seat));

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.transitionSeatToLocked(seatId, "user_001"));

            assertTrue(exception.getMessage().contains("空闲状态"));
        }

        @Test
        @DisplayName("验证状态流转约束: 非锁定座位不能直接选择")
        void testTransitionToSelectedFromInvalidState() {
            String seatId = "invalid_seat_002";
            Seat seat = TestDataBuilder.buildSeatWithId(seatId, "schedule_001", 1, 1);
            seat.setSeatStatus(SeatService.STATUS_AVAILABLE);

            when(seatRepository.findBySeatId(seatId)).thenReturn(Optional.of(seat));

            MovieException exception = assertThrows(MovieException.class,
                    () -> seatService.transitionSeatToSelected(seatId));

            assertTrue(exception.getMessage().contains("锁定状态"));
        }

        @Test
        @DisplayName("验证取消后状态恢复: 已售 -> 空闲")
        void testTransitionFromSoldToAvailable() {
            String seatId = "cancel_seat_001";
            Seat seat = TestDataBuilder.buildSeatSold("schedule_001", 1, 1, "ticket_001");
            seat.setSeatId(seatId);

            when(seatRepository.findBySeatId(seatId)).thenReturn(Optional.of(seat));
            when(seatRepository.save(any(Seat.class))).thenReturn(seat);

            seatService.transitionSeatToAvailable(seatId);

            assertEquals(SeatService.STATUS_AVAILABLE, seat.getSeatStatus());
            assertNull(seat.getTicketId());
            assertNull(seat.getLockUserId());
        }
    }

    @Nested
    @DisplayName("座位费用计算测试")
    class SeatPriceCalculationTests {

        @Test
        @DisplayName("验证单座位价格计算")
        void testSingleSeatPrice() {
            Seat seat = TestDataBuilder.buildSeatWithPrice("s_001", 1, 1, new BigDecimal("50.00"));
            List<Seat> seats = Arrays.asList(seat);

            BigDecimal total = seatService.calculateTotalPrice(seats);

            assertEquals(new BigDecimal("50.00"), total);
        }

        @Test
        @DisplayName("验证多座位总价格计算")
        void testMultipleSeatsPrice() {
            Seat seat1 = TestDataBuilder.buildSeatWithPrice("s_001", 1, 1, new BigDecimal("50.00"));
            Seat seat2 = TestDataBuilder.buildSeatWithPrice("s_002", 1, 2, new BigDecimal("60.00"));
            Seat seat3 = TestDataBuilder.buildSeatWithPrice("s_003", 1, 3, new BigDecimal("55.00"));
            List<Seat> seats = Arrays.asList(seat1, seat2, seat3);

            BigDecimal total = seatService.calculateTotalPrice(seats);

            assertEquals(new BigDecimal("165.00"), total);
        }

        @Test
        @DisplayName("验证空座位列表价格为零")
        void testEmptySeatsPrice() {
            BigDecimal total = seatService.calculateTotalPrice(Arrays.asList());

            assertEquals(BigDecimal.ZERO, total);
        }

        @Test
        @DisplayName("验证空价格座位处理")
        void testNullPriceSeat() {
            Seat seat = TestDataBuilder.buildSeat();
            seat.setSeatPrice(null);
            List<Seat> seats = Arrays.asList(seat);

            BigDecimal total = seatService.calculateTotalPrice(seats);

            assertEquals(BigDecimal.ZERO, total);
        }
    }

    @Test
    @DisplayName("验证座位初始化")
    void testInitializeSeats() {
        String scheduleId = "init_schedule_001";
        int rowCount = 3;
        int colCount = 4;
        BigDecimal price = new BigDecimal("45.00");

        when(seatRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Seat> seats = seatService.initializeSeats(scheduleId, rowCount, colCount, price);

        assertEquals(rowCount * colCount, seats.size());
        
        for (Seat seat : seats) {
            assertEquals(scheduleId, seat.getScheduleId());
            assertEquals(SeatService.STATUS_AVAILABLE, seat.getSeatStatus());
            assertEquals(price, seat.getSeatPrice());
            assertNotNull(seat.getSeatNumber());
            assertTrue(seat.getSeatNumber().contains("-"));
        }
    }
}
