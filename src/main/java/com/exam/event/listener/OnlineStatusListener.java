package com.exam.event.listener;

import com.exam.common.Constants;
import com.exam.event.ExamEvent;
import com.exam.event.ExamEventListener;
import com.exam.event.ExamEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineStatusListener implements ExamEventListener {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String ONLINE_PREFIX = "exam:online:";

    @Override
    public String getName() {
        return "ONLINE_STATUS_LISTENER";
    }

    @Override
    public boolean supports(String eventType) {
        return ExamEventType.EXAM_START.equals(eventType)
                || ExamEventType.HEARTBEAT.equals(eventType)
                || ExamEventType.SESSION_RECONNECT.equals(eventType)
                || ExamEventType.EXAM_SUBMIT.equals(eventType)
                || ExamEventType.SESSION_DISCONNECT.equals(eventType);
    }

    @Override
    public void onEvent(ExamEvent event) {
        Long examId = event.getExamId();
        Long studentId = event.getStudentId();
        Long sessionId = event.getSessionId();

        if (examId == null || studentId == null) {
            return;
        }

        String eventType = event.getEventType();
        String key = ONLINE_PREFIX + examId;

        try {
            switch (eventType) {
                case ExamEventType.EXAM_START:
                case ExamEventType.HEARTBEAT:
                case ExamEventType.SESSION_RECONNECT:
                    markOnline(key, studentId, sessionId);
                    break;
                case ExamEventType.EXAM_SUBMIT:
                case ExamEventType.SESSION_DISCONNECT:
                    markOffline(key, studentId);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("更新在线状态失败: eventType={}, examId={}, studentId={}",
                    eventType, examId, studentId, e);
        }
    }

    private void markOnline(String key, Long studentId, Long sessionId) {
        Map<String, Object> info = new HashMap<>();
        info.put("studentId", studentId);
        info.put("sessionId", sessionId);
        info.put("onlineTime", System.currentTimeMillis());
        redisTemplate.opsForHash().put(key, String.valueOf(studentId), info);
        redisTemplate.expire(key, 2, TimeUnit.HOURS);
    }

    private void markOffline(String key, Long studentId) {
        redisTemplate.opsForHash().delete(key, String.valueOf(studentId));
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
