package com.reviewsystem.service;

import com.reviewsystem.dto.CommentEditRequest;
import com.reviewsystem.dto.CommentPublishRequest;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("评论管理模块单元测试")
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private AuditRecordRepository auditRecordRepository;

    @Mock
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Mock
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Mock
    private RecommendRecordRepository recommendRecordRepository;

    @Mock
    private CommentHistoryRepository commentHistoryRepository;

    @Mock
    private CommentStatRepository commentStatRepository;

    @Mock
    private SensitiveWordFilter sensitiveWordFilter;

    @Mock
    private SentimentAnalyzer sentimentAnalyzer;

    @Mock
    private QualityEvaluator qualityEvaluator;

    @InjectMocks
    private CommentService commentService;

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
    @DisplayName("评论发布测试")
    class PublishCommentTests {

        @Test
        @DisplayName("发布正常评论 - 审核通过")
        void testPublishNormalComment_Approved() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateCommentId).thenReturn("comment_test_001");
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_test_001");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_test_001");
                idGenerator.when(IdGenerator::generateSentimentId).thenReturn("sentiment_test_001");
                idGenerator.when(IdGenerator::generateRecommendId).thenReturn("recommend_test_001");
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_test_001");
                idGenerator.when(IdGenerator::generateStatId).thenReturn("stat_test_001");

                CommentPublishRequest request = TestDataBuilder.buildNormalPublishRequest();

                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(highQualityResult);
                when(sentimentAnalyzer.analyze(anyString())).thenReturn(positiveSentimentResult);
                when(commentStatRepository.findByContentIdAndStatDate(anyString(), any()))
                        .thenReturn(Optional.empty());
                when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

                Map<String, Object> result = commentService.publishComment(request);

                assertTrue((Boolean) result.get("success"), "评论发布应该成功");
                assertEquals("published", result.get("status"), "状态应为已发布");
                assertEquals("approved", result.get("audit_result"), "审核结果应为通过");
                assertNotNull(result.get("comment_id"), "应返回评论ID");

                verify(commentRepository, times(1)).save(any(Comment.class));
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
                verify(qualityEvaluationRepository, times(1)).save(any(QualityEvaluation.class));
                verify(sentimentAnalysisRepository, times(1)).save(any(SentimentAnalysis.class));
                verify(recommendRecordRepository, times(1)).save(any(RecommendRecord.class));
                verify(commentHistoryRepository, times(1)).save(any(CommentHistory.class));
                verify(commentStatRepository, times(1)).save(any(CommentStat.class));

                idGenerator.verify(IdGenerator::generateCommentId, times(1));
            }
        }

        @Test
        @DisplayName("发布含敏感词评论 - 审核拒绝")
        void testPublishSensitiveComment_Rejected() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateCommentId).thenReturn("comment_test_002");
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_test_002");
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_test_002");
                idGenerator.when(IdGenerator::generateStatId).thenReturn("stat_test_002");

                CommentPublishRequest request = TestDataBuilder.buildSensitivePublishRequest();

                when(sensitiveWordFilter.filter(anyString())).thenReturn(sensitiveFilterResult);
                when(commentStatRepository.findByContentIdAndStatDate(anyString(), any()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = commentService.publishComment(request);

                assertTrue((Boolean) result.get("success"), "评论发布应该成功（但审核拒绝）");
                assertEquals("rejected", result.get("status"), "状态应为已拒绝");
                assertEquals("rejected", result.get("audit_result"), "审核结果应为拒绝");
                assertTrue(((String) result.get("audit_reason")).contains("敏感词"),
                        "拒绝原因应包含敏感词");

                verify(commentRepository, times(1)).save(any(Comment.class));
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
                verify(qualityEvaluationRepository, never()).save(any());
                verify(sentimentAnalysisRepository, never()).save(any());
                verify(recommendRecordRepository, never()).save(any());
            }
        }

        @Test
        @DisplayName("发布低质量评论 - 待人工审核")
        void testPublishLowQualityComment_Pending() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateCommentId).thenReturn("comment_test_003");
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_test_003");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_test_003");
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_test_003");
                idGenerator.when(IdGenerator::generateStatId).thenReturn("stat_test_003");

                CommentPublishRequest request = TestDataBuilder.buildShortPublishRequest();

                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(lowQualityResult);
                when(commentStatRepository.findByContentIdAndStatDate(anyString(), any()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = commentService.publishComment(request);

                assertTrue((Boolean) result.get("success"), "评论发布应该成功（但待审核）");
                assertEquals("pending", result.get("status"), "状态应为待审核");
                assertEquals("pending", result.get("audit_result"), "审核结果应为待审核");
                assertTrue(((String) result.get("audit_reason")).contains("人工审核"),
                        "原因应包含人工审核");

                verify(commentRepository, times(1)).save(any(Comment.class));
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
                verify(qualityEvaluationRepository, times(1)).save(any());
                verify(sentimentAnalysisRepository, never()).save(any());
                verify(recommendRecordRepository, never()).save(any());
            }
        }

        @Test
        @DisplayName("发布违规内容评论 - 审核拒绝")
        void testPublishViolationComment_Rejected() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateCommentId).thenReturn("comment_test_004");
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_test_004");
                idGenerator.when(IdGenerator::generateEvaluationId).thenReturn("eval_test_004");
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_test_004");
                idGenerator.when(IdGenerator::generateStatId).thenReturn("stat_test_004");

                CommentPublishRequest request = TestDataBuilder.buildSpamPublishRequest();

                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);
                when(qualityEvaluator.evaluate(anyString())).thenReturn(violationResult);
                when(commentStatRepository.findByContentIdAndStatDate(anyString(), any()))
                        .thenReturn(Optional.empty());

                Map<String, Object> result = commentService.publishComment(request);

                assertTrue((Boolean) result.get("success"), "评论发布应该成功（但审核拒绝）");
                assertEquals("rejected", result.get("status"), "状态应为已拒绝");
                assertEquals("rejected", result.get("audit_result"), "审核结果应为拒绝");

                verify(commentRepository, times(1)).save(any(Comment.class));
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
                verify(qualityEvaluationRepository, times(1)).save(any());
            }
        }

        @Test
        @DisplayName("发布空评论 - 异常处理")
        void testPublishEmptyComment_Exception() {
            CommentPublishRequest request = TestDataBuilder.buildEmptyPublishRequest();

            Map<String, Object> result = commentService.publishComment(request);

            assertFalse((Boolean) result.get("success"), "发布应该失败");
            assertEquals("评论内容不能为空", result.get("message"), "错误信息应正确");
            assertEquals("EMPTY_CONTENT", result.get("code"), "错误码应正确");

            verify(commentRepository, never()).save(any());
            verify(auditRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("发布超长评论 - 异常处理")
        void testPublishLongComment_Exception() {
            CommentPublishRequest request = TestDataBuilder.buildLongPublishRequest();

            Map<String, Object> result = commentService.publishComment(request);

            assertFalse((Boolean) result.get("success"), "发布应该失败");
            assertEquals("评论内容不能超过2000字", result.get("message"), "错误信息应正确");
            assertEquals("CONTENT_TOO_LONG", result.get("code"), "错误码应正确");

            verify(commentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("评论编辑测试")
    class EditCommentTests {

        @Test
        @DisplayName("编辑存在的评论 - 成功")
        void testEditExistingComment_Success() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateAuditId).thenReturn("audit_edit_001");
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_edit_001");

                Comment existingComment = TestDataBuilder.buildPublishedComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT,
                        85, 80
                );

                CommentEditRequest request = TestDataBuilder.buildEditRequest(
                        TestDataBuilder.TEST_COMMENT_ID,
                        "这是编辑后的评论内容，更加详细和具体。"
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(sensitiveWordFilter.filter(anyString())).thenReturn(cleanFilterResult);

                Map<String, Object> result = commentService.editComment(request);

                assertTrue((Boolean) result.get("success"), "编辑应该成功");
                assertEquals("pending", result.get("status"), "状态应为待审核");

                verify(commentRepository, times(1)).save(any(Comment.class));
                verify(commentHistoryRepository, times(1)).save(any(CommentHistory.class));
                verify(auditRecordRepository, times(1)).save(any(AuditRecord.class));
            }
        }

        @Test
        @DisplayName("编辑不存在的评论 - 失败")
        void testEditNonExistingComment_Failure() {
            CommentEditRequest request = TestDataBuilder.buildEditRequest(
                    "non_existing_comment",
                    "新的评论内容"
            );

            when(commentRepository.findById("non_existing_comment"))
                    .thenReturn(Optional.empty());

            Map<String, Object> result = commentService.editComment(request);

            assertFalse((Boolean) result.get("success"), "编辑应该失败");
            assertEquals("评论不存在", result.get("message"), "错误信息应正确");

            verify(commentRepository, never()).save(any());
        }

        @Test
        @DisplayName("编辑含敏感词评论 - 失败")
        void testEditWithSensitiveWord_Failure() {
            Comment existingComment = TestDataBuilder.buildPublishedComment(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.NORMAL_COMMENT,
                    85, 80
            );

            CommentEditRequest request = TestDataBuilder.buildEditRequest(
                    TestDataBuilder.TEST_COMMENT_ID,
                    "编辑后的内容包含赌博"
            );

            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existingComment));
            when(sensitiveWordFilter.filter(anyString())).thenReturn(sensitiveFilterResult);

            Map<String, Object> result = commentService.editComment(request);

            assertFalse((Boolean) result.get("success"), "编辑应该失败");
            assertTrue(((String) result.get("message")).contains("敏感词"),
                    "错误信息应包含敏感词");

            verify(commentRepository, never()).save(any());
        }

        @Test
        @DisplayName("编辑为空内容 - 失败")
        void testEditWithEmptyContent_Failure() {
            Comment existingComment = TestDataBuilder.buildPublishedComment(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.NORMAL_COMMENT,
                    85, 80
            );

            CommentEditRequest request = TestDataBuilder.buildEditRequest(
                    TestDataBuilder.TEST_COMMENT_ID,
                    "   "
            );

            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existingComment));

            Map<String, Object> result = commentService.editComment(request);

            assertFalse((Boolean) result.get("success"), "编辑应该失败");
            assertEquals("评论内容不能为空", result.get("message"), "错误信息应正确");
        }
    }

    @Nested
    @DisplayName("评论查询测试")
    class QueryCommentTests {

        @Test
        @DisplayName("查询存在的评论 - 成功")
        void testGetExistingComment_Success() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.NORMAL_COMMENT,
                    85, 80
            );

            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(comment));

            Optional<Comment> result = commentService.getComment(TestDataBuilder.TEST_COMMENT_ID);

            assertTrue(result.isPresent(), "应该找到评论");
            assertEquals(TestDataBuilder.TEST_COMMENT_ID, result.get().getCommentId());
        }

        @Test
        @DisplayName("查询不存在的评论 - 空结果")
        void testGetNonExistingComment_Empty() {
            when(commentRepository.findById("non_existing"))
                    .thenReturn(Optional.empty());

            Optional<Comment> result = commentService.getComment("non_existing");

            assertFalse(result.isPresent(), "不应该找到评论");
        }
    }

    @Nested
    @DisplayName("评论删除测试")
    class DeleteCommentTests {

        @Test
        @DisplayName("删除存在的评论 - 成功")
        void testDeleteExistingComment_Success() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateHistoryId).thenReturn("history_del_001");

                Comment existingComment = TestDataBuilder.buildPublishedComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT,
                        85, 80
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));

                boolean result = commentService.deleteComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.TEST_AUDITOR
                );

                assertTrue(result, "删除应该成功");
                assertEquals("deleted", existingComment.getCommentStatus(), "状态应为已删除");

                verify(commentRepository, times(1)).save(existingComment);
                verify(commentHistoryRepository, times(1)).save(any(CommentHistory.class));
            }
        }

        @Test
        @DisplayName("删除不存在的评论 - 失败")
        void testDeleteNonExistingComment_Failure() {
            when(commentRepository.findById("non_existing"))
                    .thenReturn(Optional.empty());

            boolean result = commentService.deleteComment("non_existing", "admin");

            assertFalse(result, "删除应该失败");
            verify(commentRepository, never()).save(any());
        }
    }
}
