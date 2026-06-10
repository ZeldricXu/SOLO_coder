package com.exam.service;

import com.exam.vo.*;

import java.util.List;

public interface ScoreAnalysisService {
    ExamReportVO getExamReport(Long examRecordId);

    ExamStatisticsVO getExamStatistics(Long examId);

    KnowledgeRadarVO getKnowledgeRadar(Long examRecordId);

    List<ScoreDistributionVO> getScoreDistribution(Long examId);

    PersonalScoreVO getPersonalScoreSummary(Long userId, Long subjectId);

    ClassScoreVO getClassScore(Long classId, Long examId);

    byte[] generateReportPdf(Long examRecordId);
}
