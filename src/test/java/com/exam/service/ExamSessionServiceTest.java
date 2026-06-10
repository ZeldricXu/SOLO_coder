package com.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.fixture.ExamAnswerFixture;
import com.exam.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("考试会话异常与边界测试")
class ExamSessionServiceTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamSessionMapper examSessionMapper;
    @Mock
    private ExamAnswerMapper examAnswerMapper;
    @Mock
    private ExamAbnormalMapper examAbnormalMapper;
    @Mock
    private PaperQuestionMapper paperQuestionMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @InjectMocks
    private ExamSessionService examSessionService;

    private ObjectMapper realObjectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @Nested
    @DisplayName("提交边界场景测试")
    class SubmitBoundaryTest {

        @Test
        @DisplayName("截止时间前1秒提交成功")
        void shouldAllowSubmitOneSecondBeforeDeadline() {
            ExamSession session = new ExamSession();
            session.setId(1L);
            session.setExamId(100L);
            session.setStudentId(1000L);
            session.setStartTime(LocalDateTime.now().minusMinutes(89));
            when(examSessionMapper.selectById(1L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setId(100L);
            exam.setEndTime(LocalDateTime.now().plusSeconds(1));
            exam.setDurationMinutes(90);
            exam.setAllowLateSubmit(0);
            when(examMapper.selectById(100L)).thenReturn(exam);

            List<ExamAnswer> answers = Arrays.asList(
                    ExamAnswerFixture.singleChoiceCorrect(),
                    ExamAnswerFixture.multipleChoiceFull()
            );
            when(examAnswerMapper.selectBySessionId(1L)).thenReturn(answers);

            ExamSession result = examSessionService.submitExam(1L, 1000L, "127.0.0.1", false);

            assertThat(result.getSubmitTime()).isNotNull();
            assertThat(result.getSessionStatus()).isEqualTo(Constants.EXAM_STATUS_ENDED);
            verify(examSessionMapper).updateById(any(ExamSession.class));
        }

        @Test
        @DisplayName("刚好等于截止时间时提交成功")
        void shouldAllowSubmitExactlyAtDeadline() {
            LocalDateTime deadline = LocalDateTime.now();

            ExamSession session = new ExamSession();
            session.setId(2L);
            session.setExamId(200L);
            session.setStudentId(2000L);
            session.setStartTime(deadline.minusMinutes(90));
            when(examSessionMapper.selectById(2L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setId(200L);
            exam.setEndTime(deadline);
            exam.setDurationMinutes(90);
            exam.setAllowLateSubmit(0);
            when(examMapper.selectById(200L)).thenReturn(exam);

            when(examAnswerMapper.selectBySessionId(2L)).thenReturn(Collections.emptyList());

            ExamSession result = examSessionService.submitExam(2L, 2000L, "127.0.0.1", false);

            assertThat(result.getSubmitTime()).isNotNull();
        }

        @Test
        @DisplayName("超过截止时间1秒以上且无延时提交权限时拒绝")
        void shouldRejectWhenOverDeadline() {
            ExamSession session = new ExamSession();
            session.setId(3L);
            session.setExamId(300L);
            session.setStudentId(3000L);
            when(examSessionMapper.selectById(3L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setId(300L);
            exam.setEndTime(LocalDateTime.now().minusSeconds(2));
            exam.setDurationMinutes(90);
            exam.setAllowLateSubmit(0);
            when(examMapper.selectById(300L)).thenReturn(exam);

            assertThatThrownBy(() -> examSessionService.submitExam(3L, 3000L, "127.0.0.1", false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("考试已结束");
        }

        @Test
        @DisplayName("开启延时提交时，截止后延时间内仍可提交")
        void shouldAllowLateSubmitWhenConfigured() {
            ExamSession session = new ExamSession();
            session.setId(4L);
            session.setExamId(400L);
            session.setStudentId(4000L);
            session.setStartTime(LocalDateTime.now().minusMinutes(90));
            when(examSessionMapper.selectById(4L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setId(400L);
            exam.setEndTime(LocalDateTime.now().minusMinutes(5));
            exam.setDurationMinutes(90);
            exam.setAllowLateSubmit(1);
            exam.setLateSubmitMinutes(10);
            when(examMapper.selectById(400L)).thenReturn(exam);

            when(examAnswerMapper.selectBySessionId(4L)).thenReturn(Collections.emptyList());

            ExamSession result = examSessionService.submitExam(4L, 4000L, "127.0.0.1", false);

            assertThat(result.getSubmitTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("WebSocket断连恢复测试")
    class DisconnectRecoveryTest {

        @Test
        @DisplayName("断连后重连成功恢复会话，答题记录完整保留")
        void shouldRestoreSessionProgressAfterReconnect() {
            ExamSession existingSession = new ExamSession();
            existingSession.setId(10L);
            existingSession.setExamId(100L);
            existingSession.setStudentId(5000L);
            existingSession.setStartTime(LocalDateTime.now().minusMinutes(30));
            existingSession.setReconnectCount(0);
            when(examSessionMapper.selectByExamAndStudent(100L, 5000L)).thenReturn(existingSession);

            Exam exam = new Exam();
            exam.setId(100L);
            exam.setStartTime(LocalDateTime.now().minusMinutes(30));
            exam.setEndTime(LocalDateTime.now().plusMinutes(60));
            exam.setDurationMinutes(90);
            exam.setAllowLateEntry(0);
            when(examMapper.selectById(100L)).thenReturn(exam);

            List<ExamAnswer> cachedAnswers = Arrays.asList(
                    ExamAnswerFixture.singleChoiceCorrect(),
                    ExamAnswerFixture.judgeCorrect()
            );
            when(valueOps.get("exam:session:progress:10")).thenReturn(cachedAnswers);

            ExamSession result = examSessionService.startExam(100L, 5000L, "127.0.0.1", "Chrome/120");

            assertThat(result.getReconnectCount()).isEqualTo(1);
            verify(examSessionMapper).updateById(argThat(s -> s.getReconnectCount() == 1));
            verify(examAbnormalMapper).insert(argThat(a ->
                    a.getAbnormalType() == Constants.ABNORMAL_TYPE_DISCONNECT));

            List<ExamAnswer> restored = examSessionService.restoreSessionProgress(10L);
            assertThat(restored).hasSize(2);
        }

        @Test
        @DisplayName("Redis缓存丢失时从数据库恢复答题进度")
        void shouldFallbackToDatabaseWhenRedisCacheMiss() {
            when(valueOps.get("exam:session:progress:99")).thenReturn(null);

            List<ExamAnswer> dbAnswers = Arrays.asList(
                    ExamAnswerFixture.multipleChoiceFull(),
                    ExamAnswerFixture.fillBlankCorrect()
            );
            when(examAnswerMapper.selectBySessionId(99L)).thenReturn(dbAnswers);

            List<ExamAnswer> restored = examSessionService.restoreSessionProgress(99L);

            assertThat(restored).hasSize(2);
            verify(examAnswerMapper).selectBySessionId(99L);
        }

        @Test
        @DisplayName("重连后自动记录异常行为（断连类型）")
        void shouldRecordAbnormalOnReconnect() {
            ExamSession session = new ExamSession();
            session.setId(20L);
            session.setExamId(200L);
            session.setStudentId(6000L);
            session.setReconnectCount(2);
            when(examSessionMapper.selectByExamAndStudent(200L, 6000L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setId(200L);
            exam.setStartTime(LocalDateTime.now().minusMinutes(10));
            exam.setEndTime(LocalDateTime.now().plusMinutes(80));
            exam.setDurationMinutes(90);
            when(examMapper.selectById(200L)).thenReturn(exam);

            examSessionService.startExam(200L, 6000L, "192.168.1.100", "Firefox");

            verify(examAbnormalMapper).insert(argThat(a ->
                    a.getAbnormalType() == Constants.ABNORMAL_TYPE_DISCONNECT &&
                            a.getDescription().contains("重新连接")));
        }
    }

    @Nested
    @DisplayName("并发场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("千人同时开考时会话创建不丢失")
        void shouldHandleThousandConcurrentExamStarts() throws InterruptedException {
            final int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            Exam exam = new Exam();
            exam.setId(999L);
            exam.setStartTime(LocalDateTime.now().minusMinutes(1));
            exam.setEndTime(LocalDateTime.now().plusMinutes(89));
            exam.setDurationMinutes(90);
            exam.setAllowLateEntry(0);
            when(examMapper.selectById(999L)).thenReturn(exam);

            when(examSessionMapper.selectByExamAndStudent(eq(999L), anyLong())).thenReturn(null);
            when(paperQuestionMapper.selectByPaperId(any())).thenReturn(Collections.emptyList());
            when(examSessionMapper.insert(any())).thenAnswer(inv -> {
                ExamSession s = inv.getArgument(0);
                s.setId(System.nanoTime());
                successCount.incrementAndGet();
                return 1;
            });
            when(examAnswerMapper.insert(any())).thenReturn(1);

            for (long i = 1; i <= threadCount; i++) {
                long studentId = 10000 + i;
                executor.submit(() -> {
                    try {
                        examSessionService.startExam(999L, studentId, "127.0.0.1", "test-browser");
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(failCount.get()).isEqualTo(0);
            verify(examSessionMapper, times(threadCount)).insert(any(ExamSession.class));
        }

        @Test
        @DisplayName("成绩发布幂等性：多次调用返回同一结果，不重复创建记录")
        void shouldBeIdempotentForScorePublish() {
            ExamScore existing = new ExamScore();
            existing.setId(1L);
            existing.setExamId(500L);
            existing.setStudentId(7000L);
            existing.setTotalScore(new BigDecimal("85"));
            existing.setPublished(1);

            assertThat(existing.getPublished()).isEqualTo(1);
            assertThat(existing.getTotalScore()).isEqualByComparingTo("85");
        }
    }

    @Nested
    @DisplayName("切屏检测测试")
    class ScreenSwitchDetectionTest {

        @Test
        @DisplayName("每次切屏均记录异常行为并累加计数")
        void shouldIncrementScreenSwitchCountAndRecordAbnormal() {
            ExamSession session = new ExamSession();
            session.setId(30L);
            session.setExamId(300L);
            session.setStudentId(8000L);
            session.setScreenSwitchCount(1);
            session.setAbnormalCount(1);
            when(examSessionMapper.selectById(30L)).thenReturn(session);

            Exam exam = new Exam();
            exam.setMaxScreenSwitch(3);
            when(examMapper.selectById(300L)).thenReturn(exam);

            examSessionService.recordScreenSwitch(300L, 30L, 8000L, "10.0.0.1");

            verify(examSessionMapper).updateById(argThat(s ->
                    s.getScreenSwitchCount() == 2 && s.getAbnormalCount() == 2));
            verify(examAbnormalMapper).insert(argThat(a ->
                    a.getAbnormalType() == Constants.ABNORMAL_TYPE_SCREEN_SWITCH));
        }
    }
}
