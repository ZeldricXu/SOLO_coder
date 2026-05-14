package com.reviewsystem.service;

import com.reviewsystem.model.CommentHistory;
import com.reviewsystem.repository.CommentHistoryRepository;
import com.reviewsystem.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class HistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    @Autowired
    private CommentHistoryRepository commentHistoryRepository;

    @Transactional
    public void recordHistory(String commentId, String actionType, String description,
                              String oldStatus, String newStatus,
                              String oldContent, String newContent,
                              String operator, String operatorType) {
        CommentHistory history = new CommentHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setCommentId(commentId);
        history.setActionType(actionType);
        history.setActionDescription(description);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setOldContent(oldContent);
        history.setNewContent(newContent);
        history.setOperator(operator);
        history.setOperatorType(operatorType);
        commentHistoryRepository.save(history);

        logger.debug("记录历史: commentId={}, action={}, operator={}",
                commentId, actionType, operator);
    }

    public List<CommentHistory> getCommentHistory(String commentId) {
        return commentHistoryRepository.findByCommentIdOrderByActionTimeDesc(commentId);
    }

    public List<CommentHistory> getCommentHistoryWithTimeRange(String commentId,
                                                                LocalDateTime startTime,
                                                                LocalDateTime endTime) {
        return commentHistoryRepository.findByCommentIdAndTimeRange(commentId, startTime, endTime);
    }

    public List<CommentHistory> getHistoryByOperator(String operator) {
        return commentHistoryRepository.findByOperator(operator);
    }

    public List<CommentHistory> getHistoryByActionType(String actionType) {
        return commentHistoryRepository.findByActionType(actionType);
    }

    public List<CommentHistory> getHistoryByContentId(String contentId) {
        return commentHistoryRepository.findByContentId(contentId);
    }

    public List<CommentHistory> getHistoryByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return commentHistoryRepository.findByTimeRange(startTime, endTime);
    }

    public List<CommentHistory> getRecentHistory(int limit) {
        List<CommentHistory> all = commentHistoryRepository.findAll();
        all.sort((h1, h2) -> h2.getActionTime().compareTo(h1.getActionTime()));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    public Map<String, Long> getHistoryStats() {
        Map<String, Long> stats = new HashMap<>();
        List<CommentHistory> all = commentHistoryRepository.findAll();

        for (CommentHistory h : all) {
            String type = h.getActionType();
            stats.put(type, stats.getOrDefault(type, 0L) + 1);
        }

        return stats;
    }

    public Map<String, Object> getCommentHistorySummary(String commentId) {
        Map<String, Object> summary = new HashMap<>();
        List<CommentHistory> histories = getCommentHistory(commentId);

        summary.put("comment_id", commentId);
        summary.put("total_actions", histories.size());

        Map<String, Integer> actionCounts = new HashMap<>();
        for (CommentHistory h : histories) {
            String type = h.getActionType();
            actionCounts.put(type, actionCounts.getOrDefault(type, 0) + 1);
        }
        summary.put("action_counts", actionCounts);

        if (!histories.isEmpty()) {
            summary.put("first_action_time", histories.get(histories.size() - 1).getActionTime());
            summary.put("last_action_time", histories.get(0).getActionTime());
        }

        return summary;
    }

    public List<Map<String, Object>> getAuditTrail(String commentId) {
        List<CommentHistory> histories = getCommentHistory(commentId);
        List<Map<String, Object>> trail = new ArrayList<>();

        for (CommentHistory h : histories) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("action_type", h.getActionType());
            entry.put("description", h.getActionDescription());
            entry.put("old_status", h.getOldStatus());
            entry.put("new_status", h.getNewStatus());
            entry.put("operator", h.getOperator());
            entry.put("operator_type", h.getOperatorType());
            entry.put("action_time", h.getActionTime());
            trail.add(entry);
        }

        return trail;
    }
}
