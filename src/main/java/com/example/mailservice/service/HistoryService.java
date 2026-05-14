package com.example.mailservice.service;

import com.example.mailservice.model.MailHistory;
import com.example.mailservice.repository.MailHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final MailHistoryRepository historyRepository;

    @Transactional
    public MailHistory recordHistory(String mailId, String actionType, String actionDetails, String actor) {
        MailHistory history = MailHistory.builder()
                .historyId("hist_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .mailId(mailId)
                .actionType(actionType)
                .actionDetails(actionDetails)
                .actor(actor)
                .build();

        return historyRepository.save(history);
    }

    public List<MailHistory> getHistoryByMailId(String mailId) {
        return historyRepository.findByMailIdOrderByCreatedAtDesc(mailId);
    }

    public Page<MailHistory> getHistoryPage(String mailId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return historyRepository.findByMailId(mailId, pageable);
    }

    public Optional<MailHistory> getHistoryById(String historyId) {
        return historyRepository.findByHistoryId(historyId);
    }

    public List<MailHistory> getHistoryByActionType(String actionType) {
        return historyRepository.findByActionType(actionType);
    }
}
