package com.exam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.entity.Exam;
import com.exam.entity.ExamAnswer;
import com.exam.entity.ExamRecord;

import java.util.List;

public interface ExamService {
    IPage<Exam> getExamPage(int pageNum, int pageSize, Long subjectId, Integer status, String keyword);

    Exam getExamById(Long id);

    Exam createExam(Exam exam);

    Exam updateExam(Exam exam);

    void deleteExam(Long id);

    void publishExam(Long id);

    void startExam(Long id);

    void endExam(Long id);

    List<Exam> getStudentExams(Long userId);

    ExamRecord enterExam(Long examId, Long userId);

    ExamRecord getExamRecord(Long examRecordId);

    List<ExamAnswer> getExamAnswers(Long examRecordId);

    void saveAnswer(Long examRecordId, Long questionId, String answer);

    ExamRecord submitExam(Long examRecordId, Long userId, Integer submitType);

    void reportAbnormal(Long examRecordId, Integer abnormalType, String detail);

    ExamRecord getCurrentExamRecord(Long examId, Long userId);
}
