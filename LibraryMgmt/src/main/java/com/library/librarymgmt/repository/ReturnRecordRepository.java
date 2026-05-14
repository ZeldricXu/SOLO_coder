package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.ReturnRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReturnRecordRepository extends JpaRepository<ReturnRecord, String> {
    Optional<ReturnRecord> findByReturnId(String returnId);
    Optional<ReturnRecord> findByBorrowId(String borrowId);
    List<ReturnRecord> findByReaderId(String readerId);
    List<ReturnRecord> findByBookId(String bookId);
    List<ReturnRecord> findByReturnStatus(String status);
    boolean existsByReturnId(String returnId);
}
