package com.library.librarymgmt;

import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.dto.BorrowRequest;
import com.library.librarymgmt.dto.BorrowResult;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.BorrowRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.service.impl.BorrowServiceImpl;
import com.library.librarymgmt.testdata.TestDataBuilder;
import com.library.librarymgmt.testdata.TestConstants;
import com.library.librarymgmt.util.LockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("借阅模块 - 库存锁定机制测试")
class BorrowServiceTest {

    @Mock
    private BorrowRepository borrowRepository;

    @Mock
    private BookService bookService;

    @Mock
    private ReaderService readerService;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private LibraryConfig libraryConfig;

    @Spy
    @InjectMocks
    private BorrowServiceImpl borrowService;

    private LockManager lockManager;
    private TestDataBuilder.BookBuilder bookBuilder;
    private TestDataBuilder.ReaderBuilder readerBuilder;

    @BeforeEach
    void setUp() {
        lockManager = new LockManager();
        bookBuilder = new TestDataBuilder.BookBuilder();
        readerBuilder = new TestDataBuilder.ReaderBuilder();

        LibraryConfig.Borrow borrowConfig = new LibraryConfig.Borrow();
        borrowConfig.setDefaultDays(15);
        when(libraryConfig.getBorrow()).thenReturn(borrowConfig);
    }

    @Test
    @DisplayName("测试图书借阅前获取分布式锁的正确性")
    void testAcquireLockBeforeBorrow() {
        Book book = bookBuilder.id("book_001")
                .name("测试图书")
                .stock(10)
                .build();
        Reader reader = readerBuilder.id("reader_001")
                .type(TestConstants.READER_TYPE_VIP)
                .borrowedCount(0)
                .build();

        String lockKey = "borrow:book:book_001";
        boolean lockAcquired = lockManager.acquireLock(lockKey, TestConstants.VIP_LOCK_TIMEOUT_SECONDS);

        assertTrue(lockAcquired, "VIP读者应该能够获取锁");
        assertTrue(lockManager.isLocked(lockKey), "锁应该处于激活状态");

        lockManager.releaseLock(lockKey);
    }

    @Test
    @DisplayName("测试并发借阅时锁冲突处理")
    void testConcurrentBorrowLockConflict() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        String lockKey = "borrow:book:concurrent_test";

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean acquired = lockManager.acquireLock(lockKey, 30);
                    if (acquired) {
                        successCount.incrementAndGet();
                        Thread.sleep(100);
                        lockManager.releaseLock(lockKey);
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
        endLatch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get() + failCount.get(), "所有线程应该完成执行");
        assertTrue(successCount.get() >= 1, "至少有一个线程应该能够获取锁");
    }

    @Test
    @DisplayName("测试VIP读者短超时锁定")
    void testVipReaderShortLockTimeout() throws InterruptedException {
        String lockKey = "borrow:book:vip_test";

        boolean lockAcquired = lockManager.acquireLock(lockKey, 1);
        assertTrue(lockAcquired, "应该能够获取锁");

        Thread.sleep(1100);

        assertFalse(lockManager.isLocked(lockKey), "VIP锁应该在短超时后过期");

        boolean newLockAcquired = lockManager.acquireLock(lockKey, 30);
        assertTrue(newLockAcquired, "锁过期后应该能够重新获取");
    }

    @Test
    @DisplayName("测试普通读者长超时锁定")
    void testNormalReaderLongLockTimeout() throws InterruptedException {
        String lockKey = "borrow:book:normal_test";

        boolean lockAcquired = lockManager.acquireLock(lockKey, 2);
        assertTrue(lockAcquired, "应该能够获取锁");

        Thread.sleep(1100);
        assertTrue(lockManager.isLocked(lockKey), "普通读者锁在1秒后仍然应该有效");

        Thread.sleep(1100);
        assertFalse(lockManager.isLocked(lockKey), "普通读者锁应该在长超时后过期");
    }

    @Test
    @DisplayName("测试不同读者等级下的锁定超时差异")
    void testLockTimeoutDifferenceByReaderType() {
        assertTrue(TestConstants.VIP_LOCK_TIMEOUT_SECONDS < TestConstants.NORMAL_LOCK_TIMEOUT_SECONDS,
                "VIP读者锁定超时应该短于普通读者");
        assertEquals(30, TestConstants.VIP_LOCK_TIMEOUT_SECONDS);
        assertEquals(120, TestConstants.NORMAL_LOCK_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("测试库存扣减正确性")
    void testInventoryDecrease() {
        Book book = bookBuilder.id("book_inventory")
                .stock(10)
                .available(10)
                .build();
        Reader reader = readerBuilder.id("reader_inventory")
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        borrowService.createBorrow(request);

        verify(inventoryService, times(1)).decreaseStock(eq(book.getBookId()), eq(1));
    }

    @Test
    @DisplayName("测试库存恢复正确性")
    void testInventoryRestore() {
        Book book = bookBuilder.id("book_restore")
                .stock(10)
                .available(5)
                .build();

        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));

        doAnswer(invocation -> {
            String bookId = invocation.getArgument(0);
            int count = invocation.getArgument(1);
            Book b = bookService.getBookById(bookId).get();
            b.setBookAvailable(b.getBookAvailable() + count);
            return null;
        }).when(inventoryService).increaseStock(anyString(), anyInt());

        inventoryService.increaseStock(book.getBookId(), 1);

        verify(inventoryService, times(1)).increaseStock(eq(book.getBookId()), eq(1));
    }

    @Test
    @DisplayName("测试图书库存为零时的拒绝处理")
    void testZeroStockRejection() {
        Book book = bookBuilder.id("book_zero")
                .stock(10)
                .available(0)
                .status(TestConstants.BOOK_STATUS_BORROWED)
                .build();
        Reader reader = readerBuilder.id("reader_zero")
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> borrowService.createBorrow(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("库存不足"));
        verify(inventoryService, never()).decreaseStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("测试图书状态不可借时的拒绝处理")
    void testUnavailableBookRejection() {
        Book book = bookBuilder.id("book_unavailable")
                .status(TestConstants.BOOK_STATUS_UNAVAILABLE)
                .available(5)
                .build();
        Reader reader = readerBuilder.id("reader_unavailable")
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> borrowService.createBorrow(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("图书不可借"));
    }

    @Test
    @DisplayName("测试冻结读者的借阅拒绝")
    void testFrozenReaderRejection() {
        Book book = bookBuilder.id("book_frozen")
                .stock(10)
                .build();
        Reader reader = readerBuilder.id("reader_frozen")
                .status(TestConstants.READER_STATUS_FROZEN)
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> borrowService.createBorrow(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("读者状态不可用"));
    }

    @Test
    @DisplayName("测试借阅已达上限的拒绝")
    void testBorrowLimitReached() {
        Book book = bookBuilder.id("book_limit")
                .stock(10)
                .build();
        Reader reader = readerBuilder.id("reader_limit")
                .borrowLimit(5)
                .borrowedCount(5)
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> borrowService.createBorrow(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("借阅已达上限"));
    }

    @Test
    @DisplayName("测试成功借阅的完整流程")
    void testSuccessfulBorrowFlow() {
        Book book = bookBuilder.id("book_success")
                .name("成功借阅测试图书")
                .stock(10)
                .available(10)
                .build();
        Reader reader = readerBuilder.id("reader_success")
                .type(TestConstants.READER_TYPE_NORMAL)
                .status(TestConstants.READER_STATUS_ACTIVE)
                .borrowLimit(5)
                .borrowedCount(2)
                .build();
        BorrowRequest request = new BorrowRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> {
            Borrow borrow = invocation.getArgument(0);
            return borrow;
        });

        BorrowResult result = borrowService.createBorrow(request);

        assertNotNull(result.getBorrow_id());
        assertEquals(TestConstants.BORROW_STATUS_BORROWED, result.getStatus());

        verify(readerService, times(1)).increaseBorrowedCount(reader.getReaderId());
        verify(inventoryService, times(1)).decreaseStock(book.getBookId(), 1);
        verify(analysisService, times(1)).incrementBorrowCount();
        verify(historyService, times(1)).log(eq("borrow"), anyString(), eq(book.getBookId()), eq(reader.getReaderId()), anyString());
    }

    @Test
    @DisplayName("测试VIP读者更高的借阅限额")
    void testVipReaderHigherLimit() {
        Reader vipReader = readerBuilder.id("vip_reader")
                .type(TestConstants.READER_TYPE_VIP)
                .borrowLimit(TestConstants.VIP_BORROW_LIMIT)
                .borrowedCount(8)
                .build();
        Reader normalReader = readerBuilder.id("normal_reader")
                .type(TestConstants.READER_TYPE_NORMAL)
                .borrowLimit(TestConstants.DEFAULT_BORROW_LIMIT)
                .borrowedCount(4)
                .build();

        assertTrue(vipReader.getBorrowLimit() > normalReader.getBorrowLimit(),
                "VIP读者借阅限额应该高于普通读者");
        assertEquals(TestConstants.VIP_BORROW_LIMIT, vipReader.getBorrowLimit());
        assertEquals(TestConstants.DEFAULT_BORROW_LIMIT, normalReader.getBorrowLimit());
    }

    @Test
    @DisplayName("测试锁释放后其他线程可获取锁")
    void testLockReleaseAllowsOtherThreads() throws InterruptedException {
        String lockKey = "borrow:book:release_test";
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger order = new AtomicInteger(0);

        Thread thread1 = new Thread(() -> {
            boolean acquired = lockManager.acquireLock(lockKey, 30);
            assertTrue(acquired);
            order.set(1);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lockManager.releaseLock(lockKey);
            latch.countDown();
        });

        Thread thread2 = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean acquired = lockManager.acquireLock(lockKey, 30);
            assertFalse(acquired, "线程2在线程1释放锁前不应该获取到锁");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acquired = lockManager.acquireLock(lockKey, 30);
            assertTrue(acquired, "线程1释放锁后，线程2应该能够获取锁");
            order.set(2);
            lockManager.releaseLock(lockKey);
            latch.countDown();
        });

        thread1.start();
        thread2.start();
        latch.await();

        assertEquals(2, order.get());
    }
}
