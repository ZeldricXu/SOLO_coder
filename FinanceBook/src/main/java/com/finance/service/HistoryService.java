package com.finance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class HistoryService {

    private final Map<String, List<HistoryEntry>> historyStore = new ConcurrentHashMap<>();

    @Transactional
    public void recordHistory(String accountId, String action, String description) {
        HistoryEntry entry = new HistoryEntry(
                accountId,
                action,
                description,
                LocalDateTime.now()
        );

        historyStore.computeIfAbsent(accountId, k -> new ArrayList<>())
                .add(0, entry);

        log.debug("记录历史: accountId={}, action={}, description={}", accountId, action, description);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> getHistoryByAccount(String accountId) {
        List<HistoryEntry> entries = historyStore.getOrDefault(accountId, new ArrayList<>());
        log.debug("查询历史: accountId={}, count={}", accountId, entries.size());
        return new ArrayList<>(entries);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> getHistoryByAccountAndAction(String accountId, String action) {
        List<HistoryEntry> entries = historyStore.getOrDefault(accountId, new ArrayList<>());
        List<HistoryEntry> filtered = new ArrayList<>();
        for (HistoryEntry entry : entries) {
            if (action.equals(entry.getAction())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> getRecentHistory(String accountId, int limit) {
        List<HistoryEntry> entries = getHistoryByAccount(accountId);
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class HistoryEntry {
        private String accountId;
        private String action;
        private String description;
        private LocalDateTime timestamp;
    }
}
