package com.exam.monitor;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
class ExamStatsSnapshot {
    private final Long examId;
    private final AtomicInteger onlineStudents = new AtomicInteger();
    private final AtomicInteger submittedStudents = new AtomicInteger();
    private final AtomicInteger abnormalCount = new AtomicInteger();
    private final AtomicLong totalAnswers = new AtomicLong();
    private BigDecimal totalScore = BigDecimal.ZERO;

    private transient int abnormalCountDelta = 0;
    private transient int submittedDelta = 0;
    private transient long answerCountDelta = 0;

    public ExamStatsSnapshot(Long examId) {
        this.examId = examId;
    }

    synchronized void apply(MonitorAggregator.RawEvent e) {
        if (e == null) return;
        switch (e.getEventType()) {
            case "online":
                onlineStudents.incrementAndGet();
                break;
            case "submit":
                submittedStudents.incrementAndGet();
                submittedDelta++;
                if (e.getScore() != null) {
                    totalScore = totalScore.add(e.getScore());
                }
                break;
            case "abnormal":
                abnormalCount.incrementAndGet();
                abnormalCountDelta++;
                break;
            case "answer":
                totalAnswers.incrementAndGet();
                answerCountDelta++;
                break;
            default:
                break;
        }
    }

    void resetDelta() {
        abnormalCountDelta = 0;
        submittedDelta = 0;
        answerCountDelta = 0;
    }

    int getAbnormalCountDelta() {
        return abnormalCountDelta;
    }

    int getSubmittedDelta() {
        return submittedDelta;
    }

    long getAnswerCountDelta() {
        return answerCountDelta;
    }

    BigDecimal getTotalScore() {
        return totalScore;
    }
}
