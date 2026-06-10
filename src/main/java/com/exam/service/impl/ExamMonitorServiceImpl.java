package com.exam.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.exam.service.ExamMonitorService;
import com.exam.vo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExamMonitorServiceImpl implements ExamMonitorService {

    private final ExamMapper examMapper;
    private final ExamRecordMapper examRecordMapper;
    private final AbnormalRecordMapper abnormalRecordMapper;
    private final UserMapper userMapper;
    private final SubjectMapper subjectMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public ExamMonitorVO getExamMonitorData(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            return new ExamMonitorVO();
        }

        ExamMonitorVO monitor = new ExamMonitorVO();
        monitor.setExamId(examId);
        monitor.setExamName(exam.getExamName());
        monitor.setTotalCandidates(exam.getTotalCandidates());
        monitor.setSubmittedCount(exam.getSubmittedCount());
        monitor.setUnsubmittedCount(exam.getTotalCandidates() - exam.getSubmittedCount());

        if (exam.getTotalCandidates() > 0) {
            monitor.setSubmitProgress((double) exam.getSubmittedCount() / exam.getTotalCandidates() * 100);
        }

        String onlineKey = Constants.REDIS_EXAM_ONLINE_PREFIX + examId;
        Set<Object> onlineUsers = redisTemplate.opsForSet().members(onlineKey);
        int onlineCount = onlineUsers != null ? onlineUsers.size() : 0;
        monitor.setOnlineCount(onlineCount);

        List<AbnormalRecord> abnormals = abnormalRecordMapper.selectList(
                new LambdaQueryWrapper<AbnormalRecord>()
                        .eq(AbnormalRecord::getExamId, examId)
                        .orderByDesc(AbnormalRecord::getAbnormalTime)
                        .last("limit 10"));

        int abnormalCount = abnormalRecordMapper.selectCount(
                new LambdaQueryWrapper<AbnormalRecord>()
                        .eq(AbnormalRecord::getExamId, examId)).intValue();
        monitor.setAbnormalCount(abnormalCount);

        int seriousCount = abnormalRecordMapper.selectCount(
                new LambdaQueryWrapper<AbnormalRecord>()
                        .eq(AbnormalRecord::getExamId, examId)
                        .ge(AbnormalRecord::getSeverity, 3)).intValue();
        monitor.setSeriousAbnormalCount(seriousCount);

        List<AbnormalAlertVO> recentAbnormals = new ArrayList<>();
        for (AbnormalRecord ar : abnormals) {
            AbnormalAlertVO vo = new AbnormalAlertVO();
            vo.setId(ar.getId());
            vo.setExamId(ar.getExamId());
            vo.setExamRecordId(ar.getExamRecordId());
            vo.setUserId(ar.getUserId());
            vo.setAbnormalType(ar.getAbnormalType());
            vo.setAbnormalTypeText(getAbnormalTypeText(ar.getAbnormalType()));
            vo.setAbnormalDetail(ar.getAbnormalDetail());
            vo.setAbnormalTime(ar.getAbnormalTime());
            vo.setSeverity(ar.getSeverity());
            vo.setSeverityText(getSeverityText(ar.getSeverity()));
            vo.setHandled(ar.getHandled());

            User user = userMapper.selectById(ar.getUserId());
            if (user != null) {
                vo.setUserName(user.getRealName());
            }

            recentAbnormals.add(vo);
        }
        monitor.setRecentAbnormals(recentAbnormals);

        List<OnlineStudentVO> onlineStudents = new ArrayList<>();
        if (onlineUsers != null && !onlineUsers.isEmpty()) {
            List<Long> userIds = onlineUsers.stream()
                    .map(u -> Long.valueOf(u.toString()))
                    .collect(Collectors.toList());

            List<User> users = userMapper.selectBatchIds(userIds);
            for (User user : users) {
                OnlineStudentVO svo = new OnlineStudentVO();
                svo.setUserId(user.getId());
                svo.setUserName(user.getUsername());
                svo.setRealName(user.getRealName());
                svo.setAbnormalCount(0);

                ExamRecord record = examRecordMapper.selectOne(
                        new LambdaQueryWrapper<ExamRecord>()
                                .eq(ExamRecord::getExamId, examId)
                                .eq(ExamRecord::getUserId, user.getId())
                                .orderByDesc(ExamRecord::getCreateTime)
                                .last("limit 1"));

                if (record != null) {
                    List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(record.getId());
                    int answeredCount = 0;
                    for (ExamAnswer answer : answers) {
                        if (answer.getAnswerStatus() != null
                                && answer.getAnswerStatus().equals(Constants.ANSWER_STATUS_ANSWERED)) {
                            answeredCount++;
                        }
                    }
                    svo.setTotalQuestions(answers.size());
                    svo.setAnsweredCount(answeredCount);
                    if (answers.size() > 0) {
                        svo.setAnswerProgress(answeredCount * 100 / answers.size());
                    }
                    svo.setAbnormalCount(record.getAbnormalCount());
                }

                onlineStudents.add(svo);
            }
        }
        monitor.setOnlineStudents(onlineStudents);

        return monitor;
    }

    @Override
    public List<AbnormalAlertVO> getAbnormalAlertList(Long examId, Integer type, Integer severity,
                                                       int pageNum, int pageSize) {
        LambdaQueryWrapper<AbnormalRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(examId != null, AbnormalRecord::getExamId, examId);
        wrapper.eq(type != null, AbnormalRecord::getAbnormalType, type);
        wrapper.eq(severity != null, AbnormalRecord::getSeverity, severity);
        wrapper.orderByDesc(AbnormalRecord::getAbnormalTime);

        Page<AbnormalRecord> page = new Page<>(pageNum, pageSize);
        IPage<AbnormalRecord> pageResult = abnormalRecordMapper.selectPage(page, wrapper);

        List<AbnormalAlertVO> result = new ArrayList<>();
        for (AbnormalRecord ar : pageResult.getRecords()) {
            AbnormalAlertVO vo = new AbnormalAlertVO();
            vo.setId(ar.getId());
            vo.setExamId(ar.getExamId());
            vo.setExamRecordId(ar.getExamRecordId());
            vo.setUserId(ar.getUserId());
            vo.setAbnormalType(ar.getAbnormalType());
            vo.setAbnormalTypeText(getAbnormalTypeText(ar.getAbnormalType()));
            vo.setAbnormalDetail(ar.getAbnormalDetail());
            vo.setAbnormalTime(ar.getAbnormalTime());
            vo.setSeverity(ar.getSeverity());
            vo.setSeverityText(getSeverityText(ar.getSeverity()));
            vo.setHandled(ar.getHandled());
            vo.setHandleRemark(ar.getHandleRemark());
            vo.setHandleTime(ar.getHandleTime());

            User user = userMapper.selectById(ar.getUserId());
            if (user != null) {
                vo.setUserName(user.getRealName());
            }

            if (ar.getHandleBy() != null) {
                User handler = userMapper.selectById(ar.getHandleBy());
                if (handler != null) {
                    vo.setHandleBy(handler.getRealName());
                }
            }

            result.add(vo);
        }

        return result;
    }

    @Override
    public OnlineStatusVO getOnlineStatus(Long examId) {
        Exam exam = examMapper.selectById(examId);

        OnlineStatusVO vo = new OnlineStatusVO();
        vo.setExamId(examId);
        vo.setTotalCandidates(exam != null ? exam.getTotalCandidates() : 0);

        String onlineKey = Constants.REDIS_EXAM_ONLINE_PREFIX + examId;
        Set<Object> onlineUsers = redisTemplate.opsForSet().members(onlineKey);
        int onlineCount = onlineUsers != null ? onlineUsers.size() : 0;
        vo.setOnlineCount(onlineCount);

        vo.setOfflineCount(vo.getTotalCandidates() - onlineCount);

        if (vo.getTotalCandidates() > 0) {
            vo.setOnlineRate((double) onlineCount / vo.getTotalCandidates() * 100);
        }

        return vo;
    }

    @Override
    public SubmitProgressVO getSubmitProgress(Long examId) {
        Exam exam = examMapper.selectById(examId);

        SubmitProgressVO vo = new SubmitProgressVO();
        vo.setExamId(examId);
        vo.setTotalCandidates(exam != null ? exam.getTotalCandidates() : 0);
        vo.setSubmittedCount(exam != null ? exam.getSubmittedCount() : 0);
        vo.setUnsubmittedCount(vo.getTotalCandidates() - vo.getSubmittedCount());

        if (vo.getTotalCandidates() > 0) {
            vo.setSubmitRate((double) vo.getSubmittedCount() / vo.getTotalCandidates() * 100);
        }

        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>().eq(ExamRecord::getExamId, examId));

        int allGradedCount = 0;
        int objectiveGradedCount = 0;
        for (ExamRecord record : records) {
            if (record.getGradingStatus() != null
                    && record.getGradingStatus().equals(Constants.GRADING_STATUS_COMPLETED)) {
                allGradedCount++;
            }
            if (record.getGradingStatus() != null
                    && record.getGradingStatus() >= Constants.GRADING_STATUS_AUTO_GRADED) {
                objectiveGradedCount++;
            }
        }

        vo.setAllGradedCount(allGradedCount);
        vo.setObjectiveGradedCount(objectiveGradedCount);

        return vo;
    }

    @Override
    public void handleAbnormal(Long abnormalId, String handleRemark, Long handlerId) {
        AbnormalRecord abnormal = abnormalRecordMapper.selectById(abnormalId);
        if (abnormal != null) {
            abnormal.setHandled(1);
            abnormal.setHandleRemark(handleRemark);
            abnormal.setHandleBy(handlerId);
            abnormal.setHandleTime(LocalDateTime.now());
            abnormalRecordMapper.updateById(abnormal);

            log.info("异常行为处理完成: abnormalId={}, handlerId={}", abnormalId, handlerId);
        }
    }

    @Override
    public List<RealtimeExamVO> getRealtimeExamList() {
        List<Exam> exams = examMapper.selectList(
                new LambdaQueryWrapper<Exam>()
                        .eq(Exam::getExamStatus, Constants.EXAM_STATUS_IN_PROGRESS)
                        .orderByDesc(Exam::getStartTime));

        List<RealtimeExamVO> result = new ArrayList<>();
        for (Exam exam : exams) {
            RealtimeExamVO vo = new RealtimeExamVO();
            vo.setExamId(exam.getId());
            vo.setExamName(exam.getExamName());
            vo.setSubjectId(exam.getSubjectId());
            vo.setExamStatus(exam.getExamStatus());
            vo.setExamStatusText(getExamStatusText(exam.getExamStatus()));
            vo.setTotalCandidates(exam.getTotalCandidates());
            vo.setSubmittedCount(exam.getSubmittedCount());
            vo.setStartTime(exam.getStartTime());
            vo.setEndTime(exam.getEndTime());

            Subject subject = subjectMapper.selectById(exam.getSubjectId());
            if (subject != null) {
                vo.setSubjectName(subject.getSubjectName());
            }

            String onlineKey = Constants.REDIS_EXAM_ONLINE_PREFIX + exam.getId();
            Set<Object> onlineUsers = redisTemplate.opsForSet().members(onlineKey);
            vo.setOnlineCount(onlineUsers != null ? onlineUsers.size() : 0);

            int abnormalCount = abnormalRecordMapper.selectCount(
                    new LambdaQueryWrapper<AbnormalRecord>()
                            .eq(AbnormalRecord::getExamId, exam.getId())).intValue();
            vo.setAbnormalCount(abnormalCount);

            result.add(vo);
        }

        return result;
    }

    private String getAbnormalTypeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        switch (type) {
            case 1:
                return "切屏";
            case 2:
                return "断线";
            case 3:
                return "失焦";
            case 4:
                return "复制粘贴";
            default:
                return "未知";
        }
    }

    private String getSeverityText(Integer severity) {
        if (severity == null) {
            return "普通";
        }
        switch (severity) {
            case 1:
                return "轻微";
            case 2:
                return "一般";
            case 3:
                return "严重";
            default:
                return "普通";
        }
    }

    private String getExamStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0:
                return "未开始";
            case 1:
                return "进行中";
            case 2:
                return "已结束";
            case 3:
                return "阅卷中";
            case 4:
                return "已完成";
            default:
                return "未知";
        }
    }
}
