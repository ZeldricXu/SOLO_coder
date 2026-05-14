package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.dto.BorrowRequest;
import com.library.librarymgmt.dto.BorrowResult;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.BorrowRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.util.IdGenerator;
import com.library.librarymgmt.util.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class BorrowServiceImpl implements BorrowService {

    private static final Logger logger = LoggerFactory.getLogger(BorrowServiceImpl.class);

    private final BorrowRepository borrowRepository;
    private final BookService bookService;
    private final ReaderService readerService;
    private final InventoryService inventoryService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final LibraryConfig libraryConfig;
    private final LockManager lockManager;
    private final CategoryService categoryService;

    public BorrowServiceImpl(BorrowRepository borrowRepository,
                             BookService bookService,
                             ReaderService readerService,
                             InventoryService inventoryService,
                             AnalysisService analysisService,
                             HistoryService historyService,
                             LibraryConfig libraryConfig,
                             LockManager lockManager,
                             CategoryService categoryService) {
        this.borrowRepository = borrowRepository;
        this.bookService = bookService;
        this.readerService = readerService;
        this.inventoryService = inventoryService;
        this.analysisService = analysisService;
        this.historyService = historyService;
        this.libraryConfig = libraryConfig;
        this.lockManager = lockManager;
        this.categoryService = categoryService;
    }

    @Override
    @Transactional
    public BorrowResult createBorrow(BorrowRequest request) {
        String bookId = request.getBook_id();
        String readerId = request.getReader_id();
        String lockKey = "borrow:book:" + bookId;

        Reader reader = readerService.getReaderById(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));

        if (!"active".equals(reader.getReaderStatus())) {
            throw new LibraryException(400, "读者状态不可用");
        }

        if (reader.getBorrowedCount() >= reader.getBorrowLimit()) {
            throw new LibraryException(400, "读者借阅已达上限");
        }

        int lockTimeout = lockManager.getLockTimeoutByReaderType(reader.getReaderType());
        boolean lockAcquired = lockManager.acquireLock(lockKey, lockTimeout);
        if (!lockAcquired) {
            throw new LibraryException(409, "图书库存锁定中，请稍后重试");
        }

        try {
            Book book = bookService.getBookById(bookId)
                    .orElseThrow(() -> new LibraryException(404, "图书不存在"));

            if (!"available".equals(book.getBookStatus())) {
                throw new LibraryException(400, "图书不可借");
            }

            if (book.getBookAvailable() <= 0) {
                throw new LibraryException(400, "库存不足");
            }

            categoryService.validateCategory(book.getBookCategory());

            int borrowDays = categoryService.getMaxBorrowDays(book.getBookCategory());

            Borrow borrow = new Borrow();
            borrow.setBorrowId(IdGenerator.generateBorrowId());
            borrow.setBookId(bookId);
            borrow.setReaderId(readerId);
            borrow.setBorrowTime(Instant.now());
            borrow.setBorrowDue(Instant.now().plus(borrowDays, ChronoUnit.DAYS));
            borrow.setBorrowStatus("borrowed");

            Borrow savedBorrow = borrowRepository.save(borrow);

            inventoryService.decreaseStock(bookId, 1);
            readerService.increaseBorrowedCount(readerId);
            analysisService.incrementBorrowCount();

            historyService.log("borrow", savedBorrow.getBorrowId(), bookId, readerId,
                    "创建借阅记录，锁超时: " + lockTimeout + "秒，借阅期限: " + borrowDays + "天，到期时间: " + savedBorrow.getBorrowDue());

            logger.info("借阅成功: borrowId={}, bookId={}, readerId={}, readerType={}, lockTimeout={}s",
                    savedBorrow.getBorrowId(), bookId, readerId, reader.getReaderType(), lockTimeout);

            BorrowResult result = new BorrowResult();
            result.setBorrow_id(savedBorrow.getBorrowId());
            result.setStatus(savedBorrow.getBorrowStatus());

            return result;
        } finally {
            lockManager.releaseLock(lockKey);
        }
    }

    @Override
    public Optional<Borrow> getBorrowById(String borrowId) {
        return borrowRepository.findByBorrowId(borrowId);
    }

    @Override
    public List<Borrow> getAllBorrows() {
        return borrowRepository.findAll();
    }

    @Override
    public List<Borrow> getBorrowsByReaderId(String readerId) {
        return borrowRepository.findByReaderId(readerId);
    }

    @Override
    public List<Borrow> getBorrowsByBookId(String bookId) {
        return borrowRepository.findByBookId(bookId);
    }

    @Override
    public List<Borrow> getActiveBorrowsByReaderId(String readerId) {
        return borrowRepository.findByReaderIdAndBorrowStatus(readerId, "borrowed");
    }

    @Override
    public List<Borrow> getOverdueBorrows() {
        return borrowRepository.findByBorrowStatusAndBorrowDueBefore("borrowed", Instant.now());
    }
}
