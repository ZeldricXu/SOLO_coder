package com.exam.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.exam.service.ExamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamServiceImpl implements ExamService {

    private final ExamMapper examMapper;
    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final AbnormalRecordMapper abnormalRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    @Value("${exam.session.screen-switch-threshold}")
    private Integer screenSwitchThreshold;

    @Override
    public IPage<Exam> getExamPage(int pageNum, int pageSize, Long subjectId, Integer status, String keyword) {
        LambdaQueryWrapper<Exam> wrapper = new LambdaQueryWrapper<>();
        if (subjectId != null) {
            wrapper.eq(Exam::getSubjectId, subjectId);
        }
        if (status != null) {
            wrapper.eq(Exam::getExamStatus, status);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Exam::getExamName, keyword);
        }
        wrapper.orderByDesc(Exam::getCreateTime);

        Page<Exam> page = new Page<>(pageNum, pageSize);
        return examMapper.selectPage(page, wrapper);
    }

    @Override
    public Exam getExamById(Long id) {
        Exam exam = examMapper.selectById(id);
        if (exam == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return exam;
    }

    @Override
    @Transactional
    public Exam createExam(Exam exam) {
        exam.setExamStatus(Constants.EXAM_STATUS_NOT_STARTED);
        exam.setSubmittedCount(0);
        exam.setGradingStatus(Constants.GRADING_STATUS_PENDING);
        examMapper.insert(exam);
        log.info("考试创建成功: examId={}, examName={}", exam.getId(), exam.getExamName());
        return exam;
    }

    @Override
    @Transactional
    public Exam updateExam(Exam exam) {
        Exam existing = examMapper.selectById(exam.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        examMapper.updateById(exam);
        log.info("考试更新成功: examId={}", exam.getId());
        return getExamById(exam.getId());
    }

    @Override
    @Transactional
    public void deleteExam(Long id) {
        Exam exam = examMapper.selectById(id);
        if (exam == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        examMapper.deleteById(id);
        log.info("考试删除成功: examId={}", id);
    }

    @Override
    @Transactional
    public void publishExam(Long id) {
        Exam exam = getExamById(id);
        if (exam.getExamStatus() != Constants.EXAM_STATUS_NOT_STARTED) {
            throw new BusinessException("考试状态不正确");
        }
        exam.setExamStatus(Constants.EXAM_STATUS_IN_PROGRESS);
        examMapper.updateById(exam);
        log.info("考试发布成功: examId={}", id);
    }

    @Override
    @Transactional
    public void startExam(Long id) {
        Exam exam = getExamById(id);
        exam.setExamStatus(Constants.EXAM_STATUS_IN_PROGRESS);
        examMapper.updateById(exam);
        log.info("考试开始: examId={}", id);
    }

    @Override
    @Transactional
    public void endExam(Long id) {
        Exam exam = getExamById(id);
        exam.setExamStatus(Constants.EXAM_STATUS_ENDED);
        exam.setGradingStatus(Constants.GRADING_STATUS_GRADING);
        examMapper.updateById(exam);

        log.info("考试结束，开始触发自动阅卷: examId={}", id);
        triggerAutoGrading(id);
    }

    @Override
    public List<Exam> getStudentExams(Long userId) {
        return examMapper.selectList(
                new LambdaQueryWrapper<Exam>()
                        .orderByDesc(Exam::getStartTime));
    }

    @Override
    @Transactional
    public ExamRecord enterExam(Long examId, Long userId) {
        Exam exam = getExamById(examId);

        LocalDateTime now = LocalDateTime.now();
        if (exam.getEnterStartTime() != null && now.isBefore(exam.getEnterStartTime())) {
            throw new BusinessException(ResultCode.EXAM_NOT_STARTED);
        }
        if (exam.getEnterEndTime() != null && now.isAfter(exam.getEnterEndTime())) {
            throw new BusinessException(ResultCode.EXAM_ENDED);
        }

        ExamRecord existingRecord = examRecordMapper.selectOne(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, examId)
                        .eq(ExamRecord::getUserId, userId)
                        .orderByDesc(ExamRecord::getCreateTime)
                        .last("limit 1"));

        if (existingRecord != null && existingRecord.getExamStatus().equals(Constants.EXAM_STATUS_ENDED)) {
            throw new BusinessException(ResultCode.EXAM_ALREADY_SUBMITTED);
        }

        if (existingRecord != null && existingRecord.getExamStatus().equals(Constants.EXAM_STATUS_IN_PROGRESS)) {
            return existingRecord;
        }

        Paper paper = paperMapper.selectById(exam.getPaperId());
        if (paper == null) {
            throw new BusinessException(ResultCode.PAPER_NOT_FOUND);
        }

        String abPaperType = "A";
        if (exam.getAbPaper() != null && exam.getAbPaper() == 1) {
            abPaperType = (userId.hashCode() % 2 == 0) ? "A" : "B";
        }

        ExamRecord record = new ExamRecord();
        record.setExamId(examId);
        record.setUserId(userId);
        record.setPaperId(exam.getPaperId());
        record.setPaperVersion(String.valueOf(paper.getPaperVersion()));
        record.setStartTime(LocalDateTime.now());
        record.setDuration(exam.getDuration());
        record.setExamStatus(Constants.EXAM_STATUS_IN_PROGRESS);
        record.setGradingStatus(Constants.GRADING_STATUS_PENDING);
        record.setScreenSwitchCount(0);
        record.setDisconnectCount(0);
        record.setAbnormalCount(0);
        record.setAbPaperType(abPaperType);
        examRecordMapper.insert(record);

        initExamAnswers(record.getId(), exam.getPaperId());

        saveExamSession(examId, userId, record.getId());

        addOnlineUser(examId, userId);

        log.info("考生进入考试: examId={}, userId={}, recordId={}", examId, userId, record.getId());

        return record;
    }

    @Override
    public ExamRecord getExamRecord(Long examRecordId) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }
        return record;
    }

    @Override
    public List<ExamAnswer> getExamAnswers(Long examRecordId) {
        return examAnswerMapper.selectByExamRecordId(examRecordId);
    }

    @Override
    @Transactional
    public void saveAnswer(Long examRecordId, Long questionId, String answer) {
        ExamAnswer examAnswer = examAnswerMapper.selectOne(
                new LambdaQueryWrapper<ExamAnswer>()
                        .eq(ExamAnswer::getExamRecordId, examRecordId)
                        .eq(ExamAnswer::getQuestionId, questionId));

        if (examAnswer == null) {
            return;
        }

        examAnswer.setUserAnswer(answer);
        examAnswer.setAnswerStatus(StrUtil.isNotBlank(answer) ?
                Constants.ANSWER_STATUS_ANSWERED : Constants.ANSWER_STATUS_NOT_ANSWERED);
        examAnswer.setAnswerTime(LocalDateTime.now());
        examAnswerMapper.updateById(examAnswer);

        updateExamSessionAnswer(examRecordId, questionId, answer);

        log.debug("保存答题: recordId={}, questionId={}", examRecordId, questionId);
    }

    @Override
    @Transactional
    public ExamRecord submitExam(Long examRecordId, Long userId, Integer submitType) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        if (record.getExamStatus().equals(Constants.EXAM_STATUS_ENDED)) {
            throw new BusinessException(ResultCode.EXAM_ALREADY_SUBMITTED);
        }

        record.setSubmitTime(LocalDateTime.now());
        record.setSubmitType(submitType);

        long usedTime = ChronoUnit.SECONDS.between(record.getStartTime(), record.getSubmitTime());
        record.setUsedTime((int) usedTime);

        record.setExamStatus(Constants.EXAM_STATUS_ENDED);
        record.setGradingStatus(Constants.GRADING_STATUS_AUTO_GRADED);
        examRecordMapper.updateById(record);

        Exam exam = examMapper.selectById(record.getExamId());
        if (exam != null) {
            exam.setSubmittedCount(exam.getSubmittedCount() + 1);
            examMapper.updateById(exam);
        }

        removeOnlineUser(record.getExamId(), userId);
        removeExamSession(record.getExamId(), userId);

        rabbitTemplate.convertAndSend(
                "exam.grading.exchange",
                "exam.grading",
                examRecordId
        );

        log.info("试卷提交成功: recordId={}, submitType={}", examRecordId, submitType);

        return record;
    }

    @Override
    @Transactional
    public void reportAbnormal(Long examRecordId, Integer abnormalType, String detail) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            return;
        }

        AbnormalRecord abnormal = new AbnormalRecord();
        abnormal.setExamId(record.getExamId());
        abnormal.setExamRecordId(examRecordId);
        abnormal.setUserId(record.getUserId());
        abnormal.setAbnormalType(abnormalType);
        abnormal.setAbnormalDetail(detail);
        abnormal.setAbnormalTime(LocalDateTime.now());
        abnormal.setSeverity(abnormalType.equals(Constants.ABNORMAL_TYPE_SCREEN_SWITCH) ? 2 : 1);
        abnormal.setHandled(0);
        abnormalRecordMapper.insert(abnormal);

        record.setAbnormalCount(record.getAbnormalCount() + 1);

        if (abnormalType.equals(Constants.ABNORMAL_TYPE_SCREEN_SWITCH)) {
            record.setScreenSwitchCount(record.getScreenSwitchCount() + 1);

            if (record.getScreenSwitchCount() >= screenSwitchThreshold) {
                abnormal.setSeverity(3);
                abnormalRecordMapper.updateById(abnormal);
            }
        }

        if (abnormalType.equals(Constants.ABNORMAL_TYPE_DISCONNECT)) {
            record.setDisconnectCount(record.getDisconnectCount() + 1);
        }

        examRecordMapper.updateById(record);

        log.warn("考试异常行为: recordId={}, type={}, detail={}", examRecordId, abnormalType, detail);
    }

    @Override
    public ExamRecord getCurrentExamRecord(Long examId, Long userId) {
        return examRecordMapper.selectOne(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, examId)
                        .eq(ExamRecord::getUserId, userId)
                        .eq(ExamRecord::getExamStatus, Constants.EXAM_STATUS_IN_PROGRESS)
                        .orderByDesc(ExamRecord::getCreateTime)
                        .last("limit 1"));
    }

    private void initExamAnswers(Long examRecordId, Long paperId) {
        List<PaperQuestion> paperQuestions = paperQuestionMapper.selectByPaperId(paperId);
        ExamRecord record = examRecordMapper.selectById(examRecordId);

        for (int i = 0; i < paperQuestions.size(); i++) {
            PaperQuestion pq = paperQuestions.get(i);
            ExamAnswer answer = new ExamAnswer();
            answer.setExamId(record.getExamId());
            answer.setExamRecordId(examRecordId);
            answer.setUserId(record.getUserId());
            answer.setPaperId(paperId);
            answer.setQuestionId(pq.getQuestionId());
            answer.setQuestionType(pq.getQuestionType());
            answer.setQuestionOrder(pq.getQuestionOrder());
            answer.setQuestionScore(pq.getScore());
            answer.setAnswerStatus(Constants.ANSWER_STATUS_NOT_ANSWERED);
            answer.setGradingStatus(Constants.GRADING_STATUS_PENDING);
            answer.setSortOrder(pq.getSortOrder() != null ? pq.getSortOrder() : i + 1);
            examAnswerMapper.insert(answer);
        }
    }

    private void saveExamSession(Long examId, Long userId, Long recordId) {
        String key = Constants.REDIS_EXAM_SESSION_PREFIX + examId + ":" + userId;
        redisTemplate.opsForValue().set(key, recordId, 2, TimeUnit.HOURS);
    }

    private void removeExamSession(Long examId, Long userId) {
        String key = Constants.REDIS_EXAM_SESSION_PREFIX + examId + ":" + userId;
        redisTemplate.delete(key);
    }

    private void updateExamSessionAnswer(Long examRecordId, Long questionId, String answer) {
    }

    private void addOnlineUser(Long examId, Long userId) {
        String key = Constants.REDIS_EXAM_ONLINE_PREFIX + examId;
        redisTemplate.opsForSet().add(key, userId);
        redisTemplate.expire(key, 2, TimeUnit.HOURS);
    }

    private void removeOnlineUser(Long examId, Long userId) {
        String key = Constants.REDIS_EXAM_ONLINE_PREFIX + examId;
        redisTemplate.opsForSet().remove(key, userId);
    }

    private void triggerAutoGrading(Long examId) {
        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, examId));

        for (ExamRecord record : records) {
            if (record.getExamStatus().equals(Constants.EXAM_STATUS_IN_PROGRESS)) {
                submitExam(record.getId(), record.getUserId(), 2);
            } else {
                rabbitTemplate.convertAndSend(
                        "exam.grading.exchange",
                        "exam.grading",
                        record.getId()
                );
            }
        }
    }
}
