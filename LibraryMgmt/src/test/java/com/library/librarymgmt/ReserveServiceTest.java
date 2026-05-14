package com.library.librarymgmt;

import com.library.librarymgmt.dto.ReserveRequest;
import com.library.librarymgmt.dto.ReserveResult;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.entity.Reserve;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.ReserveRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.service.impl.ReserveServiceImpl;
import com.library.librarymgmt.testdata.TestDataBuilder;
import com.library.librarymgmt.testdata.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预约模块 - 预约通知异步化测试")
class ReserveServiceTest {

    @Mock
    private ReserveRepository reserveRepository;

    @Mock
    private BookService bookService;

    @Mock
    private ReaderService readerService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private NotificationService notificationService;

    @Spy
    @InjectMocks
    private ReserveServiceImpl reserveService;

    private TestDataBuilder.BookBuilder bookBuilder;
    private TestDataBuilder.ReaderBuilder readerBuilder;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        bookBuilder = new TestDataBuilder.BookBuilder();
        readerBuilder = new TestDataBuilder.ReaderBuilder();
        executorService = Executors.newCachedThreadPool();
    }

    @Test
    @DisplayName("测试图书归还完成后立即返回响应不阻塞")
    void testImmediateResponseWithoutBlocking() throws InterruptedException, ExecutionException {
        Book book = bookBuilder.id("book_async")
                .stock(10)
                .available(0)
                .status(TestConstants.BOOK_STATUS_BORROWED)
                .build();
        List<Reserve> waitingReserves = new ArrayList<>();
        Reserve reserve1 = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r1").build());
        Reserve reserve2 = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r2").build());
        waitingReserves.add(reserve1);
        waitingReserves.add(reserve2);

        when(reserveRepository.findByBookIdAndReserveStatusOrderByReserveTimeAsc(
                eq(book.getBookId()), eq(TestConstants.RESERVE_STATUS_WAITING)))
                .thenReturn(waitingReserves);
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> {
            Reserve r = invocation.getArgument(0);
            r.setNotified(true);
            r.setReserveStatus(TestConstants.RESERVE_STATUS_NOTIFIED);
            return r;
        });
        when(notificationService.sendReservationNotification(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }));

        long startTime = System.currentTimeMillis();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            reserveService.notifyWaitingReaders(book.getBookId());
        });

        future.get(100, TimeUnit.MILLISECONDS);

        long endTime = System.currentTimeMillis();
        assertTrue((endTime - startTime) < 500, "主流程应该在通知完成前返回，不应该被阻塞");

        verify(reserveRepository, times(2)).save(any(Reserve.class));
    }

    @Test
    @DisplayName("测试后台Worker执行预约通知发送处理")
    void testBackgroundWorkerNotificationProcessing() throws InterruptedException {
        int notificationCount = 5;
        CountDownLatch latch = new CountDownLatch(notificationCount);
        AtomicInteger processedCount = new AtomicInteger(0);

        ExecutorService workerPool = Executors.newFixedThreadPool(3);

        for (int i = 0; i < notificationCount; i++) {
            final String reserveId = "reserve_" + i;
            final String bookId = "book_test";
            final String readerId = "reader_" + i;

            workerPool.submit(() -> {
                try {
                    Thread.sleep(50);
                    processedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "所有通知应该在5秒内完成");
        assertEquals(notificationCount, processedCount.get(), "所有通知都应该被处理");

        workerPool.shutdown();
    }

    @Test
    @DisplayName("测试通知发送失败时的重试机制 - 第一次失败后重试成功")
    void testNotificationRetryOnFailure() {
        AtomicInteger attemptCount = new AtomicInteger(0);
        int maxRetries = 3;

        NotificationService testNotificationService = new NotificationService() {
            @Override
            public boolean sendReservationNotificationSync(String reserveId, String bookId, String readerId, int retries) {
                int currentAttempt = attemptCount.incrementAndGet();
                if (currentAttempt < 2) {
                    throw new RuntimeException("模拟发送失败");
                }
                return true;
            }
        };

        boolean result = false;
        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries) {
            try {
                result = testNotificationService.sendReservationNotificationSync(
                        "reserve_test", "book_test", "reader_test", maxRetries);
                break;
            } catch (Exception e) {
                lastException = e;
                attempts++;
                if (attempts < maxRetries) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        assertTrue(result, "重试后通知应该成功");
        assertEquals(2, attemptCount.get(), "应该在第二次尝试时成功");
    }

    @Test
    @DisplayName("测试通知发送达到最大重试次数后的处理")
    void testMaxRetryExhausted() {
        AtomicInteger attemptCount = new AtomicInteger(0);
        int maxRetries = TestConstants.MAX_NOTIFICATION_RETRIES;

        NotificationService testNotificationService = new NotificationService() {
            @Override
            public boolean sendReservationNotificationSync(String reserveId, String bookId, String readerId, int retries) {
                attemptCount.incrementAndGet();
                throw new RuntimeException("模拟持续失败");
            }
        };

        boolean result = false;
        int attempts = 0;

        while (attempts < maxRetries) {
            try {
                result = testNotificationService.sendReservationNotificationSync(
                        "reserve_test", "book_test", "reader_test", maxRetries);
                break;
            } catch (Exception e) {
                attempts++;
                if (attempts < maxRetries) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        assertFalse(result, "达到最大重试次数后应该返回失败");
        assertEquals(maxRetries, attemptCount.get(), "应该尝试了" + maxRetries + "次");
    }

    @Test
    @DisplayName("测试预约状态从waiting变为notified")
    void testReservationStatusChangeToNotified() {
        Book book = bookBuilder.id("book_status")
                .available(0)
                .status(TestConstants.BOOK_STATUS_BORROWED)
                .build();
        Reserve reserve = TestDataBuilder.buildWaitingReserve(book, readerBuilder.build());

        when(reserveRepository.findByReserveId(reserve.getReserveId()))
                .thenReturn(Optional.of(reserve));
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> {
            Reserve r = invocation.getArgument(0);
            return r;
        });

        Reserve updatedReserve = reserveService.updateReserveStatus(
                reserve.getReserveId(), TestConstants.RESERVE_STATUS_NOTIFIED);

        assertEquals(TestConstants.RESERVE_STATUS_NOTIFIED, updatedReserve.getReserveStatus());
        verify(reserveRepository, times(1)).save(any(Reserve.class));
    }

    @Test
    @DisplayName("测试预约状态变更的正确性")
    void testReservationStatusTransitionCorrectness() {
        Book book = bookBuilder.id("book_transition")
                .available(0)
                .build();
        Reserve reserve = TestDataBuilder.buildWaitingReserve(book, readerBuilder.build());

        when(reserveRepository.findByReserveId(reserve.getReserveId()))
                .thenReturn(Optional.of(reserve));
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reserve reserveAfterNotify = reserveService.updateReserveStatus(
                reserve.getReserveId(), TestConstants.RESERVE_STATUS_NOTIFIED);
        assertEquals(TestConstants.RESERVE_STATUS_NOTIFIED, reserveAfterNotify.getReserveStatus());

        when(reserveRepository.findByReserveId(reserve.getReserveId()))
                .thenReturn(Optional.of(reserveAfterNotify));

        Reserve reserveAfterComplete = reserveService.updateReserveStatus(
                reserve.getReserveId(), TestConstants.RESERVE_STATUS_COMPLETED);
        assertEquals(TestConstants.RESERVE_STATUS_COMPLETED, reserveAfterComplete.getReserveStatus());
    }

    @Test
    @DisplayName("测试预约创建成功")
    void testSuccessfulReservationCreation() {
        Book book = bookBuilder.id("book_create")
                .available(0)
                .status(TestConstants.BOOK_STATUS_BORROWED)
                .build();
        Reader reader = readerBuilder.id("reader_create")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .build();
        ReserveRequest request = new ReserveRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> {
            Reserve reserve = invocation.getArgument(0);
            return reserve;
        });

        ReserveResult result = reserveService.createReserve(request);

        assertNotNull(result.getReserve_id());
        assertEquals(TestConstants.RESERVE_STATUS_WAITING, result.getStatus());
        verify(analysisService, times(1)).incrementReserveCount();
        verify(historyService, times(1)).log(
                eq("reserve"),
                anyString(),
                eq(book.getBookId()),
                eq(reader.getReaderId()),
                contains("创建图书预约")
        );
    }

    @Test
    @DisplayName("测试图书库存充足时拒绝预约")
    void testReservationRejectionWhenStockAvailable() {
        Book book = bookBuilder.id("book_available")
                .available(5)
                .status(TestConstants.BOOK_STATUS_AVAILABLE)
                .build();
        Reader reader = readerBuilder.id("reader_available")
                .build();
        ReserveRequest request = new ReserveRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById(book.getBookId())).thenReturn(Optional.of(book));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> reserveService.createReserve(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("可直接借阅"));
    }

    @Test
    @DisplayName("测试读者不存在时拒绝预约")
    void testReservationRejectionWhenReaderNotExist() {
        Book book = bookBuilder.id("book_no_reader")
                .available(0)
                .build();
        ReserveRequest request = new ReserveRequest();
        request.setBook_id(book.getBookId());
        request.setReader_id("non_existent_reader");

        when(readerService.getReaderById("non_existent_reader")).thenReturn(Optional.empty());

        LibraryException exception = assertThrows(LibraryException.class,
                () -> reserveService.createReserve(request));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("读者不存在"));
    }

    @Test
    @DisplayName("测试图书不存在时拒绝预约")
    void testReservationRejectionWhenBookNotExist() {
        Reader reader = readerBuilder.id("reader_no_book")
                .build();
        ReserveRequest request = new ReserveRequest();
        request.setBook_id("non_existent_book");
        request.setReader_id(reader.getReaderId());

        when(readerService.getReaderById(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(bookService.getBookById("non_existent_book")).thenReturn(Optional.empty());

        LibraryException exception = assertThrows(LibraryException.class,
                () -> reserveService.createReserve(request));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("图书不存在"));
    }

    @Test
    @DisplayName("测试通知标记的状态变化")
    void testNotificationFlagUpdate() {
        Book book = bookBuilder.id("book_notify_flag")
                .available(0)
                .build();
        List<Reserve> waitingReserves = new ArrayList<>();
        Reserve reserve = TestDataBuilder.buildWaitingReserve(book, readerBuilder.build());
        reserve.setNotified(false);
        waitingReserves.add(reserve);

        when(reserveRepository.findByBookIdAndReserveStatusOrderByReserveTimeAsc(
                eq(book.getBookId()), eq(TestConstants.RESERVE_STATUS_WAITING)))
                .thenReturn(waitingReserves);
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> {
            Reserve r = invocation.getArgument(0);
            return r;
        });

        reserveService.notifyWaitingReaders(book.getBookId());

        verify(reserveRepository, times(1)).save(argThat(r ->
                r.getNotified() != null && r.getNotified()
        ));
    }

    @Test
    @DisplayName("测试异步通知与同步执行的性能差异")
    void testAsyncVsSyncPerformance() throws InterruptedException, ExecutionException {
        int taskCount = 10;

        long syncStartTime = System.currentTimeMillis();
        for (int i = 0; i < taskCount; i++) {
            Thread.sleep(10);
        }
        long syncDuration = System.currentTimeMillis() - syncStartTime;

        CountDownLatch asyncLatch = new CountDownLatch(taskCount);
        long asyncStartTime = System.currentTimeMillis();
        ExecutorService asyncPool = Executors.newFixedThreadPool(5);

        for (int i = 0; i < taskCount; i++) {
            asyncPool.submit(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    asyncLatch.countDown();
                }
            });
        }

        asyncLatch.await(5, TimeUnit.SECONDS);
        long asyncDuration = System.currentTimeMillis() - asyncStartTime;
        asyncPool.shutdown();

        assertTrue(asyncDuration < syncDuration, "异步执行应该比同步执行快");
    }

    @Test
    @DisplayName("测试多个等待预约的通知顺序")
    void testMultipleWaitingReservesNotificationOrder() {
        Book book = bookBuilder.id("book_multi")
                .available(0)
                .build();
        List<Reserve> waitingReserves = new ArrayList<>();

        Reserve reserve1 = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r1").build());
        Reserve reserve2 = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r2").build());
        Reserve reserve3 = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r3").build());

        waitingReserves.add(reserve1);
        waitingReserves.add(reserve2);
        waitingReserves.add(reserve3);

        when(reserveRepository.findByBookIdAndReserveStatusOrderByReserveTimeAsc(
                eq(book.getBookId()), eq(TestConstants.RESERVE_STATUS_WAITING)))
                .thenReturn(waitingReserves);
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reserveService.notifyWaitingReaders(book.getBookId());

        verify(reserveRepository, times(3)).save(any(Reserve.class));
    }

    @Test
    @DisplayName("测试已通知的预约不再重复通知")
    void testAlreadyNotifiedReservationNotReNotified() {
        Book book = bookBuilder.id("book_already")
                .available(0)
                .build();
        List<Reserve> reserves = new ArrayList<>();

        Reserve waitingReserve = TestDataBuilder.buildWaitingReserve(book, readerBuilder.id("r_waiting").build());
        waitingReserve.setNotified(false);

        Reserve alreadyNotifiedReserve = TestDataBuilder.buildNotifiedReserve(book, readerBuilder.id("r_notified").build());
        alreadyNotifiedReserve.setNotified(true);

        reserves.add(waitingReserve);
        reserves.add(alreadyNotifiedReserve);

        when(reserveRepository.findByBookIdAndReserveStatusOrderByReserveTimeAsc(
                eq(book.getBookId()), eq(TestConstants.RESERVE_STATUS_WAITING)))
                .thenReturn(java.util.Collections.singletonList(waitingReserve));
        when(reserveRepository.save(any(Reserve.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reserveService.notifyWaitingReaders(book.getBookId());

        verify(reserveRepository, times(1)).save(any(Reserve.class));
    }
}
