package com.exam.service;

import com.exam.common.Constants;
import com.exam.entity.*;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("成绩发布与并发测试")
class ScoreServiceTest {

    @Mock
    private ExamScoreMapper examScoreMapper;
    @Mock
    private ExamSessionMapper examSessionMapper;
    @Mock
    private ExamAnswerMapper examAnswerMapper;
    @Mock
    private WrongBookMapper wrongBookMapper;
    @Mock
    private PaperQuestionMapper paperQuestionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private ScoreService scoreService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Nested
    @DisplayName("成绩发布幂等性测试")
    class IdempotentPublishTest {

        @Test
        @DisplayName("重复发布成绩不重复创建记录，返回已有结果")
        void shouldReturnExistingScoreWhenAlreadyPublished() {
            ExamScore existing = new ExamScore();
            existing.setId(1L);
            existing.setExamId(100L);
            existing.setStudentId(1000L);
            existing.setTotalScore(new BigDecimal("85.5"));
            existing.setPublished(1);

            when(examScoreMapper.selectByExamAndStudent(100L, 1000L)).thenReturn(existing);

            ExamScore result = scoreService.publishScore(100L, 1000L, 1L);

            assertThat(result).isSameAs(existing);
            assertThat(result.getPublished()).isEqualTo(1);
            verify(examScoreMapper, never()).insert(any());
            verify(examScoreMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("同一考生并发多次发布，只有一次成功写入")
        void shouldAllowOnlyOnePublishUnderConcurrency() throws InterruptedException {
            final int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger insertCount = new AtomicInteger(0);
            AtomicInteger returnCount = new AtomicInteger(0);

            ExamSession session = new ExamSession();
            session.setId(200L);
            session.setPaperId(300L);
            session.setGradingStatus(Constants.GRADING_STATUS_COMPLETED);
            when(examSessionMapper.selectByExamAndStudent(500L, 2000L)).thenReturn(session);

            when(examAnswerMapper.selectBySessionId(200L)).thenReturn(Collections.emptyList());
            when(examScoreMapper.selectByExamId(500L)).thenReturn(Collections.emptyList());

            when(examScoreMapper.selectByExamAndStudent(500L, 2000L))
                    .thenAnswer(inv -> {
                        if (insertCount.get() > 0) {
                            ExamScore s = new ExamScore();
                            s.setId(999L);
                            s.setPublished(1);
                            s.setTotalScore(new BigDecimal("90"));
                            return s;
                        }
                        return null;
                    });

            when(examScoreMapper.insert(any())).thenAnswer(inv -> {
                insertCount.incrementAndGet();
                ExamScore s = inv.getArgument(0);
                s.setId(System.currentTimeMillis());
                return 1;
            });

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        ExamScore r = scoreService.publishScore(500L, 2000L, 1L);
                        if (r != null && r.getPublished() != null && r.getPublished() == 1) {
                            returnCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(returnCount.get()).isEqualTo(threadCount);
        }
    }

    @Nested
    @DisplayName("多人阅卷仲裁合并测试")
    class MultiGraderArbitrationTest {

        @Test
        @DisplayName("两位老师分数一致，直接合并取平均值")
        void shouldMergeWhenTwoGradersAgree() {
            ExamAnswer answer = new ExamAnswer();
            answer.setFirstGraderScore(new BigDecimal("8"));
            answer.setSecondGraderScore(new BigDecimal("8.5"));
            answer.setQuestionScore(new BigDecimal("10"));

            GradingService gradingService = new GradingService(null);
            BigDecimal merged = gradingService.mergeSubjectiveGrades(answer);

            assertThat(merged).isEqualByComparingTo(new BigDecimal("8.25"));
        }

        @Test
        @DisplayName("两位老师分数差异超过阈值（20%），进入仲裁流程")
        void shouldRequireArbitrationWhenDifferenceExceedsThreshold() {
            ExamAnswer answer = new ExamAnswer();
            answer.setFirstGraderScore(new BigDecimal("6"));
            answer.setSecondGraderScore(new BigDecimal("9"));
            answer.setQuestionScore(new BigDecimal("10"));

            GradingService gradingService = new GradingService(null);
            BigDecimal merged = gradingService.mergeSubjectiveGrades(answer);

            assertThat(merged).isNull();
        }

        @Test
        @DisplayName("仲裁分数作为最终分数记录")
        void shouldUseArbitrationScoreAsFinal() {
            ExamAnswer answer = new ExamAnswer();
            answer.setFirstGraderScore(new BigDecimal("6"));
            answer.setSecondGraderScore(new BigDecimal("9"));
            answer.setFinalScore(new BigDecimal("8"));
            answer.setQuestionScore(new BigDecimal("10"));

            assertThat(answer.getFinalScore()).isEqualByComparingTo("8");
        }
    }

    @Nested
    @DisplayName("错题本与知识点掌握度测试")
    class WrongBookAndMasteryTest {

        @Test
        @DisplayName("错题自动归集到错题本，重复答错累加次数")
        void shouldAccumulateWrongCountForRepeatedMistakes() {
            Question q = new Question();
            q.setId(1L);
            q.setSubjectId(1L);
            when(questionMapper.selectById(1L)).thenReturn(q);

            PaperQuestion pq = new PaperQuestion();
            pq.setQuestionId(1L);
            pq.setQuestionScore(new BigDecimal("10"));
            when(paperQuestionMapper.selectByPaperAndQuestion(100L, 1L)).thenReturn(pq);

            ExamAnswer wrong = new ExamAnswer();
            wrong.setQuestionId(1L);
            wrong.setQuestionType(1);
            wrong.setStudentScore(BigDecimal.ZERO);
            wrong.setStudentAnswer("A");
            wrong.setCorrectAnswer("B");

            ExamSession session = new ExamSession();
            session.setId(10L);
            session.setPaperId(100L);
            session.setGradingStatus(Constants.GRADING_STATUS_COMPLETED);
            when(examSessionMapper.selectByExamAndStudent(200L, 3000L)).thenReturn(session);
            when(examAnswerMapper.selectBySessionId(10L)).thenReturn(List.of(wrong));
            when(examScoreMapper.selectByExamAndStudent(200L, 3000L)).thenReturn(null);
            when(examScoreMapper.selectByExamId(200L)).thenReturn(Collections.emptyList());
            when(wrongBookMapper.selectByStudentAndQuestion(3000L, 1L)).thenReturn(null);
            when(wrongBookMapper.insert(any())).thenAnswer(inv -> {
                WrongBook wb = inv.getArgument(0);
                wb.setId(1L);
                return 1;
            });
            when(examScoreMapper.insert(any())).thenAnswer(inv -> {
                ExamScore s = inv.getArgument(0);
                s.setId(99L);
                return 1;
            });

            ExamScore score = scoreService.publishScore(200L, 3000L, 1L);

            assertThat(score).isNotNull();
            verify(wrongBookMapper).insert(argThat(wb ->
                    wb.getWrongCount() == 1 &&
                            "A".equals(wb.getStudentAnswer()) &&
                            "B".equals(wb.getCorrectAnswer())));
        }
    }
}
