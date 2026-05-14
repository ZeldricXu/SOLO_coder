package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.ReturnRequest;
import com.library.librarymgmt.dto.ReturnResult;
import com.library.librarymgmt.entity.ReturnRecord;

import java.util.List;
import java.util.Optional;

public interface ReturnService {
    ReturnResult processReturn(ReturnRequest request);
    Optional<ReturnRecord> getReturnById(String returnId);
    Optional<ReturnRecord> getReturnByBorrowId(String borrowId);
    List<ReturnRecord> getAllReturns();
    List<ReturnRecord> getReturnsByReaderId(String readerId);
    List<ReturnRecord> getReturnsByBookId(String bookId);
    List<ReturnRecord> getReturnsByStatus(String status);
}
