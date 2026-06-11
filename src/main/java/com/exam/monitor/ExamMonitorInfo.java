package com.exam.monitor;

import lombok.Data;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

@Data
class ExamMonitorInfo {
    private Long examId;
    private Integer onlineStudents = 0;
    private Integer submittedStudents = 0;
    private Integer abnormalCount = 0;
    private Integer submittedDelta = 0;
    private BigDecimal averageScore = BigDecimal.ZERO;
}
