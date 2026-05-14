package com.library.librarymgmt.service;

import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.repository.BorrowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ReminderSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderSchedulerService.class);

    private final BorrowRepository borrowRepository;
    private final BookService bookService;
    private final ReminderService reminderService;
    private final HistoryService historyService;

    public ReminderSchedulerService(BorrowRepository borrowRepository,
                                    BookService bookService,
                                    ReminderService reminderService,
                                    HistoryService historyService) {
        this.borrowRepository = borrowRepository;
        this.bookService = bookService;
        this.reminderService = reminderService;
        this.historyService = historyService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void scheduleReturnReminders() {
        logger.info("开始执行归还提醒调度任务");

        List<Borrow> activeBorrows = borrowRepository.findByBorrowStatus("borrowed");

        for (Borrow borrow : activeBorrows) {
            try {
                processReminderForBorrow(borrow);
            } catch (Exception e) {
                logger.error("处理借阅提醒时发生错误: borrowId={}, error={}",
                        borrow.getBorrowId(), e.getMessage());
            }
        }

        logger.info("归还提醒调度任务执行完成，共处理 {} 条借阅记录", activeBorrows.size());
    }

    private void processReminderForBorrow(Borrow borrow) {
        Instant now = Instant.now();
        Instant dueDate = borrow.getBorrowDue();
        long daysUntilDue = Duration.between(now, dueDate).toDays();

        Book book = bookService.getBookById(borrow.getBookId()).orElse(null);
        if (book == null) {
            logger.warn("借阅记录关联的图书不存在: borrowId={}, bookId={}",
                    borrow.getBorrowId(), borrow.getBookId());
            return;
        }

        int reminderDays = reminderService.getReminderDaysBeforeDue(book.getBookCategory());
        logger.debug("借阅记录: borrowId={}, bookCategory={}, daysUntilDue={}, reminderDays={}",
                borrow.getBorrowId(), book.getBookCategory(), daysUntilDue, reminderDays);

        if (reminderService.shouldSendReminder(book.getBookCategory(), (int) daysUntilDue)) {
            sendReminder(borrow, book, (int) daysUntilDue);
        } else if (daysUntilDue < 0) {
            handleOverdueBorrow(borrow, book, (int) Math.abs(daysUntilDue));
        }
    }

    private void sendReminder(Borrow borrow, Book book, int daysUntilDue) {
        logger.info("发送归还提醒: borrowId={}, bookId={}, readerId={}, bookCategory={}, daysUntilDue={}",
                borrow.getBorrowId(), book.getBookId(), borrow.getReaderId(),
                book.getBookCategory(), daysUntilDue);

        historyService.log(
                "return_reminder",
                borrow.getBorrowId(),
                book.getBookId(),
                borrow.getReaderId(),
                "归还提醒: 距离到期还有 " + daysUntilDue + " 天，图书分类: " + book.getBookCategory()
        );
    }

    private void handleOverdueBorrow(Borrow borrow, Book book, int overdueDays) {
        logger.warn("检测到逾期借阅: borrowId={}, bookId={}, readerId={}, overdueDays={}",
                borrow.getBorrowId(), book.getBookId(), borrow.getReaderId(), overdueDays);

        historyService.log(
                "overdue_notice",
                borrow.getBorrowId(),
                book.getBookId(),
                borrow.getReaderId(),
                "逾期提醒: 已逾期 " + overdueDays + " 天，图书分类: " + book.getBookCategory()
        );
    }
}
