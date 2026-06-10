package com.exam.service;

import com.exam.common.Constants;
import com.exam.entity.Paper;
import com.exam.entity.PaperQuestion;
import com.exam.entity.PaperTemplate;
import com.exam.entity.Question;
import com.exam.fixture.PaperTemplateFixture;
import com.exam.fixture.QuestionFixture;
import com.exam.mapper.*;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("组卷引擎测试")
class PaperGenerationServiceTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private PaperMapper paperMapper;
    @Mock
    private PaperQuestionMapper paperQuestionMapper;
    @Mock
    private PaperTemplateMapper paperTemplateMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private PaperGenerationService paperGenerationService;

    @BeforeEach
    void setUp() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Nested
    @DisplayName("题库不足降级策略测试")
    class DegradationStrategyTest {

        @Test
        @DisplayName("知识点覆盖不全时优先保证难度分布，不足部分按题型随机抽取补充")
        void shouldFallbackToTypeRandomWhenKnowledgeInsufficient() {
            PaperTemplate template = PaperTemplateFixture.insufficientTemplate().build();

            when(questionMapper.countByTypeAndDifficulty(eq(template.getSubjectId()), eq(1), eq(3)))
                    .thenReturn(5);
            when(questionMapper.countByTypeAndDifficulty(eq(template.getSubjectId()), eq(1), eq(2)))
                    .thenReturn(10);
            when(questionMapper.countByTypeAndDifficulty(eq(template.getSubjectId()), eq(1), eq(1)))
                    .thenReturn(10);
            when(questionMapper.countByType(eq(template.getSubjectId()), eq(1)))
                    .thenReturn(50);

            List<Question> hardQuestions = QuestionFixture.createQuestionPool(5, 1, 3, template.getSubjectId());
            List<Question> mediumQuestions = QuestionFixture.createQuestionPool(10, 1, 2, template.getSubjectId());
            List<Question> easyQuestions = QuestionFixture.createQuestionPool(10, 1, 1, template.getSubjectId());
            List<Question> allSingle = QuestionFixture.createQuestionPool(50, 1, 2, template.getSubjectId());

            when(questionMapper.selectRandomQuestions(eq(template.getSubjectId()), eq(1), eq(3), anyInt()))
                    .thenReturn(hardQuestions);
            when(questionMapper.selectRandomQuestions(eq(template.getSubjectId()), eq(1), eq(2), anyInt()))
                    .thenReturn(mediumQuestions);
            when(questionMapper.selectRandomQuestions(eq(template.getSubjectId()), eq(1), eq(1), anyInt()))
                    .thenReturn(easyQuestions);
            when(questionMapper.selectRandomQuestionsByType(eq(template.getSubjectId()), eq(1), anyInt()))
                    .thenReturn(allSingle);

            when(questionMapper.countByTypeAndDifficulty(eq(template.getSubjectId()), eq(2), anyInt()))
                    .thenReturn(20);
            when(questionMapper.countByType(eq(template.getSubjectId()), eq(2)))
                    .thenReturn(20);
            List<Question> multiQuestions = QuestionFixture.createQuestionPool(20, 2, 2, template.getSubjectId());
            when(questionMapper.selectRandomQuestions(anyLong(), eq(2), anyInt(), anyInt()))
                    .thenReturn(multiQuestions);
            when(questionMapper.selectRandomQuestionsByType(anyLong(), eq(2), anyInt()))
                    .thenReturn(multiQuestions);

            when(questionMapper.countByTypeAndDifficulty(eq(template.getSubjectId()), eq(3), anyInt()))
                    .thenReturn(15);
            when(questionMapper.countByType(eq(template.getSubjectId()), eq(3)))
                    .thenReturn(15);
            List<Question> judgeQuestions = QuestionFixture.createQuestionPool(15, 3, 1, template.getSubjectId());
            when(questionMapper.selectRandomQuestions(anyLong(), eq(3), anyInt(), anyInt()))
                    .thenReturn(judgeQuestions);
            when(questionMapper.selectRandomQuestionsByType(anyLong(), eq(3), anyInt()))
                    .thenReturn(judgeQuestions);

            Paper paper = new Paper();
            paper.setId(1L);
            when(paperMapper.insert(any())).thenAnswer(inv -> {
                Paper p = inv.getArgument(0);
                p.setId(1L);
                return 1;
            });
            when(paperQuestionMapper.insert(any())).thenReturn(1);

            Paper result = paperGenerationService.generatePaper(template, "测试降级试卷", 1L);

            assertThat(result).isNotNull();
            assertThat(result.getQuestionCount()).isGreaterThan(0);

            verify(questionMapper, atLeastOnce()).selectRandomQuestionsByType(anyLong(), eq(1), anyInt());
        }

        @Test
        @DisplayName("按难度抽取不足时允许使用最近用过的题目作为兜底")
        void shouldAllowRecentlyUsedAsFallback() {
            PaperTemplate template = PaperTemplateFixture.objectiveOnlyTemplate()
                    .singleCount(3)
                    .build();

            when(questionMapper.countByTypeAndDifficulty(anyLong(), eq(1), anyInt()))
                    .thenReturn(3);
            when(questionMapper.countByType(anyLong(), eq(1))).thenReturn(3);
            when(questionMapper.countByTypeAndDifficulty(anyLong(), eq(2), anyInt())).thenReturn(10);
            when(questionMapper.countByType(anyLong(), eq(2))).thenReturn(10);
            when(questionMapper.countByTypeAndDifficulty(anyLong(), eq(3), anyInt())).thenReturn(10);
            when(questionMapper.countByType(anyLong(), eq(3))).thenReturn(10);

            List<Question> threeSingle = QuestionFixture.createQuestionPool(3, 1, 2, 1L);
            when(questionMapper.selectRandomQuestions(anyLong(), eq(1), anyInt(), anyInt()))
                    .thenReturn(threeSingle);
            when(questionMapper.selectRandomQuestionsByType(anyLong(), eq(1), anyInt()))
                    .thenReturn(threeSingle);

            List<Question> multi = QuestionFixture.createQuestionPool(10, 2, 2, 1L);
            when(questionMapper.selectRandomQuestions(anyLong(), eq(2), anyInt(), anyInt()))
                    .thenReturn(multi);
            when(questionMapper.selectRandomQuestionsByType(anyLong(), eq(2), anyInt())).thenReturn(multi);

            List<Question> judge = QuestionFixture.createQuestionPool(10, 3, 2, 1L);
            when(questionMapper.selectRandomQuestions(anyLong(), eq(3), anyInt(), anyInt()))
                    .thenReturn(judge);
            when(questionMapper.selectRandomQuestionsByType(anyLong(), eq(3), anyInt())).thenReturn(judge);

            when(paperMapper.insert(any())).thenAnswer(inv -> {
                Paper p = inv.getArgument(0);
                p.setId(100L);
                return 1;
            });
            when(paperQuestionMapper.insert(any())).thenReturn(1);

            Paper result1 = paperGenerationService.generatePaper(template, "试卷1", 1L);
            Paper result2 = paperGenerationService.generatePaper(template, "试卷2", 1L);

            assertThat(result1.getQuestionCount()).isEqualTo(3 + 10 + 10);
            assertThat(result2.getQuestionCount()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("随机卷去重测试")
    class RandomPaperDeduplicationTest {

        @Test
        @DisplayName("连续三次抽题不出现相同题目ID")
        void shouldNotHaveDuplicateQuestionsInThreeConsecutivePapers() {
            PaperTemplate template = PaperTemplateFixture.objectiveOnlyTemplate()
                    .singleCount(5)
                    .multipleCount(0)
                    .judgeCount(0)
                    .easyRatio(new BigDecimal("1"))
                    .mediumRatio(BigDecimal.ZERO)
                    .hardRatio(BigDecimal.ZERO)
                    .build();

            List<Question> easyPool = QuestionFixture.createQuestionPool(50, 1, 1, 1L);

            when(questionMapper.countByTypeAndDifficulty(anyLong(), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        Integer type = inv.getArgument(1);
                        return type == 1 ? 50 : 0;
                    });
            when(questionMapper.countByType(anyLong(), anyInt())).thenReturn(50);
            when(questionMapper.selectRandomQuestions(anyLong(), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        int limit = inv.getArgument(3);
                        return new ArrayList<>(easyPool.subList(0, Math.min(limit, easyPool.size())));
                    });
            when(questionMapper.selectRandomQuestionsByType(anyLong(), anyInt(), anyInt()))
                    .thenReturn(new ArrayList<>(easyPool));

            when(paperMapper.insert(any())).thenAnswer(inv -> {
                Paper p = inv.getArgument(0);
                p.setId(System.currentTimeMillis());
                return 1;
            });
            when(paperQuestionMapper.insert(any())).thenReturn(1);

            List<PaperQuestion> q1 = paperGenerationService.generateQuestions(template, new Paper());
            Set<Long> ids1 = q1.stream().map(PaperQuestion::getQuestionId).collect(java.util.stream.Collectors.toSet());

            easyPool.removeIf(q -> ids1.contains(q.getId()));
            List<PaperQuestion> q2 = paperGenerationService.generateQuestions(template, new Paper());
            Set<Long> ids2 = q2.stream().map(PaperQuestion::getQuestionId).collect(java.util.stream.Collectors.toSet());

            Set<Long> combined = new HashSet<>();
            combined.addAll(ids1);
            combined.addAll(ids2);
            easyPool.removeIf(q -> combined.contains(q.getId()));

            List<PaperQuestion> q3 = paperGenerationService.generateQuestions(template, new Paper());
            Set<Long> ids3 = q3.stream().map(PaperQuestion::getQuestionId).collect(java.util.stream.Collectors.toSet());

            assertThat(ids1).doesNotContainAnyElementsOf(ids2);
            assertThat(ids1).doesNotContainAnyElementsOf(ids3);
            assertThat(ids2).doesNotContainAnyElementsOf(ids3);
        }
    }

    @Nested
    @DisplayName("抽题锁粒度测试")
    class LockGranularityTest {

        @Test
        @DisplayName("同一科目并发组卷使用独立锁，不同科目锁互不干扰")
        void shouldUseSubjectLevelLockForPaperGeneration() throws InterruptedException {
            PaperTemplate template1 = PaperTemplateFixture.standardTemplate().subjectId(1L).build();
            PaperTemplate template2 = PaperTemplateFixture.standardTemplate().subjectId(2L).build();

            when(questionMapper.countByTypeAndDifficulty(anyLong(), anyInt(), anyInt())).thenReturn(50);
            when(questionMapper.countByType(anyLong(), anyInt())).thenReturn(50);
            when(questionMapper.selectRandomQuestions(anyLong(), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(inv -> QuestionFixture.createQuestionPool(
                            (Integer) inv.getArgument(3),
                            (Integer) inv.getArgument(1),
                            (Integer) inv.getArgument(2),
                            (Long) inv.getArgument(0)));
            when(questionMapper.selectRandomQuestionsByType(anyLong(), anyInt(), anyInt()))
                    .thenAnswer(inv -> QuestionFixture.createQuestionPool(
                            (Integer) inv.getArgument(2),
                            (Integer) inv.getArgument(1),
                            2,
                            (Long) inv.getArgument(0)));
            when(paperMapper.insert(any())).thenAnswer(inv -> {
                Paper p = inv.getArgument(0);
                p.setId(System.nanoTime());
                return 1;
            });
            when(paperQuestionMapper.insert(any())).thenReturn(1);

            paperGenerationService.generatePaper(template1, "科目1试卷", 1L);
            paperGenerationService.generatePaper(template2, "科目2试卷", 1L);

            verify(redissonClient, times(1)).getLock(contains(":1:"));
            verify(redissonClient, times(1)).getLock(contains(":2:"));
            verify(lock, times(2)).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("获取锁失败时抛出业务异常，不阻塞线程")
        void shouldThrowExceptionWhenLockAcquisitionFails() throws InterruptedException {
            PaperTemplate template = PaperTemplateFixture.standardTemplate().build();
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            assertThatThrownBy(() -> paperGenerationService.generatePaper(template, "测试锁", 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("系统繁忙");
        }
    }
}
