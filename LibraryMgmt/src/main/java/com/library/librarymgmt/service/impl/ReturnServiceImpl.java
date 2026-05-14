package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.dto.ReturnRequest;
import com.library.librarymgmt.dto.ReturnResult;
import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.entity.ReturnRecord;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.BorrowRepository;
import com.library.librarymgmt.repository.ReturnRecordRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class ReturnServiceImpl implements ReturnService {

    private final ReturnRecordRepository returnRecordRepository;
    private final BorrowRepository borrowRepository;
    private final InventoryService inventoryService;
    private final ReaderService readerService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final ReserveService reserveService;

    public ReturnServiceImpl(ReturnRecordRepository returnRecordRepository,
                             BorrowRepository borrowRepository,
                             InventoryService inventoryService,
                             ReaderService readerService,
                             AnalysisService analysisService,
                             HistoryService historyService,
                             ReserveService reserveService) {
        this.returnRecordRepository = returnRecordRepository;
        this.borrowRepository = borrowRepository;
        this.inventoryService = inventoryService;
        this.readerService = readerService;
        this.analysisService = analysisService;
        this.historyService = historyService;
        this.reserveService = reserveService;
    }

    @Override
    @Transactional
    public ReturnResult processReturn(ReturnRequest request) {
        String borrowId = request.getBorrow_id();

        Borrow borrow = borrowRepository.findByBorrowId(borrowId)
                .orElseThrow(() -> new LibraryException(404, "借阅记录不存在"));

        if ("returned".equals(borrow.getBorrowStatus())) {
            throw new LibraryException(400, "重复归还");
        }

        Instant returnTime = Instant.now();
        boolean isOverdue = returnTime.isAfter(borrow.getBorrowDue());
        String returnStatus = isOverdue ? "overdue" : "normal";
        double overdueFine = 0.0;

        if (isOverdue) {
            long overdueDays = ChronoUnit.DAYS.between(borrow.getBorrowDue(), returnTime);
            overdueFine = overdueDays * 0.5;
        }

        ReturnRecord returnRecord = new ReturnRecord();
        returnRecord.setReturnId(IdGenerator.generateReturnId());
        returnRecord.setBorrowId(borrowId);
        returnRecord.setBookId(borrow.getBookId());
        returnRecord.setReaderId(borrow.getReaderId());
        returnRecord.setReturnTime(returnTime);
        returnRecord.setReturnStatus(returnStatus);
        returnRecord.setOverdueFine(overdueFine);

        ReturnRecord savedReturn = returnRecordRepository.save(returnRecord);

        borrow.setBorrowStatus("returned");
        borrow.setReturnedAt(returnTime);
        borrowRepository.save(borrow);

        inventoryService.increaseStock(borrow.getBookId(), 1);
        inventoryService.updateBookStatusBasedOnStock(borrow.getBookId());
        readerService.decreaseBorrowedCount(borrow.getReaderId());
        analysisService.incrementReturnCount();

        if (isOverdue) {
            analysisService.incrementOverdueCount();
        }

        reserveService.notifyWaitingReaders(borrow.getBookId());

        historyService.log("return", savedReturn.getReturnId(), borrow.getBookId(), borrow.getReaderId(),
                "处理图书归还，归还状态: " + returnStatus + (isOverdue ? "，逾期天数: " + ChronoUnit.DAYS.between(borrow.getBorrowDue(), returnTime) : ""));

        ReturnResult result = new ReturnResult();
        result.setReturn_id(savedReturn.getReturnId());
        result.setStatus(returnStatus);

        return result;
    }

    @Override
    public Optional<ReturnRecord> getReturnById(String returnId) {
        return returnRecordRepository.findByReturnId(returnId);
    }

    @Override
    public Optional<ReturnRecord> getReturnByBorrowId(String borrowId) {
        return returnRecordRepository.findByBorrowId(borrowId);
    }

    @Override
    public List<ReturnRecord> getAllReturns() {
        return returnRecordRepository.findAll();
    }

    @Override
    public List<ReturnRecord> getReturnsByReaderId(String readerId) {
        return returnRecordRepository.findByReaderId(readerId);
    }

    @Override
    public List<ReturnRecord> getReturnsByBookId(String bookId) {
        return returnRecordRepository.findByBookId(bookId);
    }

    @Override
    public List<ReturnRecord> getReturnsByStatus(String status) {
        return returnRecordRepository.findByReturnStatus(status);
    }
}
