package com.exam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.entity.ExamAnswer;
import com.exam.entity.GradingRecord;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface GradingService {
    void autoGradeObjectiveQuestions(Long examRecordId);

    void autoGradeProgrammingQuestions(Long examRecordId);

    void assignSubjectiveQuestions(Long examId);

    IPage<ExamAnswer> getPendingGradingList(Long graderId, Long examId, int pageNum, int pageSize);

    GradingRecord gradeQuestion(Long answerId, Long graderId, BigDecimal score, String remark);

    GradingRecord submitArbitration(Long answerId, Long graderId, BigDecimal score, String remark);

    GradingRecord handleArbitration(Long answerId, Long arbiterId, BigDecimal score, String remark);

    void completeGrading(Long examRecordId);

    Map<String, BigDecimal> calculateScore(Long examRecordId);

    List<GradingRecord> getGradingRecords(Long answerId);

    IPage<GradingRecord> getGraderGradingList(Long graderId, Integer status, int pageNum, int pageSize);
}
