package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.HistoryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryLogRepository extends JpaRepository<HistoryLog, Long> {
    List<HistoryLog> findByLogTypeOrderByCreatedAtDesc(String logType);
    List<HistoryLog> findByRefIdOrderByCreatedAtDesc(String refId);
    List<HistoryLog> findByBookIdOrderByCreatedAtDesc(String bookId);
    List<HistoryLog> findByReaderIdOrderByCreatedAtDesc(String readerId);
}
