package com.example.mailservice.repository;

import com.example.mailservice.model.MailHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailHistoryRepository extends JpaRepository<MailHistory, Long> {
    Optional<MailHistory> findByHistoryId(String historyId);

    List<MailHistory> findByMailIdOrderByCreatedAtDesc(String mailId);

    Page<MailHistory> findByMailId(String mailId, Pageable pageable);

    List<MailHistory> findByActionType(String actionType);

    List<MailHistory> findByMailIdIn(List<String> mailIds);
}
