package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.Borrow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BorrowRepository extends JpaRepository<Borrow, String> {
    Optional<Borrow> findByBorrowId(String borrowId);
    List<Borrow> findByReaderId(String readerId);
    List<Borrow> findByBookId(String bookId);
    List<Borrow> findByBorrowStatus(String status);
    List<Borrow> findByReaderIdAndBorrowStatus(String readerId, String status);
    List<Borrow> findByBorrowStatusAndBorrowDueBefore(String status, Instant time);
    boolean existsByBorrowId(String borrowId);
}
