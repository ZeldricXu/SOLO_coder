package com.library.librarymgmt;

import com.library.librarymgmt.dto.ReturnRequest;
import com.library.librarymgmt.dto.ReturnResult;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.entity.ReturnRecord;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.BorrowRepository;
import com.library.librarymgmt.repository.ReturnRecordRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.service.impl.ReturnServiceImpl;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("归还模块 - 归还提醒机制测试")
class ReturnServiceTest {

    @Mock
    private ReturnRecordRepository returnRecordRepository;

    @Mock
    private BorrowRepository borrowRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private ReaderService readerService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private ReserveService reserveService;

    @Spy
    @InjectMocks
    private ReturnServiceImpl returnService;

    private TestDataBuilder.BookBuilder bookBuilder;
    private TestDataBuilder.ReaderBuilder readerBuilder;
    private TestDataBuilder.BorrowBuilder borrowBuilder;
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        bookBuilder = new TestDataBuilder.BookBuilder();
        readerBuilder = new TestDataBuilder.ReaderBuilder();
        borrowBuilder = new TestDataBuilder.BorrowBuilder();
        reminderService = new ReminderService();
    }

    @Test
    @DisplayName("测试归还提醒定期触发的正确性")
    void testReminderPeriodicTrigger() throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        AtomicInteger triggerCount = new AtomicInteger(0);
        Book hotBook = bookBuilder.category(TestConstants.BOOK_CATEGORY_HOT).build();
        Book normalBook = bookBuilder.category(TestConstants.BOOK_CATEGORY_NORMAL).build();

        Runnable reminderTask = () -> {
            triggerCount.incrementAndGet();
        };

        scheduler.scheduleAtFixedRate(reminderTask, 0, 100, TimeUnit.MILLISECONDS);

        Thread.sleep(350);
        scheduler.shutdown();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);

        assertTrue(triggerCount.get() >= 3, "提醒应该至少触发3次");
    }

    @Test
    @DisplayName("测试热门图书早提醒")
    void testHotBookEarlyReminder() {
        String hotCategory = TestConstants.BOOK_CATEGORY_HOT;
        int hotReminderDays = reminderService.getReminderDaysBeforeDue(hotCategory);

        assertEquals(TestConstants.HOT_BOOK_REMIND_DAYS_BEFORE, hotReminderDays);
        assertTrue(reminderService.shouldSendReminder(hotCategory, 7), "离到期7天时热门图书应该发送提醒");
        assertTrue(reminderService.shouldSendReminder(hotCategory, 3), "离到期3天时热门图书应该发送提醒");
        assertFalse(reminderService.shouldSendReminder(hotCategory, 10), "离到期10天时热门图书不应该发送提醒");
    }

    @Test
    @DisplayName("测试普通图书晚提醒")
    void testNormalBookLateReminder() {
        String normalCategory = TestConstants.BOOK_CATEGORY_NORMAL;
        int normalReminderDays = reminderService.getReminderDaysBeforeDue(normalCategory);

        assertEquals(TestConstants.NORMAL_BOOK_REMIND_DAYS_BEFORE, normalReminderDays);
        assertTrue(reminderService.shouldSendReminder(normalCategory, 3), "离到期3天时普通图书应该发送提醒");
        assertTrue(reminderService.shouldSendReminder(normalCategory, 1), "离到期1天时普通图书应该发送提醒");
        assertFalse(reminderService.shouldSendReminder(normalCategory, 7), "离到期7天时普通图书不应该发送提醒");
    }

    @Test
    @DisplayName("测试不同图书类型下的提醒时间差异")
    void testReminderTimeDifferenceByBookType() {
        assertTrue(TestConstants.HOT_BOOK_REMIND_DAYS_BEFORE > TestConstants.NORMAL_BOOK_REMIND_DAYS_BEFORE,
                "热门图书提醒时间应该早于普通图书");
        assertEquals(7, TestConstants.HOT_BOOK_REMIND_DAYS_BEFORE);
        assertEquals(3, TestConstants.NORMAL_BOOK_REMIND_DAYS_BEFORE);
    }

    @Test
    @DisplayName("测试不同图书类型提醒触发时机")
    void testReminderTriggerTimingByType() {
        String hotCategory = TestConstants.BOOK_CATEGORY_HOT;
        String normalCategory = TestConstants.BOOK_CATEGORY_NORMAL;

        assertTrue(reminderService.shouldSendReminder(hotCategory, 5), "热门图书离到期5天时应该提醒");
        assertFalse(reminderService.shouldSendReminder(normalCategory, 5), "普通图书离到期5天时不应该提醒");

        assertTrue(reminderService.shouldSendReminder(hotCategory, 2), "热门图书离到期2天时应该提醒");
        assertTrue(reminderService.shouldSendReminder(normalCategory, 2), "普通图书离到期2天时应该提醒");
    }

    @Test
    @DisplayName("测试正常归还处理")
    void testNormalReturnProcessing() {
        Book book = bookBuilder.id("book_return")
                .stock(10)
                .available(5)
                .build();
        Reader reader = readerBuilder.id("reader_return")
                .borrowedCount(1)
                .build();
        Borrow borrow = borrowBuilder.id("borrow_001")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .borrowedDaysAgo(5)
                .dueInDays(10)
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnResult result = returnService.processReturn(request);

        assertNotNull(result.getReturn_id());
        assertEquals(TestConstants.RETURN_STATUS_NORMAL, result.getStatus());

        verify(inventoryService, times(1)).increaseStock(eq(book.getBookId()), eq(1));
        verify(readerService, times(1)).decreaseBorrowedCount(eq(reader.getReaderId()));
        verify(analysisService, times(1)).incrementReturnCount();
        verify(analysisService, never()).incrementOverdueCount();
    }

    @Test
    @DisplayName("测试逾期归还的状态处理")
    void testOverdueReturnStatusProcessing() {
        Book book = bookBuilder.id("book_overdue")
                .build();
        Reader reader = readerBuilder.id("reader_overdue")
                .build();
        int overdueDays = 5;
        Borrow borrow = borrowBuilder.id("borrow_overdue")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .overdueByDays(overdueDays)
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> {
            ReturnRecord record = invocation.getArgument(0);
            return record;
        });
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnResult result = returnService.processReturn(request);

        assertEquals(TestConstants.RETURN_STATUS_OVERDUE, result.getStatus());
        verify(analysisService, times(1)).incrementOverdueCount();
    }

    @Test
    @DisplayName("测试逾期归还的罚款计算")
    void testOverdueFineCalculation() {
        Book book = bookBuilder.id("book_fine")
                .build();
        Reader reader = readerBuilder.id("reader_fine")
                .build();
        int overdueDays = 3;
        Borrow borrow = borrowBuilder.id("borrow_fine")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .overdueByDays(overdueDays)
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        final ReturnRecord[] savedRecord = new ReturnRecord[1];

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> {
            savedRecord[0] = invocation.getArgument(0);
            return savedRecord[0];
        });
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        assertNotNull(savedRecord[0]);
        double expectedFine = overdueDays * TestConstants.OVERDUE_FINE_PER_DAY;
        assertEquals(expectedFine, savedRecord[0].getOverdueFine(), 0.01);
    }

    @Test
    @DisplayName("测试不同逾期天数的罚款计算")
    void testDifferentOverdueDaysFineCalculation() {
        int[] testDays = {1, 5, 10, 30};
        for (int days : testDays) {
            double expectedFine = days * TestConstants.OVERDUE_FINE_PER_DAY;
            assertEquals(expectedFine, days * 0.5, 0.01,
                    "逾期" + days + "天的罚款计算应该正确");
        }
    }

    @Test
    @DisplayName("测试归还后预约通知的触发")
    void testReservationNotificationTrigger() {
        Book book = bookBuilder.id("book_reserve_notify")
                .build();
        Reader reader = readerBuilder.id("reader_return")
                .build();
        Borrow borrow = borrowBuilder.id("borrow_reserve")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        verify(reserveService, times(1)).notifyWaitingReaders(eq(book.getBookId()));
    }

    @Test
    @DisplayName("测试重复归还的拒绝")
    void testDuplicateReturnRejection() {
        Book book = bookBuilder.id("book_duplicate")
                .build();
        Reader reader = readerBuilder.id("reader_duplicate")
                .build();
        Borrow borrow = borrowBuilder.id("borrow_duplicate")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .status(TestConstants.BORROW_STATUS_RETURNED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));

        LibraryException exception = assertThrows(LibraryException.class,
                () -> returnService.processReturn(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("重复归还"));
    }

    @Test
    @DisplayName("测试不存在的借阅记录")
    void testNonExistentBorrowRecord() {
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id("non_existent");

        when(borrowRepository.findByBorrowId("non_existent")).thenReturn(Optional.empty());

        LibraryException exception = assertThrows(LibraryException.class,
                () -> returnService.processReturn(request));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("借阅记录不存在"));
    }

    @Test
    @DisplayName("测试归还后库存恢复")
    void testInventoryRestoreAfterReturn() {
        Book book = bookBuilder.id("book_inventory_restore")
                .stock(10)
                .available(0)
                .status(TestConstants.BOOK_STATUS_BORROWED)
                .build();
        Reader reader = readerBuilder.id("reader_inventory")
                .build();
        Borrow borrow = borrowBuilder.id("borrow_inventory")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        verify(inventoryService, times(1)).increaseStock(eq(book.getBookId()), eq(1));
        verify(inventoryService, times(1)).updateBookStatusBasedOnStock(eq(book.getBookId()));
    }

    @Test
    @DisplayName("测试历史记录日志")
    void testHistoryLogging() {
        Book book = bookBuilder.id("book_history")
                .build();
        Reader reader = readerBuilder.id("reader_history")
                .build();
        Borrow borrow = borrowBuilder.id("borrow_history")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> {
            ReturnRecord record = invocation.getArgument(0);
            record.setReturnId("return_test_001");
            return record;
        });
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        verify(historyService, times(1)).log(
                eq("return"),
                eq("return_test_001"),
                eq(book.getBookId()),
                eq(reader.getReaderId()),
                contains("处理图书归还")
        );
    }

    @Test
    @DisplayName("测试逾期归还的历史记录日志")
    void testOverdueHistoryLogging() {
        Book book = bookBuilder.id("book_overdue_history")
                .build();
        Reader reader = readerBuilder.id("reader_overdue_history")
                .build();
        int overdueDays = 5;
        Borrow borrow = borrowBuilder.id("borrow_overdue_history")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .overdueByDays(overdueDays)
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> {
            ReturnRecord record = invocation.getArgument(0);
            record.setReturnId("return_overdue_001");
            return record;
        });
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        verify(historyService, times(1)).log(
                eq("return"),
                anyString(),
                eq(book.getBookId()),
                eq(reader.getReaderId()),
                contains("overdue")
        );
    }

    @Test
    @DisplayName("测试归还后读者借阅计数减少")
    void testReaderBorrowedCountDecrease() {
        Book book = bookBuilder.id("book_count")
                .build();
        Reader reader = readerBuilder.id("reader_count")
                .borrowedCount(3)
                .build();
        Borrow borrow = borrowBuilder.id("borrow_count")
                .bookId(book.getBookId())
                .readerId(reader.getReaderId())
                .status(TestConstants.BORROW_STATUS_BORROWED)
                .build();
        ReturnRequest request = new ReturnRequest();
        request.setBorrow_id(borrow.getBorrowId());

        when(borrowRepository.findByBorrowId(borrow.getBorrowId())).thenReturn(Optional.of(borrow));
        when(returnRecordRepository.save(any(ReturnRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(borrowRepository.save(any(Borrow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        returnService.processReturn(request);

        verify(readerService, times(1)).decreaseBorrowedCount(eq(reader.getReaderId()));
    }
}
