package com.exam.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ExamReportVO {
    private Long examId;
    private String examName;
    private Long examRecordId;
    private Long userId;
    private String userName;
    private BigDecimal totalScore;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal programmingScore;
    private BigDecimal finalScore;
    private Integer totalQuestions;
    private Integer correctCount;
    private Integer wrongCount;
    private Integer unansweredCount;
    private BigDecimal accuracy;
    private Integer rank;
    private Integer totalCandidates;
    private BigDecimal passScore;
    private Boolean isPass;
    private Integer usedTime;
    private Integer duration;
    private LocalDateTime startTime;
    private LocalDateTime submitTime;
    private Integer abnormalCount;
    private Integer screenSwitchCount;

    private List<QuestionReportVO> questionReports;
    private List<KnowledgePointScoreVO> knowledgePointScores;
}
