package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.entity.HistoryLog;
import com.library.librarymgmt.repository.HistoryLogRepository;
import com.library.librarymgmt.service.HistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryServiceImpl implements HistoryService {

    private final HistoryLogRepository historyLogRepository;

    public HistoryServiceImpl(HistoryLogRepository historyLogRepository) {
        this.historyLogRepository = historyLogRepository;
    }

    @Override
    @Transactional
    public HistoryLog log(String logType, String refId, String bookId, String readerId, String action) {
        HistoryLog log = new HistoryLog();
        log.setLogType(logType);
        log.setRefId(refId);
        log.setBookId(bookId);
        log.setReaderId(readerId);
        log.setAction(action);
        return historyLogRepository.save(log);
    }

    @Override
    public List<HistoryLog> getLogsByType(String logType) {
        return historyLogRepository.findByLogTypeOrderByCreatedAtDesc(logType);
    }

    @Override
    public List<HistoryLog> getLogsByRefId(String refId) {
        return historyLogRepository.findByRefIdOrderByCreatedAtDesc(refId);
    }

    @Override
    public List<HistoryLog> getLogsByBookId(String bookId) {
        return historyLogRepository.findByBookIdOrderByCreatedAtDesc(bookId);
    }

    @Override
    public List<HistoryLog> getLogsByReaderId(String readerId) {
        return historyLogRepository.findByReaderIdOrderByCreatedAtDesc(readerId);
    }

    @Override
    public List<HistoryLog> getAllLogs() {
        return historyLogRepository.findAll();
    }
}
