package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.BorrowRequest;
import com.library.librarymgmt.dto.BorrowResult;
import com.library.librarymgmt.entity.Borrow;

import java.util.List;
import java.util.Optional;

public interface BorrowService {
    BorrowResult createBorrow(BorrowRequest request);
    Optional<Borrow> getBorrowById(String borrowId);
    List<Borrow> getAllBorrows();
    List<Borrow> getBorrowsByReaderId(String readerId);
    List<Borrow> getBorrowsByBookId(String bookId);
    List<Borrow> getActiveBorrowsByReaderId(String readerId);
    List<Borrow> getOverdueBorrows();
}
