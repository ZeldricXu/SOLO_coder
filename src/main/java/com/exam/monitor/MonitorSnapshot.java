package com.exam.monitor;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MonitorSnapshot {
    private long timestamp;
    private int windowSeconds;
    private int totalOnline = 0;
    private int totalSubmitted = 0;
    private int totalAbnormal = 0;
    private long totalAnswerCount = 0;
    private List<ExamMonitorInfo> exams = new ArrayList<>();
}
