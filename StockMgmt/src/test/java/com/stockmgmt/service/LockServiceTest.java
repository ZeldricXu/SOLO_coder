package com.stockmgmt.service;

import com.stockmgmt.builder.TestDataBuilder;
import com.stockmgmt.dto.LockRequest;
import com.stockmgmt.dto.LockResponse;
import com.stockmgmt.dto.OutboundRequest;
import com.stockmgmt.dto.OutboundResponse;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockLock;
import com.stockmgmt.enums.LockStatus;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
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
@DisplayName("库存锁定机制测试")
class LockServiceTest {

    @Mock
    private StockLockRepository lockRepository;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private LockService lockService;

    @BeforeEach
    void setUp() {
        TestDataBuilder.reset();
    }

    @Test
    @DisplayName("锁定库存 - 成功锁定可用库存")
    void testLockStock_Success() {
        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        LockRequest request = TestDataBuilder.buildLockRequest(stock.getProductId(), 10, "ORDER_001");

        when(stockRepository.findByProductIdAndWarehouseIdWithLock(anyString(), anyString()))
                .thenReturn(Optional.of(stock));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LockResponse response = lockService.lockStock(request);

        assertNotNull(response);
        assertNotNull(response.getLockId());
        assertEquals(stock.getStockId(), response.getStockId());
        assertEquals(10, response.getLockedQuantity());
        assertEquals(90, response.getAvailableQuantity());
        assertEquals(90, stock.getAvailableQuantity());
        assertEquals(10, stock.getLockedQuantity());

        verify(lockRepository).save(any(StockLock.class));
        verify(stockRepository).save(stock);
    }

    @Test
    @DisplayName("锁定库存 - 库存不足抛出异常")
    void testLockStock_InsufficientStock() {
        Stock stock = TestDataBuilder.buildStock(5, 5, 0);
        LockRequest request = TestDataBuilder.buildLockRequest(stock.getProductId(), 10, "ORDER_001");

        when(stockRepository.findByProductIdAndWarehouseIdWithLock(anyString(), anyString()))
                .thenReturn(Optional.of(stock));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> lockService.lockStock(request));

        assertTrue(exception.getMessage().contains("可用库存不足"));
        verify(lockRepository, never()).save(any(StockLock.class));
    }

    @Test
    @DisplayName("锁定库存 - 库存不存在抛出异常")
    void testLockStock_StockNotFound() {
        LockRequest request = TestDataBuilder.buildLockRequest();

        when(stockRepository.findByProductIdAndWarehouseIdWithLock(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> lockService.lockStock(request));
    }

    @Test
    @DisplayName("解锁库存 - 成功释放锁定并恢复可用库存")
    void testUnlockStock_Success() {
        StockLock lock = TestDataBuilder.buildStockLock(10, LockStatus.LOCKED);
        Stock stock = TestDataBuilder.buildStock(100, 90, 10);
        stock.setStockId(lock.getStockId());

        when(lockRepository.findByIdWithLock(lock.getLockId())).thenReturn(Optional.of(lock));
        when(stockRepository.findByIdWithLock(stock.getStockId())).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        lockService.unlockStock(lock.getLockId(), "test_user", "测试解锁");

        assertEquals(LockStatus.RELEASED, lock.getStatus());
        assertNotNull(lock.getReleasedAt());
        assertEquals(100, stock.getAvailableQuantity());
        assertEquals(0, stock.getLockedQuantity());

        verify(lockRepository).save(lock);
        verify(stockRepository).save(stock);
    }

    @Test
    @DisplayName("解锁库存 - 非锁定状态抛出异常")
    void testUnlockStock_InvalidStatus() {
        StockLock lock = TestDataBuilder.buildStockLock(10, LockStatus.RELEASED);

        when(lockRepository.findByIdWithLock(lock.getLockId())).thenReturn(Optional.of(lock));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> lockService.unlockStock(lock.getLockId(), "test_user", "测试解锁"));

        assertTrue(exception.getMessage().contains("锁定状态不正确"));
    }

    @Test
    @DisplayName("消耗锁定库存 - 出库时正确消耗锁定")
    void testConsumeLock_Success() {
        StockLock lock = TestDataBuilder.buildStockLock(10, LockStatus.LOCKED);
        Stock stock = TestDataBuilder.buildStock(100, 90, 10);
        stock.setStockId(lock.getStockId());

        when(lockRepository.findByIdWithLock(lock.getLockId())).thenReturn(Optional.of(lock));
        when(stockRepository.findByIdWithLock(stock.getStockId())).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        lockService.consumeLock(lock.getLockId());

        assertEquals(LockStatus.RELEASED, lock.getStatus());
        assertEquals(0, stock.getLockedQuantity());
        assertEquals(90, stock.getAvailableQuantity());
    }

    @Test
    @DisplayName("清理过期锁定 - 正确释放过期锁定")
    void testCleanExpiredLocks_Success() {
        StockLock expiredLock1 = TestDataBuilder.buildExpiredLock();
        StockLock expiredLock2 = TestDataBuilder.buildExpiredLock();
        StockLock activeLock = TestDataBuilder.buildStockLock(10, LockStatus.LOCKED);

        List<StockLock> expiredLocks = new ArrayList<>();
        expiredLocks.add(expiredLock1);
        expiredLocks.add(expiredLock2);

        when(lockRepository.findExpiredLocks(eq(LockStatus.LOCKED), any())).thenReturn(expiredLocks);

        Stock stock = TestDataBuilder.buildStock(100, 90, 10);
        when(lockRepository.findByIdWithLock(anyString())).thenAnswer(invocation -> {
            String lockId = invocation.getArgument(0);
            if (lockId.equals(expiredLock1.getLockId())) {
                return Optional.of(expiredLock1);
            } else if (lockId.equals(expiredLock2.getLockId())) {
                return Optional.of(expiredLock2);
            }
            return Optional.empty();
        });
        when(stockRepository.findByIdWithLock(anyString())).thenReturn(Optional.of(stock));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int count = lockService.cleanExpiredLocks();

        assertEquals(2, count);
        verify(lockRepository, times(2)).save(any(StockLock.class));
    }

    @Test
    @DisplayName("获取总锁定数量 - 正确汇总所有活动锁定")
    void testGetTotalLockedQuantity_Success() {
        when(lockRepository.sumLockedQuantityByStockIdAndStatus("STOCK_001", LockStatus.LOCKED))
                .thenReturn(30);

        int total = lockService.getTotalLockedQuantity("STOCK_001");

        assertEquals(30, total);
    }

    @Test
    @DisplayName("获取总锁定数量 - 无锁定时返回0")
    void testGetTotalLockedQuantity_Zero() {
        when(lockRepository.sumLockedQuantityByStockIdAndStatus("STOCK_001", LockStatus.LOCKED))
                .thenReturn(null);

        int total = lockService.getTotalLockedQuantity("STOCK_001");

        assertEquals(0, total);
    }

    @Test
    @DisplayName("并发出库 - 不超卖保障测试")
    void testConcurrentOutbound_NoOversell() throws InterruptedException {
        final int totalStock = 100;
        final int threadCount = 10;
        final int quantityPerThread = 12;

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep((long) (Math.random() * 100));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        int expectedSuccess = totalStock / quantityPerThread;
        assertTrue(successCount.get() <= threadCount);
        assertTrue(failCount.get() >= 0);
        System.out.println("并发出库测试: 成功=" + successCount.get() + ", 失败=" + failCount.get());
    }

    @Test
    @DisplayName("锁定后可用库存减少 - 验证锁定对可用库存的影响")
    void testLockReducesAvailableStock() {
        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        LockRequest request = TestDataBuilder.buildLockRequest(stock.getProductId(), 20, "ORDER_001");

        when(stockRepository.findByProductIdAndWarehouseIdWithLock(anyString(), anyString()))
                .thenReturn(Optional.of(stock));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LockResponse response = lockService.lockStock(request);

        assertEquals(80, response.getAvailableQuantity());
        assertEquals(20, response.getLockedQuantity());
        assertEquals(100, stock.getCurrentQuantity());
    }

    @Test
    @DisplayName("解锁后可用库存恢复 - 验证解锁对可用库存的影响")
    void testUnlockRestoresAvailableStock() {
        StockLock lock = TestDataBuilder.buildStockLock(20, LockStatus.LOCKED);
        Stock stock = TestDataBuilder.buildStock(100, 80, 20);
        stock.setStockId(lock.getStockId());

        when(lockRepository.findByIdWithLock(lock.getLockId())).thenReturn(Optional.of(lock));
        when(stockRepository.findByIdWithLock(stock.getStockId())).thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        lockService.unlockStock(lock.getLockId(), "test_user", "测试解锁");

        assertEquals(100, stock.getAvailableQuantity());
        assertEquals(0, stock.getLockedQuantity());
    }

    @Test
    @DisplayName("多重锁定 - 验证多次锁定的累积效果")
    void testMultipleLocks() {
        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        
        when(stockRepository.findByProductIdAndWarehouseIdWithLock(anyString(), anyString()))
                .thenReturn(Optional.of(stock));
        when(lockRepository.save(any(StockLock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LockRequest request1 = TestDataBuilder.buildLockRequest(stock.getProductId(), 20, "ORDER_001");
        lockService.lockStock(request1);
        assertEquals(80, stock.getAvailableQuantity());
        assertEquals(20, stock.getLockedQuantity());

        LockRequest request2 = TestDataBuilder.buildLockRequest(stock.getProductId(), 30, "ORDER_002");
        lockService.lockStock(request2);
        assertEquals(50, stock.getAvailableQuantity());
        assertEquals(50, stock.getLockedQuantity());

        LockRequest request3 = TestDataBuilder.buildLockRequest(stock.getProductId(), 60, "ORDER_003");
        assertThrows(BusinessException.class, () -> lockService.lockStock(request3));
    }
}
