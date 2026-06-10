package com.exam.event.listener;

import com.exam.entity.ExamAnswer;
import com.exam.event.ExamEvent;
import com.exam.event.ExamEventListener;
import com.exam.event.ExamEventType;
import com.exam.mapper.ExamAnswerMapper;
import com.exam.mapper.ExamSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerProgressListener implements ExamEventListener {

    private final ExamAnswerMapper examAnswerMapper;
    private final ExamSessionMapper examSessionMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SESSION_CACHE_PREFIX = "exam:session:progress:";
    private static final int PROGRESS_CACHE_SECONDS = 3600;

    @Override
    public String getName() {
        return "ANSWER_PROGRESS_LISTENER";
    }

    @Override
    public boolean supports(String eventType) {
        return ExamEventType.ANSWER_SAVE.equals(eventType)
                || ExamEventType.ANSWER_CHANGE.equals(eventType)
                || ExamEventType.EXAM_SUBMIT.equals(eventType);
    }

    @Override
    public void onEvent(ExamEvent event) {
        Long sessionId = event.getSessionId();
        Long questionId = event.getQuestionId();
        String answer = event.getAnswer();

        if (sessionId == null || questionId == null) {
            return;
        }

        try {
            ExamAnswer examAnswer = examAnswerMapper.selectBySessionAndQuestion(sessionId, questionId);
            if (examAnswer != null) {
                examAnswer.setStudentAnswer(answer);
                examAnswer.setLastSaveTime(LocalDateTime.now());
                examAnswerMapper.updateById(examAnswer);
                log.debug("答题进度已保存: sessionId={}, questionId={}", sessionId, questionId);
            }

            cacheSessionProgress(sessionId);
        } catch (Exception e) {
            log.error("保存答题进度失败: sessionId={}, questionId={}", sessionId, questionId, e);
        }
    }

    private void cacheSessionProgress(Long sessionId) {
        try {
            var answers = examAnswerMapper.selectBySessionId(sessionId);
            String cacheKey = SESSION_CACHE_PREFIX + sessionId;
            redisTemplate.opsForValue().set(cacheKey, answers, PROGRESS_CACHE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("缓存答题进度失败: sessionId={}", sessionId, e);
        }
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
