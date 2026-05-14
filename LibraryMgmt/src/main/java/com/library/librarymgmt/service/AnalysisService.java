package com.library.librarymgmt.service;

import com.library.librarymgmt.entity.BorrowStat;

import java.util.List;
import java.util.Optional;

public interface AnalysisService {
    BorrowStat getOrCreateCurrentMonthStat();
    void incrementBorrowCount();
    void incrementReturnCount();
    void incrementReserveCount();
    void incrementOverdueCount();
    Optional<BorrowStat> getStatByMonth(String month);
    List<BorrowStat> getAllStats();
    long getTotalBooks();
    long getTotalReaders();
    long getActiveBorrowsCount();
    long getOverdueBorrowsCount();
    long getWaitingReservesCount();
}
