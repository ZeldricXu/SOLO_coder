package com.exam.monitor;

import com.exam.service.ExamMonitorService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorAggregator {

    private final ExamMonitorService examMonitorService;

    private final ConcurrentLinkedQueue<RawEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private final Map<Long, ExamStatsSnapshot> snapshots = new ConcurrentHashMap<>();
    private volatile MonitorSnapshot lastAggregatedSnapshot;
    private volatile LocalDateTime lastAggregateTime;

    private static final int WINDOW_SECONDS = 5;

    public void ingest(RawEvent event) {
        if (event == null) return;
        eventQueue.offer(event);
    }

    @Scheduled(fixedRate = 5000)
    public void aggregateWindow() {
        List<RawEvent> window = drainWindow();
        if (!window.isEmpty()) {
            log.debug("聚合窗口事件数: {}", window.size());
        }

        MonitorSnapshot snapshot = buildSnapshot(window);
        this.lastAggregatedSnapshot = snapshot;
        this.lastAggregateTime = LocalDateTime.now();

        for (Map.Entry<Long, ExamStatsSnapshot> e : snapshots.entrySet()) {
            ExamStatsSnapshot s = e.getValue();
            s.resetDelta();
        }
    }

    private List<RawEvent> drainWindow() {
        List<RawEvent> window = new java.util.ArrayList<>(eventQueue.size());
        RawEvent evt;
        while ((evt = eventQueue.poll()) != null) {
            window.add(evt);
            ExamStatsSnapshot stats = snapshots.computeIfAbsent(evt.getExamId(), ExamStatsSnapshot::new);
            stats.apply(evt);
        }
        return window;
    }

    private MonitorSnapshot buildSnapshot(List<RawEvent> events) {
        MonitorSnapshot snap = new MonitorSnapshot();
        snap.setTimestamp(System.currentTimeMillis());
        snap.setWindowSeconds(WINDOW_SECONDS);

        AtomicInteger totalOnline = new AtomicInteger();
        AtomicInteger totalSubmitted = new AtomicInteger();
        AtomicInteger totalAbnormal = new AtomicInteger();
        AtomicLong totalAnswerCount = new AtomicLong();

        for (Map.Entry<Long, ExamStatsSnapshot> e : snapshots.entrySet()) {
            Long examId = e.getKey();
            ExamStatsSnapshot s = e.getValue();

            ExamMonitorInfo info = new ExamMonitorInfo();
            info.setExamId(examId);
            info.setOnlineStudents(s.getOnlineStudents().get());
            info.setSubmittedStudents(s.getSubmittedStudents().get());
            info.setAbnormalCount(s.getAbnormalCountDelta());
            info.setSubmittedDelta(s.getSubmittedDelta());
            info.setAverageScore(s.getTotalScore().divide(
                    BigDecimal.valueOf(Math.max(s.getSubmittedStudents().get(), 1)), 2));

            snap.getExams().add(info);

            totalOnline.addAndGet(s.getOnlineStudents().get());
            totalSubmitted.addAndGet(s.getSubmittedStudents().get());
            totalAbnormal.addAndGet(s.getAbnormalCountDelta());
            totalAnswerCount.addAndGet(s.getAnswerCountDelta());
        }

        snap.setTotalOnline(totalOnline.get());
        snap.setTotalSubmitted(totalSubmitted.get());
        snap.setTotalAbnormal(totalAbnormal.get());
        snap.setTotalAnswerCount(totalAnswerCount.get());

        return snap;
    }

    public MonitorSnapshot getLatestSnapshot() {
        return lastAggregatedSnapshot != null ? lastAggregatedSnapshot : new MonitorSnapshot();
    }

    public LocalDateTime getLastAggregateTime() {
        return lastAggregateTime;
    }

    @Data
    public static class RawEvent {
        private Long examId;
        private String eventType;
        private Long studentId;
        private Long answerId;
        private Integer abnormalType;
        private BigDecimal score;
        private long timestamp;

        public static RawEvent online(Long examId, Long studentId) {
            RawEvent e = new RawEvent();
            e.examId = examId;
            e.studentId = studentId;
            e.eventType = "online";
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static RawEvent submit(Long examId, Long studentId, BigDecimal score) {
            RawEvent e = new RawEvent();
            e.examId = examId;
            e.studentId = studentId;
            e.score = score;
            e.eventType = "submit";
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static RawEvent answer(Long examId, Long answerId) {
            RawEvent e = new RawEvent();
            e.examId = examId;
            e.answerId = answerId;
            e.eventType = "answer";
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static RawEvent abnormal(Long examId, Integer type) {
            RawEvent e = new RawEvent();
            e.examId = examId;
            e.abnormalType = type;
            e.eventType = "abnormal";
            e.timestamp = System.currentTimeMillis();
            return e;
        }
    }
}
