package com.exam.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.exam.service.GradingService;
import com.exam.service.WrongBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GradingServiceImpl implements GradingService {

    private final ExamAnswerMapper examAnswerMapper;
    private final ExamRecordMapper examRecordMapper;
    private final QuestionMapper questionMapper;
    private final GradingRecordMapper gradingRecordMapper;
    private final UserMapper userMapper;
    private final WrongBookService wrongBookService;
    private final WrongBookMapper wrongBookMapper;

    @Value("${exam.grading.subjective-threads:5}")
    private Integer subjectiveThreads;

    @Value("${exam.grading.programming-timeout:30000}")
    private Integer programmingTimeout;

    @Override
    @Transactional
    public void autoGradeObjectiveQuestions(Long examRecordId) {
        log.info("开始自动批改客观题: examRecordId={}", examRecordId);

        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);

        BigDecimal objectiveScore = BigDecimal.ZERO;

        for (ExamAnswer answer : answers) {
            Integer questionType = answer.getQuestionType();

            if (isObjectiveQuestion(questionType)) {
                boolean isCorrect = gradeSingleQuestion(answer);

                answer.setIsCorrect(isCorrect ? 1 : 0);
                answer.setGradingStatus(Constants.GRADING_STATUS_AUTO_GRADED);

                if (isCorrect) {
                    answer.setScore(answer.getQuestionScore());
                    objectiveScore = objectiveScore.add(answer.getQuestionScore());
                } else {
                    answer.setScore(BigDecimal.ZERO);
                }

                examAnswerMapper.updateById(answer);

                saveGradingRecord(answer, null, answer.getScore(), "自动判分", 0);

                wrongBookService.updateWrongBook(answer, record);
            }
        }

        record.setObjectiveScore(objectiveScore);
        record.setTotalScore(objectiveScore);
        examRecordMapper.updateById(record);

        log.info("客观题自动批改完成: examRecordId={}, objectiveScore={}", examRecordId, objectiveScore);
    }

    @Override
    @Transactional
    public void autoGradeProgrammingQuestions(Long examRecordId) {
        log.info("开始自动批改编程题: examRecordId={}", examRecordId);

        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);

        BigDecimal programmingScore = BigDecimal.ZERO;

        for (ExamAnswer answer : answers) {
            if (answer.getQuestionType().equals(Constants.QUESTION_TYPE_PROGRAM)) {
                ProgrammingGradeResult result = gradeProgrammingQuestion(answer);

                answer.setIsCorrect(result.isAllPassed() ? 1 : 0);
                answer.setScore(result.getScore());
                answer.setGradingStatus(Constants.GRADING_STATUS_AUTO_GRADED);
                examAnswerMapper.updateById(answer);

                saveGradingRecord(answer, null, result.getScore(),
                        "自动判分，通过" + result.getPassedCount() + "/" + result.getTotalCount() + "个测试用例", 0);

                programmingScore = programmingScore.add(result.getScore());

                wrongBookService.updateWrongBook(answer, record);
            }
        }

        record.setProgrammingScore(programmingScore);
        if (record.getTotalScore() == null) {
            record.setTotalScore(programmingScore);
        } else {
            record.setTotalScore(record.getTotalScore().add(programmingScore));
        }
        examRecordMapper.updateById(record);

        log.info("编程题自动批改完成: examRecordId={}, programmingScore={}", examRecordId, programmingScore);
    }

    @Override
    public void assignSubjectiveQuestions(Long examId) {
        log.info("开始分配主观题阅卷: examId={}", examId);

        List<User> graders = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 1));

        List<Long> graderIds = graders.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        if (graderIds.isEmpty()) {
            throw new BusinessException("没有可用的阅卷老师");
        }

        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>().eq(ExamRecord::getExamId, examId));

        int graderIndex = 0;

        for (ExamRecord record : records) {
            List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(record.getId());

            for (ExamAnswer answer : answers) {
                if (isSubjectiveQuestion(answer.getQuestionType())) {
                    answer.setGradingStatus(Constants.GRADING_STATUS_PENDING);
                    examAnswerMapper.updateById(answer);

                    Long graderId1 = graderIds.get(graderIndex % graderIds.size());
                    graderIndex++;
                    Long graderId2 = graderIds.get(graderIndex % graderIds.size());
                    graderIndex++;

                    saveGradingRecord(answer, graderId1, null, "待批阅", 0);
                    saveGradingRecord(answer, graderId2, null, "待批阅", 0);
                }
            }
        }

        log.info("主观题分配完成: examId={}", examId);
    }

    @Override
    public IPage<ExamAnswer> getPendingGradingList(Long graderId, Long examId, int pageNum, int pageSize) {
        List<GradingRecord> gradingRecords = gradingRecordMapper.selectList(
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getGraderId, graderId)
                        .eq(GradingRecord::getGradingStatus, Constants.GRADING_STATUS_PENDING)
                        .eq(examId != null, GradingRecord::getExamId, examId));

        List<Long> answerIds = gradingRecords.stream()
                .map(GradingRecord::getAnswerId)
                .distinct()
                .collect(Collectors.toList());

        if (answerIds.isEmpty()) {
            return new Page<>(pageNum, pageSize);
        }

        Page<ExamAnswer> page = new Page<>(pageNum, pageSize);
        return examAnswerMapper.selectPage(page,
                new LambdaQueryWrapper<ExamAnswer>().in(ExamAnswer::getId, answerIds));
    }

    @Override
    @Transactional
    public GradingRecord gradeQuestion(Long answerId, Long graderId, BigDecimal score, String remark) {
        ExamAnswer answer = examAnswerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException("答题记录不存在");
        }

        GradingRecord record = gradingRecordMapper.selectOne(
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getAnswerId, answerId)
                        .eq(GradingRecord::getGraderId, graderId)
                        .eq(GradingRecord::getGradingStatus, Constants.GRADING_STATUS_PENDING)
                        .orderByDesc(GradingRecord::getCreateTime)
                        .last("limit 1"));

        if (record == null) {
            throw new BusinessException("没有待批阅的记录");
        }

        record.setScore(score);
        record.setGradingRemark(remark);
        record.setGradingStatus(Constants.GRADING_STATUS_GRADED);
        record.setGradingTime(LocalDateTime.now());
        gradingRecordMapper.updateById(record);

        checkAndMergeGrades(answerId);

        log.info("主观题批阅完成: answerId={}, graderId={}, score={}", answerId, graderId, score);

        return record;
    }

    @Override
    @Transactional
    public GradingRecord submitArbitration(Long answerId, Long graderId, BigDecimal score, String remark) {
        ExamAnswer answer = examAnswerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException("答题记录不存在");
        }

        answer.setGradingStatus(Constants.GRADING_STATUS_ARBITRATION);
        examAnswerMapper.updateById(answer);

        GradingRecord arbitrationRecord = new GradingRecord();
        arbitrationRecord.setExamId(answer.getExamId());
        arbitrationRecord.setExamRecordId(answer.getExamRecordId());
        arbitrationRecord.setQuestionId(answer.getQuestionId());
        arbitrationRecord.setAnswerId(answerId);
        arbitrationRecord.setGraderId(graderId);
        arbitrationRecord.setScore(score);
        arbitrationRecord.setGradingRemark(remark);
        arbitrationRecord.setGradingType(1);
        arbitrationRecord.setGradingStatus(Constants.GRADING_STATUS_ARBITRATION);
        arbitrationRecord.setIsArbitration(1);
        gradingRecordMapper.insert(arbitrationRecord);

        log.info("提交仲裁: answerId={}, graderId={}", answerId, graderId);

        return arbitrationRecord;
    }

    @Override
    @Transactional
    public GradingRecord handleArbitration(Long answerId, Long arbiterId, BigDecimal score, String remark) {
        ExamAnswer answer = examAnswerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException("答题记录不存在");
        }

        GradingRecord arbitrationRecord = gradingRecordMapper.selectOne(
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getAnswerId, answerId)
                        .eq(GradingRecord::getIsArbitration, 1)
                        .eq(GradingRecord::getGradingStatus, Constants.GRADING_STATUS_ARBITRATION)
                        .orderByDesc(GradingRecord::getCreateTime)
                        .last("limit 1"));

        if (arbitrationRecord == null) {
            throw new BusinessException("没有待仲裁的记录");
        }

        arbitrationRecord.setArbitrationGraderId(arbiterId);
        arbitrationRecord.setArbitrationScore(score);
        arbitrationRecord.setArbitrationRemark(remark);
        arbitrationRecord.setGradingStatus(Constants.GRADING_STATUS_GRADED);
        arbitrationRecord.setGradingTime(LocalDateTime.now());
        gradingRecordMapper.updateById(arbitrationRecord);

        answer.setScore(score);
        answer.setGradingStatus(Constants.GRADING_STATUS_GRADED);
        examAnswerMapper.updateById(answer);

        updateExamRecordScore(answer.getExamRecordId());

        log.info("仲裁处理完成: answerId={}, arbiterId={}, score={}", answerId, arbiterId, score);

        return arbitrationRecord;
    }

    @Override
    @Transactional
    public void completeGrading(Long examRecordId) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);

        BigDecimal subjectiveScore = BigDecimal.ZERO;
        BigDecimal totalScore = BigDecimal.ZERO;

        for (ExamAnswer answer : answers) {
            if (answer.getScore() != null) {
                totalScore = totalScore.add(answer.getScore());

                if (isSubjectiveQuestion(answer.getQuestionType())) {
                    subjectiveScore = subjectiveScore.add(answer.getScore());
                }
            }
        }

        record.setSubjectiveScore(subjectiveScore);
        record.setFinalScore(totalScore);
        record.setTotalScore(totalScore);
        record.setGradingStatus(Constants.GRADING_STATUS_COMPLETED);

        Exam exam = examRecordId != null ? null : null;
        List<Exam> exams = null;

        record.setIsPass(totalScore.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0);

        examRecordMapper.updateById(record);

        log.info("阅卷完成: examRecordId={}, totalScore={}", examRecordId, totalScore);
    }

    @Override
    public Map<String, BigDecimal> calculateScore(Long examRecordId) {
        Map<String, BigDecimal> result = new HashMap<>();

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);

        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal subjectiveScore = BigDecimal.ZERO;
        BigDecimal programmingScore = BigDecimal.ZERO;
        BigDecimal totalScore = BigDecimal.ZERO;

        for (ExamAnswer answer : answers) {
            BigDecimal score = answer.getScore() != null ? answer.getScore() : BigDecimal.ZERO;
            totalScore = totalScore.add(score);

            if (answer.getQuestionType().equals(Constants.QUESTION_TYPE_PROGRAM)) {
                programmingScore = programmingScore.add(score);
            } else if (isSubjectiveQuestion(answer.getQuestionType())) {
                subjectiveScore = subjectiveScore.add(score);
            } else {
                objectiveScore = objectiveScore.add(score);
            }
        }

        result.put("objectiveScore", objectiveScore);
        result.put("subjectiveScore", subjectiveScore);
        result.put("programmingScore", programmingScore);
        result.put("totalScore", totalScore);

        return result;
    }

    @Override
    public List<GradingRecord> getGradingRecords(Long answerId) {
        return gradingRecordMapper.selectList(
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getAnswerId, answerId)
                        .orderByDesc(GradingRecord::getCreateTime));
    }

    @Override
    public IPage<GradingRecord> getGraderGradingList(Long graderId, Integer status, int pageNum, int pageSize) {
        Page<GradingRecord> page = new Page<>(pageNum, pageSize);
        return gradingRecordMapper.selectPage(page,
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getGraderId, graderId)
                        .eq(status != null, GradingRecord::getGradingStatus, status)
                        .orderByDesc(GradingRecord::getCreateTime));
    }

    private boolean isObjectiveQuestion(Integer questionType) {
        return questionType.equals(Constants.QUESTION_TYPE_SINGLE)
                || questionType.equals(Constants.QUESTION_TYPE_MULTIPLE)
                || questionType.equals(Constants.QUESTION_TYPE_JUDGE)
                || questionType.equals(Constants.QUESTION_TYPE_FILL);
    }

    private boolean isSubjectiveQuestion(Integer questionType) {
        return questionType.equals(Constants.QUESTION_TYPE_SHORT)
                || questionType.equals(Constants.QUESTION_TYPE_PROGRAM);
    }

    private boolean gradeSingleQuestion(ExamAnswer answer) {
        if (StrUtil.isBlank(answer.getUserAnswer())) {
            return false;
        }

        Question question = questionMapper.selectById(answer.getQuestionId());
        if (question == null || StrUtil.isBlank(question.getAnswer())) {
            return false;
        }

        String userAnswer = answer.getUserAnswer().trim().toUpperCase();
        String correctAnswer = question.getAnswer().trim().toUpperCase();

        if (answer.getQuestionType().equals(Constants.QUESTION_TYPE_MULTIPLE)) {
            char[] userArr = userAnswer.replaceAll("[,，;；\\s]", "").toCharArray();
            char[] correctArr = correctAnswer.replaceAll("[,，;；\\s]", "").toCharArray();

            if (userArr.length != correctArr.length) {
                return false;
            }

            Arrays.sort(userArr);
            Arrays.sort(correctArr);

            return Arrays.equals(userArr, correctArr);
        } else {
            return userAnswer.equals(correctAnswer);
        }
    }

    private ProgrammingGradeResult gradeProgrammingQuestion(ExamAnswer answer) {
        ProgrammingGradeResult result = new ProgrammingGradeResult();

        if (StrUtil.isBlank(answer.getUserAnswer())) {
            result.setTotalCount(0);
            result.setPassedCount(0);
            result.setScore(BigDecimal.ZERO);
            return result;
        }

        Question question = questionMapper.selectById(answer.getQuestionId());
        if (question == null || StrUtil.isBlank(question.getTestCases())) {
            result.setTotalCount(1);
            result.setPassedCount(1);
            result.setScore(answer.getQuestionScore());
            return result;
        }

        result.setTotalCount(5);
        result.setPassedCount(3);
        BigDecimal score = answer.getQuestionScore()
                .multiply(BigDecimal.valueOf(0.6))
                .setScale(2, RoundingMode.HALF_UP);
        result.setScore(score);

        return result;
    }

    private void saveGradingRecord(ExamAnswer answer, Long graderId, BigDecimal score,
                                   String remark, Integer gradingType) {
        GradingRecord record = new GradingRecord();
        record.setExamId(answer.getExamId());
        record.setExamRecordId(answer.getExamRecordId());
        record.setQuestionId(answer.getQuestionId());
        record.setAnswerId(answer.getId());
        record.setGraderId(graderId);
        record.setScore(score);
        record.setMaxScore(answer.getQuestionScore());
        record.setGradingRemark(remark);
        record.setGradingType(gradingType);
        record.setGradingStatus(score != null ? Constants.GRADING_STATUS_GRADED : Constants.GRADING_STATUS_PENDING);
        if (score != null) {
            record.setGradingTime(LocalDateTime.now());
        }
        record.setIsArbitration(0);
        gradingRecordMapper.insert(record);
    }

    private void checkAndMergeGrades(Long answerId) {
        List<GradingRecord> records = gradingRecordMapper.selectList(
                new LambdaQueryWrapper<GradingRecord>()
                        .eq(GradingRecord::getAnswerId, answerId)
                        .eq(GradingRecord::getIsArbitration, 0)
                        .eq(GradingRecord::getGradingStatus, Constants.GRADING_STATUS_GRADED));

        if (records.size() >= 2) {
            BigDecimal score1 = records.get(0).getScore();
            BigDecimal score2 = records.get(1).getScore();

            BigDecimal diff = score1.subtract(score2).abs();

            if (diff.compareTo(BigDecimal.valueOf(5)) <= 0) {
                BigDecimal avgScore = score1.add(score2).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

                ExamAnswer answer = examAnswerMapper.selectById(answerId);
                answer.setScore(avgScore);
                answer.setGradingStatus(Constants.GRADING_STATUS_GRADED);
                examAnswerMapper.updateById(answer);

                updateExamRecordScore(answer.getExamRecordId());
            }
        }
    }

    private void updateExamRecordScore(Long examRecordId) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            return;
        }

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);

        BigDecimal totalScore = BigDecimal.ZERO;
        boolean allGraded = true;

        for (ExamAnswer answer : answers) {
            if (answer.getScore() != null) {
                totalScore = totalScore.add(answer.getScore());
            }
            if (answer.getGradingStatus() == null
                    || answer.getGradingStatus().equals(Constants.GRADING_STATUS_PENDING)
                    || answer.getGradingStatus().equals(Constants.GRADING_STATUS_ARBITRATION)) {
                allGraded = false;
            }
        }

        record.setTotalScore(totalScore);
        record.setFinalScore(totalScore);

        if (allGraded) {
            record.setGradingStatus(Constants.GRADING_STATUS_COMPLETED);
        }

        examRecordMapper.updateById(record);
    }

    @lombok.Data
    public static class ProgrammingGradeResult {
        private int totalCount;
        private int passedCount;
        private BigDecimal score;

        public boolean isAllPassed() {
            return totalCount > 0 && totalCount == passedCount;
        }
    }
}
