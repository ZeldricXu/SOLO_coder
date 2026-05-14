package com.fitnesscenter.service;

import com.fitnesscenter.model.History;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class HistoryService {

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public List<History> getHistoryByMemberId(String memberId) {
        return historyRepository.findByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public List<History> getHistoryByMemberIdAndActionType(String memberId, String actionType) {
        return historyRepository.findByMemberIdAndActionType(memberId, actionType);
    }

    @Transactional(readOnly = true)
    public List<History> getHistoryByActionType(String actionType) {
        return historyRepository.findByActionType(actionType);
    }

    @Transactional(readOnly = true)
    public List<History> getHistoryByRelatedId(String relatedId) {
        return historyRepository.findByRelatedId(relatedId);
    }

    @Transactional(readOnly = true)
    public List<History> getMemberHistoryInRange(String memberId, Instant startTime, Instant endTime) {
        return historyRepository.findByMemberIdAndActionTimeBetween(memberId, startTime, endTime);
    }

    @Transactional(readOnly = true)
    public List<History> getMemberHistoryThisMonth(String memberId) {
        LocalDate now = LocalDate.now();
        LocalDate startOfMonth = now.withDayOfMonth(1);
        Instant startInstant = startOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endInstant = now.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        return historyRepository.findByMemberIdAndActionTimeBetween(memberId, startInstant, endInstant);
    }

    @Transactional(readOnly = true)
    public List<History> getAllHistory() {
        return historyRepository.findAll();
    }

    @Transactional
    public History recordHistory(History history) {
        if (history.getHistoryId() == null) {
            history.setHistoryId(IdGenerator.generateHistoryId());
        }
        if (history.getActionTime() == null) {
            history.setActionTime(Instant.now());
        }
        return historyRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<History> queryTrainingHistory(String memberId) {
        return historyRepository.findByMemberIdAndActionType(memberId, "TRAINING_RECORD");
    }

    @Transactional(readOnly = true)
    public List<History> queryBookingHistory(String memberId) {
        return historyRepository.findByMemberIdAndActionType(memberId, "BOOKING_CREATE");
    }

    @Transactional(readOnly = true)
    public List<History> queryPlanHistory(String memberId) {
        return historyRepository.findByMemberIdAndActionType(memberId, "PLAN_CREATE");
    }
}
