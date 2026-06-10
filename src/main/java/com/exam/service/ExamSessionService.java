package com.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.entity.*;
import com.exam.event.ExamEvent;
import com.exam.event.ExamEventPublisher;
import com.exam.event.ExamEventType;
import com.exam.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ExamSessionService {

    private final ExamMapper examMapper;
    private final ExamSessionMapper examSessionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamAbnormalMapper examAbnormalMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExamEventPublisher eventPublisher;

    public ExamSessionService(ExamMapper examMapper, ExamSessionMapper examSessionMapper,
                              ExamAnswerMapper examAnswerMapper, ExamAbnormalMapper examAbnormalMapper,
                              PaperQuestionMapper paperQuestionMapper,
                              RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this(examMapper, examSessionMapper, examAnswerMapper, examAbnormalMapper, paperQuestionMapper,
                redisTemplate, objectMapper, null);
    }

    public ExamSessionService(ExamMapper examMapper, ExamSessionMapper examSessionMapper,
                              ExamAnswerMapper examAnswerMapper, ExamAbnormalMapper examAbnormalMapper,
                              PaperQuestionMapper paperQuestionMapper,
                              RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper,
                              ExamEventPublisher eventPublisher) {
        this.examMapper = examMapper;
        this.examSessionMapper = examSessionMapper;
        this.examAnswerMapper = examAnswerMapper;
        this.examAbnormalMapper = examAbnormalMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    private static final String SESSION_CACHE_PREFIX = "exam:session:progress:";
    private static final String ONLINE_PREFIX = "exam:online:";
    private static final int PROGRESS_CACHE_SECONDS = 3600;

    @Transactional
    public ExamSession startExam(Long examId, Long studentId, String ip, String deviceInfo) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }

        ExamSession existing = examSessionMapper.selectByExamAndStudent(examId, studentId);
        if (existing != null && existing.getSubmitTime() != null) {
            throw new BusinessException(ResultCode.EXAM_ALREADY_SUBMITTED);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(exam.getStartTime())) {
            if (exam.getAllowLateEntry() == null || exam.getAllowLateEntry() == 0
                    || now.isBefore(exam.getStartTime().minusMinutes(exam.getLateEntryMinutes() != null ? exam.getLateEntryMinutes() : 0))) {
                throw new BusinessException(ResultCode.EXAM_NOT_STARTED);
            }
        }

        if (existing != null) {
            existing.setReconnectCount(existing.getReconnectCount() == null ? 1 : existing.getReconnectCount() + 1);
            existing.setLastHeartbeat(now);
            existing.setDeviceInfo(deviceInfo);
            examSessionMapper.updateById(existing);

            publishEvent(buildEvent(ExamEventType.SESSION_RECONNECT, examId, existing.getId(), studentId, ip));

            recordAbnormal(examId, existing.getId(), studentId,
                    Constants.ABNORMAL_TYPE_DISCONNECT, "重新连接恢复考试", ip);
            return existing;
        }

        ExamSession session = new ExamSession();
        session.setExamId(examId);
        session.setPaperId(exam.getPaperId());
        session.setStudentId(studentId);
        session.setAbType(Math.random() > 0.5 ? 1 : 2);
        session.setSessionStatus(Constants.EXAM_STATUS_IN_PROGRESS);
        session.setStartTime(now);
        session.setScreenSwitchCount(0);
        session.setAbnormalCount(0);
        session.setGradingStatus(Constants.GRADING_STATUS_PENDING);
        session.setSubmitIp(ip);
        session.setDeviceInfo(deviceInfo);
        session.setLastHeartbeat(now);
        session.setReconnectCount(0);
        examSessionMapper.insert(session);

        List<PaperQuestion> questions = paperQuestionMapper.selectByPaperId(exam.getPaperId());
        for (PaperQuestion pq : questions) {
            ExamAnswer answer = new ExamAnswer();
            answer.setSessionId(session.getId());
            answer.setExamId(examId);
            answer.setPaperId(exam.getPaperId());
            answer.setQuestionId(pq.getQuestionId());
            answer.setStudentId(studentId);
            answer.setQuestionOrder(pq.getQuestionOrder());
            answer.setQuestionType(pq.getQuestionType());
            answer.setQuestionScore(pq.getQuestionScore());
            answer.setAnswerStatus(Constants.ANSWER_STATUS_NOT_ANSWERED);
            answer.setGradingStatus(Constants.GRADING_STATUS_PENDING);
            examAnswerMapper.insert(answer);
        }

        publishEvent(buildEvent(ExamEventType.EXAM_START, examId, session.getId(), studentId, ip));

        return session;
    }

    public void saveAnswerProgress(Long sessionId, Long questionId, String answer, Long studentId) {
        ExamSession session = examSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("考试会话不存在");
        }

        ExamAnswer examAnswer = examAnswerMapper.selectBySessionAndQuestion(sessionId, questionId);
        if (examAnswer == null) {
            throw new BusinessException("答题记录不存在");
        }

        if (!examAnswer.getStudentId().equals(studentId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        examAnswer.setStudentAnswer(answer);
        examAnswer.setAnswerStatus(answer == null || answer.trim().isEmpty()
                ? Constants.ANSWER_STATUS_NOT_ANSWERED : Constants.ANSWER_STATUS_ANSWERED);
        examAnswer.setLastSaveTime(LocalDateTime.now());
        examAnswerMapper.updateById(examAnswer);

        ExamEvent event = buildEvent(ExamEventType.ANSWER_SAVE,
                session.getExamId(), sessionId, studentId, null);
        event.setQuestionId(questionId);
        event.setAnswer(answer);
        event.addExtra("answerStatus", examAnswer.getAnswerStatus());
        publishEvent(event);
    }

    @Transactional
    public ExamSession submitExam(Long sessionId, Long studentId, String ip, boolean forceAutoSubmit) {
        ExamSession session = examSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("考试会话不存在");
        }
        if (session.getSubmitTime() != null && !forceAutoSubmit) {
            throw new BusinessException(ResultCode.EXAM_ALREADY_SUBMITTED);
        }

        Exam exam = examMapper.selectById(session.getExamId());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = exam.getEndTime();

        if (exam.getAllowLateSubmit() != null && exam.getAllowLateSubmit() == 1
                && exam.getLateSubmitMinutes() != null) {
            deadline = deadline.plusMinutes(exam.getLateSubmitMinutes());
        }

        if (now.isAfter(deadline.plusSeconds(1)) && !forceAutoSubmit) {
            throw new BusinessException(ResultCode.EXAM_ENDED);
        }

        if (!forceAutoSubmit && now.plusSeconds(1).isAfter(deadline)) {
            log.info("考试提交处于边界时间，sessionId={}, now={}, deadline={}", sessionId, now, deadline);
        }

        List<ExamAnswer> answers = examAnswerMapper.selectBySessionId(sessionId);
        cacheAllAnswers(sessionId, answers);

        int usedSeconds = (int) ChronoUnit.SECONDS.between(session.getStartTime(), now);
        int maxSeconds = exam.getDurationMinutes() * 60;
        usedSeconds = Math.min(usedSeconds, maxSeconds);

        session.setSessionStatus(Constants.EXAM_STATUS_ENDED);
        session.setSubmitTime(now);
        session.setUsedSeconds(usedSeconds);
        session.setGradingStatus(Constants.GRADING_STATUS_AUTO_GRADED);
        examSessionMapper.updateById(session);

        ExamEvent event = buildEvent(ExamEventType.EXAM_SUBMIT,
                session.getExamId(), sessionId, studentId, ip);
        event.addExtra("usedSeconds", usedSeconds);
        event.addExtra("forceAutoSubmit", forceAutoSubmit);
        event.addExtra("answerCount", answers.size());
        publishEvent(event);

        return session;
    }

    public Map<String, Object> getRemainingTime(Long sessionId) {
        ExamSession session = examSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("考试会话不存在");
        }
        Exam exam = examMapper.selectById(session.getExamId());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = session.getStartTime().plusMinutes(exam.getDurationMinutes());

        if (exam.getEndTime().isBefore(endTime)) {
            endTime = exam.getEndTime();
        }

        long remaining = ChronoUnit.SECONDS.between(now, endTime);
        remaining = Math.max(0, remaining);

        Map<String, Object> result = new HashMap<>();
        result.put("remainingSeconds", remaining);
        result.put("serverTime", now.toString());
        result.put("endTime", endTime.toString());
        return result;
    }

    public void recordScreenSwitch(Long examId, Long sessionId, Long studentId, String ip) {
        ExamSession session = examSessionMapper.selectById(sessionId);
        if (session == null) return;

        int count = (session.getScreenSwitchCount() == null ? 0 : session.getScreenSwitchCount()) + 1;
        session.setScreenSwitchCount(count);
        session.setAbnormalCount((session.getAbnormalCount() == null ? 0 : session.getAbnormalCount()) + 1);
        examSessionMapper.updateById(session);

        Exam exam = examMapper.selectById(examId);
        int threshold = exam.getMaxScreenSwitch() != null ? exam.getMaxScreenSwitch() : 3;

        String desc = String.format("第%d次检测到切屏行为", count);
        recordAbnormal(examId, sessionId, studentId, Constants.ABNORMAL_TYPE_SCREEN_SWITCH, desc, ip);

        ExamEvent event = buildEvent(ExamEventType.SCREEN_SWITCH, examId, sessionId, studentId, ip);
        event.addExtra("screenSwitchCount", count);
        event.addExtra("threshold", threshold);
        event.addExtra("description", desc);
        publishEvent(event);

        if (count >= threshold) {
            log.warn("考生切屏超过阈值，examId={}, studentId={}, count={}", examId, studentId, count);
        }
    }

    public void recordAbnormal(Long examId, Long sessionId, Long studentId,
                               Integer abnormalType, String description, String ip) {
        ExamAbnormal abnormal = new ExamAbnormal();
        abnormal.setExamId(examId);
        abnormal.setSessionId(sessionId);
        abnormal.setStudentId(studentId);
        abnormal.setAbnormalType(abnormalType);
        abnormal.setAbnormalName(getAbnormalTypeName(abnormalType));
        abnormal.setDescription(description);
        abnormal.setHappenTime(LocalDateTime.now());
        abnormal.setClientIp(ip);
        abnormal.setHandled(0);
        examAbnormalMapper.insert(abnormal);
    }

    private String getAbnormalTypeName(Integer type) {
        return switch (type) {
            case 1 -> "切屏";
            case 2 -> "异常断连";
            case 3 -> "窗口失焦";
            case 4 -> "复制粘贴";
            default -> "其他异常";
        };
    }

    public void heartbeat(Long examId, Long sessionId, Long studentId) {
        ExamSession session = examSessionMapper.selectById(sessionId);
        if (session != null) {
            session.setLastHeartbeat(LocalDateTime.now());
            examSessionMapper.updateById(session);
        }

        ExamEvent event = buildEvent(ExamEventType.HEARTBEAT, examId, sessionId, studentId, null);
        publishEvent(event);
    }

    public List<ExamAnswer> restoreSessionProgress(Long sessionId) {
        String cacheKey = SESSION_CACHE_PREFIX + sessionId;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                @SuppressWarnings("unchecked")
                List<ExamAnswer> answers = (List<ExamAnswer>) cached;
                log.info("从Redis恢复答题进度，sessionId={}, 答题数={}", sessionId, answers.size());
                return answers;
            }
        } catch (Exception e) {
            log.error("从缓存恢复进度失败", e);
        }
        return examAnswerMapper.selectBySessionId(sessionId);
    }

    private void cacheAllAnswers(Long sessionId, List<ExamAnswer> answers) {
        String cacheKey = SESSION_CACHE_PREFIX + sessionId;
        redisTemplate.opsForValue().set(cacheKey, answers, PROGRESS_CACHE_SECONDS, TimeUnit.SECONDS);
    }

    public int getOnlineCount(Long examId) {
        String key = ONLINE_PREFIX + examId;
        Long size = redisTemplate.opsForHash().size(key);
        return size != null ? size.intValue() : 0;
    }

    public void refreshHeartbeatTimeouts() {
        Set<String> keys = redisTemplate.keys(ONLINE_PREFIX + "*");
        if (keys == null) return;

        long now = System.currentTimeMillis();
        for (String key : keys) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> info = (Map<String, Object>) entry.getValue();
                Long onlineTime = ((Number) info.get("onlineTime")).longValue();
                if (now - onlineTime > Constants.EXAM_HEARTBEAT_TIMEOUT) {
                    redisTemplate.opsForHash().delete(key, entry.getKey());
                    log.info("超时下线，examKey={}, studentId={}", key, entry.getKey());
                }
            }
        }
    }

    private ExamEvent buildEvent(String type, Long examId, Long sessionId, Long studentId, String ip) {
        ExamEvent event = new ExamEvent(type);
        event.setExamId(examId);
        event.setSessionId(sessionId);
        event.setStudentId(studentId);
        event.setClientIp(ip);
        return event;
    }

    private void publishEvent(ExamEvent event) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.warn("发布考试事件失败: type={}, examId={}", event.getEventType(), event.getExamId(), e);
        }
    }
}
