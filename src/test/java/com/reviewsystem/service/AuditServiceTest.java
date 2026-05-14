package com.reviewsystem.service;

import com.reviewsystem.model.*;
import com.reviewsystem.repository.*;
import com.reviewsystem.testdata.TestDataBuilder;
import com.reviewsystem.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审核模块单元测试")
class AuditServiceTest {

    @Mock
    private AuditRecordRepository auditRecordRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Mock
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Mock
    private CommentHistoryRepository commentHistoryRepository;

    @Mock
    private SensitiveWordFilter sensitiveWordFilter;

    @Mock
    private QualityEvaluator qualityEvaluator;

    @Mock
    private SentimentAnalyzer sentimentAnalyzer;

    @Mock
    private RecommendService recommendService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AuditService auditService;

    private SensitiveWordFilter.FilterResult cleanFilterResult;
    private SensitiveWordFilter.FilterResult sensitiveFilterResult;
    private QualityEvaluator.QualityResult highQualityResult;
    private QualityEvaluator.QualityResult lowQualityResult;
    private QualityEvaluator.QualityResult violationResult;
    private SentimentAnalyzer.SentimentResult positiveSentimentResult;

    @BeforeEach
    void setUp() {
        cleanFilterResult = new SensitiveWordFilter.FilterResult(
                false, Collections.emptyList(), "", 0.0
        );
        sensitiveFilterResult = new SensitiveWordFilter.FilterResult(
                true, Arrays.asList("赌博"), "赌博", 0.8
        );

        highQualityResult = new QualityEvaluator.QualityResult(
                85, 90, 80, 85, 10,
                false, null, null, false, "good"
        );

        lowQualityResult = new QualityEvaluator.QualityResult(
                25, 15, 30, 25, 20,
                false, null, null, false, "poor"
        );

        violationResult = new QualityEvaluator.QualityResult(
                15, 20, 10, 15, 90,
                true, "spam", "检测到垃圾内容", true, "violation"
        );

        positiveSentimentResult = new SentimentAnalyzer.SentimentResult(
                "positive", 0.85, 0.85, 0.05, 0.10,
                Arrays.asList("好", "优秀", "棒")
        );
    }

    @Nested
    @DisplayName("敏感词过滤测试")
    class SensitiveWordFilterTests {

        @Test
        @DisplayName("敏感词过滤 - 检测到敏感词")
        void testSensitiveWordDetection_Found() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_sens_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.SENSITIVE_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(sensitiveFilterResult);

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertTrue((Boolean) result.get("success"), "审核应该执行");
                assertEquals("rejected", result.get("audit_result"), "应被拒绝");
                assertEquals("rejected", result.get("comment_status"), "状态应为已拒绝");
                assertTrue(((String) result.get("audit_reason")).contains("赌博"),
                        "拒绝原因应包含敏感词");

                verify(commentRepository, times(1)).save(pendingComment);
                assertEquals("rejected", pendingComment.getAuditResult());
                assertEquals("rejected", pendingComment.getCommentStatus());
            }
        }

        @Test
        @DisplayName("敏感词过滤 - 未检测到敏感词")
        void testSensitiveWordDetection_NotFound() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_clean_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_clean_001");
                idGenerator.when(IdGenerator::generateSentimentId).thenReturn("sentiment_clean_001");
                idGenerator.when(IdGenerator::generateRecommendId).thenReturn("recommend_clean_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                when(recommendService.calculateRecommendScore(anyInt(), anyDouble())).thenReturn(85);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());
                when(sentimentAnalysisRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertTrue((Boolean) result.get("success"), "审核应该成功");
                assertEquals("approved", result.get("audit_result"), "应审核通过");
                assertEquals(85, result.get("quality_score"), "质量分数应正确");
                assertEquals("positive", result.get("sentiment_type"), "情感类型应正确");

                verify(commentRepository, times(1)).save(pendingComment);
                assertEquals("approved", pendingComment.getAuditResult());
                assertEquals("published", pendingComment.getCommentStatus());
                assertEquals(85, pendingComment.getQualityScore());
                assertEquals(85, pendingComment.getRecommendScore());
            }
        }

        @Test
        @DisplayName("敏感词列表配置验证")
        void testSensitiveWordListConfiguration() {
            Set<String> expectedWords = TestDataBuilder.SENSITIVE_WORDS;

            assertNotNull(expectedWords, "敏感词列表不应为空");
            assertTrue(expectedWords.contains("赌博"), "应包含赌博");
            assertTrue(expectedWords.contains("色情"), "应包含色情");
            assertTrue(expectedWords.contains("暴力"), "应包含暴力");
            assertTrue(expectedWords.contains("毒品"), "应包含毒品");
            assertTrue(expectedWords.contains("诈骗"), "应包含诈骗");
            assertEquals(5, expectedWords.size(), "应有5个敏感词");
        }
    }

    @Nested
    @DisplayName("质量检测测试")
    class QualityCheckTests {

        @Test
        @DisplayName("高质量评论 - 通过审核")
        void testHighQualityComment_Approved() {
            QualityEvaluator.QualityResult result = highQualityResult;

            assertTrue(result.getQualityScore() >= 60, "质量分数应>=60");
            assertFalse(result.isViolation(), "不应违规");
            assertFalse(result.isSpam(), "不应是垃圾");
            assertEquals("good", result.getEvaluationLevel(), "评级应为good");
            assertEquals(85, result.getQualityScore(), "质量分数应为85");
            assertTrue(result.getLengthScore() >= 70, "长度分数应达标");
            assertTrue(result.getRelevanceScore() >= 70, "相关性分数应达标");
            assertTrue(result.getReadabilityScore() >= 70, "可读性分数应达标");
        }

        @Test
        @DisplayName("低质量评论 - 待人工审核")
        void testLowQualityComment_Pending() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_low_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_low_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.SHORT_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(lowQualityResult);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertTrue((Boolean) result.get("success"), "审核应该执行");
                assertEquals("pending", result.get("audit_result"), "应待人工审核");
                assertEquals("pending", result.get("comment_status"), "状态应为待审核");
                assertTrue(((String) result.get("audit_reason")).contains("人工审核"),
                        "原因应包含人工审核");

                verify(commentRepository, times(1)).save(pendingComment);
                assertEquals("pending", pendingComment.getAuditResult());
                assertEquals("pending", pendingComment.getCommentStatus());
            }
        }

        @Test
        @DisplayName("违规评论 - 拒绝")
        void testViolationComment_Rejected() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_violation_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_violation_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.SPAM_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(violationResult);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertTrue((Boolean) result.get("success"), "审核应该执行");
                assertEquals("rejected", result.get("audit_result"), "应被拒绝");
                assertEquals("rejected", result.get("comment_status"), "状态应为已拒绝");
                assertTrue(((String) result.get("audit_reason")).contains("垃圾"),
                        "拒绝原因应包含垃圾");
            }
        }

        @Test
        @DisplayName("质量评分边界测试")
        void testQualityScoreBoundary() {
            QualityEvaluator.QualityResult edgeResult = new QualityEvaluator.QualityResult(
                    30, 35, 25, 30, 15,
                    false, null, null, false, "poor"
            );

            assertEquals(30, edgeResult.getQualityScore(), "质量分数应为30");
            assertFalse(edgeResult.isViolation(), "不应违规");
            assertFalse(edgeResult.isSpam(), "不应是垃圾");
        }
    }

    @Nested
    @DisplayName("审核结果判断测试")
    class AuditResultTests {

        @Test
        @DisplayName("审核通过 - 状态流转正确")
        void testAuditApproved_StatusFlow() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_approved_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_approved_001");
                idGenerator.when(IdGenerator::generateSentimentId).thenReturn("sentiment_approved_001");
                idGenerator.when(IdGenerator::generateRecommendId).thenReturn("recommend_approved_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT
                );
                assertEquals("pending", pendingComment.getCommentStatus(), "初始状态应为待审核");

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                when(recommendService.calculateRecommendScore(anyInt(), anyDouble())).thenReturn(85);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());
                when(sentimentAnalysisRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertEquals("approved", result.get("audit_result"), "审核结果应为通过");
                assertEquals("published", result.get("comment_status"), "状态应为已发布");
                assertEquals(85, result.get("quality_score"), "质量分数应正确");
                assertEquals(0.85, result.get("sentiment_score"), "情感分数应正确");

                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
                verify(historyService, times(1)).recordHistory(
                        eq(TestDataBuilder.TEST_COMMENT_ID),
                        eq("AUDIT"),
                        anyString(),
                        anyString(),
                        eq("published"),
                        any(),
                        any(),
                        eq(TestDataBuilder.TEST_AUDITOR),
                        eq("admin")
                );
            }
        }

        @Test
        @DisplayName("审核拒绝 - 状态流转正确")
        void testAuditRejected_StatusFlow() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_rejected_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.SENSITIVE_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(sensitiveFilterResult);

                Map<String, Object> result = auditService.auditComment(
                        TestDataBuilder.TEST_COMMENT_ID, TestDataBuilder.TEST_AUDITOR);

                assertEquals("rejected", result.get("audit_result"), "审核结果应为拒绝");
                assertEquals("rejected", result.get("comment_status"), "状态应为已拒绝");

                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
            }
        }

        @Test
        @DisplayName("审核评论不存在")
        void testAuditNonExistingComment() {
            when(commentRepository.findById("non_existing"))
                    .thenReturn(Optional.empty());

            Map<String, Object> result = auditService.auditComment(
                    "non_existing", TestDataBuilder.TEST_AUDITOR);

            assertFalse((Boolean) result.get("success"), "审核应该失败");
            assertEquals("评论不存在", result.get("message"), "错误信息应正确");
        }
    }

    @Nested
    @DisplayName("人工审核测试")
    class ManualAuditTests {

        @Test
        @DisplayName("人工审核通过")
        void testManualAudit_Approve() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_manual_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_manual_001");
                idGenerator.when(IdGenerator::generateSentimentId).thenReturn("sentiment_manual_001");
                idGenerator.when(IdGenerator::generateRecommendId).thenReturn("recommend_manual_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));
                when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                when(recommendService.calculateRecommendScore(anyInt(), anyDouble())).thenReturn(85);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());
                when(sentimentAnalysisRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = auditService.manualAudit(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.TEST_AUDITOR,
                        "approve",
                        "人工审核通过"
                );

                assertTrue((Boolean) result.get("success"), "审核应该成功");
                assertEquals("approved", result.get("audit_result"), "结果应为通过");
                assertEquals("published", result.get("comment_status"), "状态应为已发布");
                assertEquals(TestDataBuilder.TEST_AUDITOR, result.get("auditor"), "审核人应正确");
                assertEquals(85, result.get("quality_score"), "质量分数应正确");
                assertEquals("positive", result.get("sentiment_type"), "情感类型应正确");

                verify(commentRepository, times(1)).save(pendingComment);
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
            }
        }

        @Test
        @DisplayName("人工审核拒绝")
        void testManualAudit_Reject() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_manual_rej_001");

                Comment pendingComment = TestDataBuilder.buildPendingComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(pendingComment));

                Map<String, Object> result = auditService.manualAudit(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.TEST_AUDITOR,
                        "reject",
                        "人工审核拒绝"
                );

                assertTrue((Boolean) result.get("success"), "审核应该成功");
                assertEquals("rejected", result.get("audit_result"), "结果应为拒绝");
                assertEquals("rejected", result.get("comment_status"), "状态应为已拒绝");
                assertEquals(TestDataBuilder.TEST_AUDITOR, result.get("auditor"), "审核人应正确");

                verify(commentRepository, times(1)).save(pendingComment);
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
            }
        }

        @Test
        @DisplayName("无效的审核决策")
        void testManualAudit_InvalidDecision() {
            Comment pendingComment = TestDataBuilder.buildPendingComment(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.NORMAL_COMMENT
            );

            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(pendingComment));

            Map<String, Object> result = auditService.manualAudit(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.TEST_AUDITOR,
                    "invalid_decision",
                    "无效决策"
            );

            assertFalse((Boolean) result.get("success"), "审核应该失败");
            assertEquals("无效的审核决策", result.get("message"), "错误信息应正确");
        }
    }

    @Nested
    @DisplayName("审核异步化机制测试")
    class AuditAsyncTests {

        @Test
        @DisplayName("并发审核测试 - 多线程安全")
        void testConcurrentAudit_ThreadSafe() throws InterruptedException, ExecutionException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenAnswer(inv -> "audit_concurrent_" + UUID.randomUUID().toString());
                idGenerator.when(IdGenerator::generateEvaluationId).thenAnswer(inv -> "eval_concurrent_" + UUID.randomUUID().toString());
                idGenerator.when(IdGenerator::generateSentimentId).thenAnswer(inv -> "sentiment_concurrent_" + UUID.randomUUID().toString());
                idGenerator.when(IdGenerator::generateRecommendId).thenAnswer(inv -> "recommend_concurrent_" + UUID.randomUUID().toString());

                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                when(recommendService.calculateRecommendScore(anyInt(), anyDouble())).thenReturn(85);
                when(qualityEvaluationRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());
                when(sentimentAnalysisRepository.findByCommentId(anyString()))
                        .thenReturn(Optional.empty());

                List<Future<Boolean>> futures = new ArrayList<>();

                for (int i = 0; i < threadCount; i++) {
                    final String commentId = "concurrent_comment_" + i;
                    Comment comment = TestDataBuilder.buildPendingComment(
                            commentId,
                            TestDataBuilder.NORMAL_COMMENT
                    );
                    when(commentRepository.findById(commentId))
                            .thenReturn(Optional.of(comment));

                    futures.add(executor.submit(() -> {
                        try {
                            latch.countDown();
                            Map<String, Object> result = auditService.auditComment(
                                    commentId, "concurrent_auditor");
                            return (Boolean) result.getOrDefault("success", false);
                        } catch (Exception e) {
                            return false;
                        }
                    }));
                }

                boolean allSuccess = true;
                for (Future<Boolean> future : futures) {
                    if (!future.get()) {
                        allSuccess = false;
                    }
                }

                assertTrue(allSuccess, "所有并发审核应该成功");

                executor.shutdown();
            }
        }

        @Test
        @DisplayName("审核任务队列 - 模拟异步处理")
        void testAuditQueue_AsyncProcessing() {
            Queue<String> auditQueue = new LinkedList<>();
            List<String> processedComments = new ArrayList<>();

            auditQueue.add("queue_comment_1");
            auditQueue.add("queue_comment_2");
            auditQueue.add("queue_comment_3");

            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenAnswer(inv -> "audit_queue_" + UUID.randomUUID());
                idGenerator.when(IdGenerator::generateEvaluationId).thenAnswer(inv -> "eval_queue_" + UUID.randomUUID());
                idGenerator.when(IdGenerator::generateSentimentId).thenAnswer(inv -> "sentiment_queue_" + UUID.randomUUID());
                idGenerator.when(IdGenerator::generateRecommendId).thenAnswer(inv -> "recommend_queue_" + UUID.randomUUID());

                while (!auditQueue.isEmpty()) {
                    String commentId = auditQueue.poll();
                    Comment comment = TestDataBuilder.buildPendingComment(
                            commentId, TestDataBuilder.NORMAL_COMMENT
                    );

                    when(commentRepository.findById(commentId))
                            .thenReturn(Optional.of(comment));
                    when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                    when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                    when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                    when(recommendService.calculateRecommendScore(anyInt(), anyDouble())).thenReturn(85);
                    when(qualityEvaluationRepository.findByCommentId(anyString()))
                            .thenReturn(Optional.empty());
                    when(sentimentAnalysisRepository.findByCommentId(anyString()))
                            .thenReturn(Optional.empty());

                    Map<String, Object> result = auditService.auditComment(
                            commentId, "queue_auditor");

                    if (Boolean.TRUE.equals(result.get("success"))) {
                        processedComments.add(commentId);
                    }
                }

                assertEquals(3, processedComments.size(), "所有队列任务应处理完成");
                assertTrue(auditQueue.isEmpty(), "队列应已清空");
            }
        }
    }

    @Nested
    @DisplayName("审核统计测试")
    class AuditStatsTests {

        @Test
        @DisplayName("获取审核统计")
        void testGetAuditStats() {
            when(auditRecordRepository.countByAuditResult("approved")).thenReturn(100L);
            when(auditRecordRepository.countByAuditResult("rejected")).thenReturn(20L);
            when(commentRepository.countByAuditResult("pending")).thenReturn(15L);

            Map<String, Long> stats = auditService.getAuditStats();

            assertEquals(100L, stats.get("approved"), "通过数应正确");
            assertEquals(20L, stats.get("rejected"), "拒绝数应正确");
            assertEquals(15L, stats.get("pending"), "待审核数应正确");
        }

        @Test
        @DisplayName("统计待审核评论数量")
        void testCountPendingComments() {
            when(commentRepository.countByAuditResult("pending")).thenReturn(25L);

            long count = auditService.countPendingComments();

            assertEquals(25L, count, "待审核数量应正确");
        }
    }
}
