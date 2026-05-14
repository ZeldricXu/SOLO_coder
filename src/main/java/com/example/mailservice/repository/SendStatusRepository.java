package com.example.mailservice.repository;

import com.example.mailservice.model.SendStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SendStatusRepository extends JpaRepository<SendStatus, Long> {
    Optional<SendStatus> findByStatusId(String statusId);

    Optional<SendStatus> findByMailId(String mailId);

    List<SendStatus> findByMailIdIn(List<String> mailIds);

    List<SendStatus> findBySendStatus(String sendStatus);

    List<SendStatus> findBySendStatusAndSendAttemptsLessThan(String sendStatus, int maxAttempts);
}
