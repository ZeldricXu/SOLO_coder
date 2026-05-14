package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.SendStatus;
import com.example.mailservice.repository.SendStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatusService {

    private final SendStatusRepository statusRepository;
    private final AppConfig appConfig;

    @Transactional
    public SendStatus createStatus(String mailId, String sendStatus, String smtpResponse, String errorMessage) {
        SendStatus status = SendStatus.builder()
                .statusId("status_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .mailId(mailId)
                .sendStatus(sendStatus)
                .smtpResponse(smtpResponse)
                .errorMessage(errorMessage)
                .sendAttempts(1)
                .lastAttempt(LocalDateTime.now())
                .build();

        return statusRepository.save(status);
    }

    public Optional<SendStatus> getStatusByMailId(String mailId) {
        return statusRepository.findByMailId(mailId);
    }

    public Optional<SendStatus> getStatusByStatusId(String statusId) {
        return statusRepository.findByStatusId(statusId);
    }

    @Transactional
    public SendStatus updateStatus(String mailId, String newStatus, String smtpResponse, String errorMessage) {
        Optional<SendStatus> existingOpt = statusRepository.findByMailId(mailId);
        if (!existingOpt.isPresent()) {
            return createStatus(mailId, newStatus, smtpResponse, errorMessage);
        }

        SendStatus existing = existingOpt.get();
        existing.setSendStatus(newStatus);
        existing.setSmtpResponse(smtpResponse);
        existing.setErrorMessage(errorMessage);
        existing.setSendAttempts(existing.getSendAttempts() + 1);
        existing.setLastAttempt(LocalDateTime.now());

        return statusRepository.save(existing);
    }

    public List<SendStatus> getFailedStatusesForRetry() {
        return statusRepository.findBySendStatusAndSendAttemptsLessThan(
                "failed", appConfig.getMail().getRetryCount());
    }

    @Transactional
    public void incrementAttempts(String mailId) {
        Optional<SendStatus> statusOpt = statusRepository.findByMailId(mailId);
        if (statusOpt.isPresent()) {
            SendStatus status = statusOpt.get();
            status.setSendAttempts(status.getSendAttempts() + 1);
            status.setLastAttempt(LocalDateTime.now());
            statusRepository.save(status);
        }
    }
}
