package com.reviewsystem.service;

import com.reviewsystem.model.Comment;
import com.reviewsystem.model.RecommendRecord;
import com.reviewsystem.model.SentimentAnalysis;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.RecommendRecordRepository;
import com.reviewsystem.repository.SentimentAnalysisRepository;
import com.reviewsystem.testdata.TestDataBuilder;
import com.reviewsystem.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("推荐模块单元测试")
class RecommendServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private RecommendRecordRepository recommendRecordRepository;

    @Mock
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @InjectMocks
    private RecommendService recommendService;

    private Comment highQualityComment;
    private Comment mediumQualityComment;
    private Comment lowQualityComment;
    private Comment oldComment;

    @BeforeEach
    void setUp() {
        highQualityComment = TestDataBuilder.buildHighQualityComment(TestDataBuilder.TEST_COMMENT_ID);
        mediumQualityComment = TestDataBuilder.buildMediumQualityComment(TestDataBuilder.TEST_COMMENT_ID_2);
        lowQualityComment = TestDataBuilder.buildLowQualityComment(TestDataBuilder.TEST_COMMENT_ID_3);
        oldComment = TestDataBuilder.buildOldComment(
                "comment_old_001",
                LocalDateTime.now().minusDays(5)
        );
    }

    @Nested
    @DisplayName("推荐分数计算测试")
    class RecommendScoreTests {

        @Test
        @DisplayName("基础推荐分数计算 - 高质量高情感")
        void testCalculateRecommendScore_HighQualityHighSentiment() {
            int qualityScore = 90;
            double sentimentScore = 0.90;

            int result = recommendService.calculateRecommendScore(qualityScore, sentimentScore);

            int expected = (90 + 90) / 2;
            assertEquals(expected, result, "推荐分数应正确计算");
            assertTrue(result >= 0 && result <= 100, "分数应在0-100之间");
        }

        @Test
        @DisplayName("基础推荐分数计算 - 低质量低情感")
        void testCalculateRecommendScore_LowQualityLowSentiment() {
            int qualityScore = 30;
            double sentimentScore = 0.20;

            int result = recommendService.calculateRecommendScore(qualityScore, sentimentScore);

            int expected = (30 + 20) / 2;
            assertEquals(expected, result, "推荐分数应正确计算");
        }

        @Test
        @DisplayName("基础推荐分数计算 - 边界值测试")
        void testCalculateRecommendScore_BoundaryValues() {
            assertEquals(100, recommendService.calculateRecommendScore(100, 1.0),
                    "最大值应为100");
            assertEquals(0, recommendService.calculateRecommendScore(0, 0.0),
                    "最小值应为0");
            assertEquals(50, recommendService.calculateRecommendScore(50, 0.5),
                    "中间值应为50");
        }

        @Test
        @DisplayName("多因素推荐分数计算 - 含热度和时间衰减")
        void testCalculateRecommendScoreWithHeat_AllFactors() {
            Comment hotComment = TestDataBuilder.buildPublishedComment(
                    "comment_hot_001",
                    TestDataBuilder.POSITIVE_COMMENT,
                    85, 80
            );
            hotComment.setLikeCount(100);
            hotComment.setReplyCount(20);
            hotComment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    hotComment, 85, 0.85);

            int qualityFactor = 85;
            int sentimentFactor = 85;
            int heatScore = Math.min(30, 100 * 2 + 20 * 3);
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "多因素推荐分数应正确计算");
            assertTrue(result >= 0 && result <= 100, "分数应在0-100之间");
        }

        @Test
        @DisplayName("多因素推荐分数计算 - 旧评论时间衰减")
        void testCalculateRecommendScoreWithHeat_OldCommentDecay() {
            Comment veryOldComment = TestDataBuilder.buildPublishedComment(
                    "comment_very_old_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    85, 80
            );
            veryOldComment.setLikeCount(50);
            veryOldComment.setReplyCount(10);
            veryOldComment.setCreatedAt(LocalDateTime.now().minusDays(8));

            int result = recommendService.calculateRecommendScoreWithHeat(
                    veryOldComment, 85, 0.70);

            int qualityFactor = 85;
            int sentimentFactor = 70;
            int heatScore = Math.min(30, 50 * 2 + 10 * 3);
            long hoursSinceCreated = 8 * 24;
            double timeDecay = Math.max(0.5, 1.0 - hoursSinceCreated / 168.0);
            int timeFactor = (int) (timeDecay * 100);

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "旧评论推荐分数应正确计算");
            assertTrue(timeFactor < 100, "时间衰减因子应小于100");
        }

        @Test
        @DisplayName("多因素权重验证")
        void testRecommendScoreWeightVerification() {
            Comment freshHotComment = TestDataBuilder.buildPublishedComment(
                    "comment_fresh_hot_001",
                    TestDataBuilder.POSITIVE_COMMENT,
                    100, 100
            );
            freshHotComment.setLikeCount(200);
            freshHotComment.setReplyCount(50);
            freshHotComment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    freshHotComment, 100, 1.0);

            assertTrue(result > 90, "高质量高热度新评论应获得高分");
            assertTrue(result <= 100, "分数不应超过100");
        }

        @Test
        @DisplayName("推荐分数计算 - null值处理")
        void testCalculateRecommendScoreWithHeat_NullValues() {
            Comment nullMetricsComment = TestDataBuilder.buildPublishedComment(
                    "comment_null_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    70, 65
            );
            nullMetricsComment.setLikeCount(null);
            nullMetricsComment.setReplyCount(null);
            nullMetricsComment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    nullMetricsComment, 70, 0.60);

            int qualityFactor = 70;
            int sentimentFactor = 60;
            int heatScore = 0;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "null值应安全处理");
        }
    }

    @Nested
    @DisplayName("多因素权重计算测试")
    class MultiFactorWeightTests {

        @Test
        @DisplayName("质量因素权重验证 - 占比40%")
        void testQualityFactorWeight() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    "comment_q_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    100, 100
            );
            comment.setLikeCount(0);
            comment.setReplyCount(0);
            comment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    comment, 100, 0.0);

            int qualityFactor = 100;
            int sentimentFactor = 0;
            int heatScore = 0;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "质量因素权重应为40%");
        }

        @Test
        @DisplayName("情感因素权重验证 - 占比20%")
        void testSentimentFactorWeight() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    "comment_s_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    0, 0
            );
            comment.setLikeCount(0);
            comment.setReplyCount(0);
            comment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    comment, 0, 1.0);

            int qualityFactor = 0;
            int sentimentFactor = 100;
            int heatScore = 0;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "情感因素权重应为20%");
        }

        @Test
        @DisplayName("热度因素权重验证 - 占比20%")
        void testHeatFactorWeight() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    "comment_h_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    0, 0
            );
            comment.setLikeCount(1000);
            comment.setReplyCount(500);
            comment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    comment, 0, 0.0);

            int qualityFactor = 0;
            int sentimentFactor = 0;
            int heatScore = 30;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "热度因素权重应为20%");
        }

        @Test
        @DisplayName("时间因素权重验证 - 占比20%")
        void testTimeFactorWeight() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    "comment_t_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    0, 0
            );
            comment.setLikeCount(0);
            comment.setReplyCount(0);
            comment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    comment, 0, 0.0);

            int qualityFactor = 0;
            int sentimentFactor = 0;
            int heatScore = 0;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "时间因素权重应为20%");
        }

        @Test
        @DisplayName("热度分数上限测试 - 30分封顶")
        void testHeatScoreCapped() {
            Comment hyperHotComment = TestDataBuilder.buildPublishedComment(
                    "comment_hyper_001",
                    TestDataBuilder.POSITIVE_COMMENT,
                    100, 100
            );
            hyperHotComment.setLikeCount(10000);
            hyperHotComment.setReplyCount(5000);
            hyperHotComment.setCreatedAt(LocalDateTime.now());

            int result = recommendService.calculateRecommendScoreWithHeat(
                    hyperHotComment, 100, 1.0);

            int qualityFactor = 100;
            int sentimentFactor = 100;
            int heatScore = 30;
            int timeFactor = 100;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "热度分数应封顶30分");
        }

        @Test
        @DisplayName("时间衰减下限测试 - 50%保底")
        void testTimeDecayFloor() {
            Comment ancientComment = TestDataBuilder.buildPublishedComment(
                    "comment_ancient_001",
                    TestDataBuilder.NORMAL_COMMENT,
                    100, 100
            );
            ancientComment.setLikeCount(0);
            ancientComment.setReplyCount(0);
            ancientComment.setCreatedAt(LocalDateTime.now().minusDays(30));

            int result = recommendService.calculateRecommendScoreWithHeat(
                    ancientComment, 100, 1.0);

            int qualityFactor = 100;
            int sentimentFactor = 100;
            int heatScore = 0;
            int timeFactor = 50;

            int expected = (int) (qualityFactor * 0.4 + sentimentFactor * 0.2 +
                    heatScore * 0.2 + timeFactor * 0.2);

            assertEquals(expected, result, "时间衰减应有50%保底");
        }
    }

    @Nested
    @DisplayName("推荐排序测试")
    class RecommendSortTests {

        @Test
        @DisplayName("推荐列表排序 - 按推荐分数降序")
        void testRecommendedCommentsSorting() {
            List<Comment> mockComments = Arrays.asList(
                    highQualityComment,
                    mediumQualityComment,
                    lowQualityComment
            );

            when(commentRepository.findRecommendedComments(eq(TestDataBuilder.TEST_CONTENT_ID), any(Pageable.class)))
                    .thenReturn(mockComments);

            List<Comment> result = recommendService.getRecommendedComments(
                    TestDataBuilder.TEST_CONTENT_ID, 10);

            assertEquals(3, result.size(), "应返回3条评论");
            assertEquals(highQualityComment.getCommentId(), result.get(0).getCommentId(),
                    "高质量评论应排在第一");
            assertEquals(mediumQualityComment.getCommentId(), result.get(1).getCommentId(),
                    "中等质量评论应排在第二");
            assertEquals(lowQualityComment.getCommentId(), result.get(2).getCommentId(),
                    "低质量评论应排在第三");
        }

        @Test
        @DisplayName("热门评论排序 - 按热度降序")
        void testHotCommentsSorting() {
            Comment hot1 = TestDataBuilder.buildPublishedComment("hot_1", "内容1", 80, 85);
            hot1.setLikeCount(100);
            hot1.setReplyCount(20);

            Comment hot2 = TestDataBuilder.buildPublishedComment("hot_2", "内容2", 70, 75);
            hot2.setLikeCount(200);
            hot2.setReplyCount(50);

            List<Comment> mockComments = Arrays.asList(hot2, hot1);

            when(commentRepository.findHotComments(eq(TestDataBuilder.TEST_CONTENT_ID), any(Pageable.class)))
                    .thenReturn(mockComments);

            List<Comment> result = recommendService.getHotComments(
                    TestDataBuilder.TEST_CONTENT_ID, 10);

            assertEquals(2, result.size(), "应返回2条评论");
            assertEquals("hot_2", result.get(0).getCommentId(),
                    "更热门评论应排在前面");
        }

        @Test
        @DisplayName("最新评论排序 - 按时间降序")
        void testLatestCommentsSorting() {
            Comment latest = TestDataBuilder.buildPublishedComment("latest_1", "内容1", 60, 65);
            Comment older = TestDataBuilder.buildPublishedComment("latest_2", "内容2", 70, 75);
            latest.setCreatedAt(LocalDateTime.now());
            older.setCreatedAt(LocalDateTime.now().minusHours(1));

            List<Comment> mockComments = Arrays.asList(latest, older);

            when(commentRepository.findLatestComments(eq(TestDataBuilder.TEST_CONTENT_ID), any(Pageable.class)))
                    .thenReturn(mockComments);

            List<Comment> result = recommendService.getLatestComments(
                    TestDataBuilder.TEST_CONTENT_ID, 10);

            assertEquals(2, result.size(), "应返回2条评论");
            assertEquals("latest_1", result.get(0).getCommentId(),
                    "最新评论应排在前面");
        }

        @Test
        @DisplayName("优质评论排序 - 按质量分数降序")
        void testQualityCommentsSorting() {
            List<Comment> publishedComments = Arrays.asList(
                    mediumQualityComment,
                    lowQualityComment,
                    highQualityComment
            );

            when(commentRepository.findByContentIdAndCommentStatus(
                    eq(TestDataBuilder.TEST_CONTENT_ID), eq("published")))
                    .thenReturn(publishedComments);

            List<Comment> result = recommendService.getQualityComments(
                    TestDataBuilder.TEST_CONTENT_ID, 10);

            assertEquals(3, result.size(), "应返回3条评论");
            assertEquals(highQualityComment.getCommentId(), result.get(0).getCommentId(),
                    "高质量评论应排在第一");
            assertEquals(90, result.get(0).getQualityScore(),
                    "第一条评论质量分数应为90");
            assertEquals(70, result.get(1).getQualityScore(),
                    "第二条评论质量分数应为70");
            assertEquals(35, result.get(2).getQualityScore(),
                    "第三条评论质量分数应为35");
        }

        @Test
        @DisplayName("正面评论筛选 - 只返回积极情感评论")
        void testPositiveCommentsFiltering() {
            List<Comment> publishedComments = Arrays.asList(
                    highQualityComment,
                    mediumQualityComment,
                    lowQualityComment
            );

            when(commentRepository.findByContentIdAndCommentStatus(
                    eq(TestDataBuilder.TEST_CONTENT_ID), eq("published")))
                    .thenReturn(publishedComments);

            SentimentAnalysis positiveAnalysis = TestDataBuilder.buildSentimentAnalysis(
                    TestDataBuilder.TEST_COMMENT_ID, "positive", 0.90);
            SentimentAnalysis neutralAnalysis = TestDataBuilder.buildSentimentAnalysis(
                    TestDataBuilder.TEST_COMMENT_ID_2, "neutral", 0.50);
            SentimentAnalysis negativeAnalysis = TestDataBuilder.buildSentimentAnalysis(
                    TestDataBuilder.TEST_COMMENT_ID_3, "negative", 0.20);

            when(sentimentAnalysisRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(positiveAnalysis));
            when(sentimentAnalysisRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID_2))
                    .thenReturn(Optional.of(neutralAnalysis));
            when(sentimentAnalysisRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID_3))
                    .thenReturn(Optional.of(negativeAnalysis));

            List<Comment> result = recommendService.getPositiveComments(
                    TestDataBuilder.TEST_CONTENT_ID, 10);

            assertEquals(1, result.size(), "应只返回1条正面评论");
            assertEquals(highQualityComment.getCommentId(), result.get(0).getCommentId(),
                    "应返回高质量正面评论");
        }

        @Test
        @DisplayName("推荐排序边界 - 空列表处理")
        void testRecommendSorting_EmptyList() {
            when(commentRepository.findByContentIdAndCommentStatus(
                    eq("empty_content"), eq("published")))
                    .thenReturn(new ArrayList<>());

            List<Comment> result = recommendService.getQualityComments("empty_content", 10);

            assertTrue(result.isEmpty(), "空内容应返回空列表");
        }

        @Test
        @DisplayName("推荐排序边界 - 限制数量")
        void testRecommendSorting_Limit() {
            List<Comment> manyComments = Arrays.asList(
                    highQualityComment,
                    mediumQualityComment,
                    lowQualityComment
            );

            when(commentRepository.findByContentIdAndCommentStatus(
                    eq(TestDataBuilder.TEST_CONTENT_ID), eq("published")))
                    .thenReturn(manyComments);

            List<Comment> result = recommendService.getQualityComments(
                    TestDataBuilder.TEST_CONTENT_ID, 2);

            assertEquals(2, result.size(), "应只返回2条评论");
            assertEquals(highQualityComment.getCommentId(), result.get(0).getCommentId(),
                    "高质量评论应排在第一");
        }
    }

    @Nested
    @DisplayName("推荐位置更新测试")
    class RecommendPositionTests {

        @Test
        @DisplayName("推荐位置更新 - 按推荐分数排序")
        void testUpdateRecommendScores_PositionSorting() {
            RecommendRecord record1 = TestDataBuilder.buildRecommendRecord(
                    "rec_1", TestDataBuilder.TEST_COMMENT_ID, 90, 0);
            RecommendRecord record2 = TestDataBuilder.buildRecommendRecord(
                    "rec_2", TestDataBuilder.TEST_COMMENT_ID_2, 75, 0);
            RecommendRecord record3 = TestDataBuilder.buildRecommendRecord(
                    "rec_3", TestDataBuilder.TEST_COMMENT_ID_3, 85, 0);

            List<RecommendRecord> records = Arrays.asList(record1, record2, record3);

            when(recommendRecordRepository.findByContentId(TestDataBuilder.TEST_CONTENT_ID))
                    .thenReturn(records);

            recommendService.updateRecommendScores(TestDataBuilder.TEST_CONTENT_ID);

            verify(recommendRecordRepository, times(3)).save(any(RecommendRecord.class));
            assertEquals(1, record1.getRecommendPosition(), "最高分数应排第1");
            assertEquals(2, record3.getRecommendPosition(), "第二高分应排第2");
            assertEquals(3, record2.getRecommendPosition(), "最低分应排第3");
        }

        @Test
        @DisplayName("推荐位置更新 - 空记录处理")
        void testUpdateRecommendScores_EmptyRecords() {
            when(recommendRecordRepository.findByContentId("empty_content"))
                    .thenReturn(new ArrayList<>());

            recommendService.updateRecommendScores("empty_content");

            verify(recommendRecordRepository, never()).save(any(RecommendRecord.class));
        }

        @Test
        @DisplayName("重新计算推荐分数 - 更新评论推荐分数")
        void testRecalculateRecommendScore_UpdateComment() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateRecommendId)
                        .thenReturn("recommend_recalc_001");

                Comment comment = TestDataBuilder.buildPublishedComment(
                        TestDataBuilder.TEST_COMMENT_ID,
                        TestDataBuilder.NORMAL_COMMENT,
                        80, 70
                );

                SentimentAnalysis sentimentAnalysis = TestDataBuilder.buildSentimentAnalysis(
                        TestDataBuilder.TEST_COMMENT_ID, "positive", 0.85);

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(comment));
                when(sentimentAnalysisRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(sentimentAnalysis));
                when(recommendRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.empty());

                recommendService.recalculateRecommendScore(TestDataBuilder.TEST_COMMENT_ID);

                verify(commentRepository, times(1)).save(comment);
                verify(recommendRecordRepository, times(1)).save(any(RecommendRecord.class));
            }
        }

        @Test
        @DisplayName("重新计算推荐分数 - 评论不存在")
        void testRecalculateRecommendScore_CommentNotFound() {
            when(commentRepository.findById("non_existing"))
                    .thenReturn(Optional.empty());

            recommendService.recalculateRecommendScore("non_existing");

            verify(commentRepository, never()).save(any(Comment.class));
            verify(recommendRecordRepository, never()).save(any(RecommendRecord.class));
        }

        @Test
        @DisplayName("重新计算推荐分数 - 无质量分数")
        void testRecalculateRecommendScore_NoQualityScore() {
            Comment comment = TestDataBuilder.buildPublishedComment(
                    TestDataBuilder.TEST_COMMENT_ID,
                    TestDataBuilder.NORMAL_COMMENT,
                    null, 0
            );

            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(comment));

            recommendService.recalculateRecommendScore(TestDataBuilder.TEST_COMMENT_ID);

            verify(commentRepository, never()).save(any(Comment.class));
            verify(recommendRecordRepository, never()).save(any(RecommendRecord.class));
        }
    }

    @Nested
    @DisplayName("推荐记录管理测试")
    class RecommendRecordTests {

        @Test
        @DisplayName("创建推荐记录 - 新记录")
        void testCreateRecommendRecord_NewRecord() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateRecommendId)
                        .thenReturn("recommend_new_001");

                when(recommendRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.empty());

                recommendService.createRecommendRecord(
                        highQualityComment, 85, 90, 0.85);

                verify(recommendRecordRepository, times(1)).save(any(RecommendRecord.class));
                idGenerator.verify(IdGenerator::generateRecommendId, times(1));
            }
        }

        @Test
        @DisplayName("创建推荐记录 - 更新已有记录")
        void testCreateRecommendRecord_UpdateExisting() {
            RecommendRecord existing = TestDataBuilder.buildRecommendRecord(
                    "rec_existing", TestDataBuilder.TEST_COMMENT_ID, 70, 2);

            when(recommendRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existing));

            recommendService.createRecommendRecord(
                    highQualityComment, 85, 90, 0.85);

            verify(recommendRecordRepository, times(1)).save(existing);
            assertEquals(85, existing.getRecommendScore(), "推荐分数应更新");
            assertEquals(90, existing.getQualityFactor(), "质量因素应更新");
            assertEquals(85, existing.getSentimentFactor(), "情感因素应更新");
        }

        @Test
        @DisplayName("获取评论推荐信息")
        void testGetCommentRecommendation() {
            RecommendRecord record = TestDataBuilder.buildRecommendRecord(
                    "rec_info_001", TestDataBuilder.TEST_COMMENT_ID, 85, 3);

            when(recommendRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(record));
            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(highQualityComment));

            Map<String, Object> result = recommendService.getCommentRecommendation(
                    TestDataBuilder.TEST_COMMENT_ID);

            assertEquals("rec_info_001", result.get("recommend_id"));
            assertEquals(85, result.get("recommend_score"));
            assertEquals(3, result.get("recommend_position"));
            assertEquals(TestDataBuilder.TEST_COMMENT_ID, result.get("comment_id"));
            assertEquals(90, result.get("quality_score"));
        }

        @Test
        @DisplayName("获取推荐排行 - 包含所有类型")
        void testGetCommentRanking() {
            when(commentRepository.findRecommendedComments(anyString(), any(Pageable.class)))
                    .thenReturn(Arrays.asList(highQualityComment));
            when(commentRepository.findHotComments(anyString(), any(Pageable.class)))
                    .thenReturn(Arrays.asList(mediumQualityComment));
            when(commentRepository.findLatestComments(anyString(), any(Pageable.class)))
                    .thenReturn(Arrays.asList(lowQualityComment));
            when(commentRepository.findByContentIdAndCommentStatus(anyString(), eq("published")))
                    .thenReturn(Arrays.asList(highQualityComment, mediumQualityComment));

            Map<String, Object> result = recommendService.getCommentRanking(
                    TestDataBuilder.TEST_CONTENT_ID);

            assertNotNull(result.get("recommended"));
            assertNotNull(result.get("hot"));
            assertNotNull(result.get("latest"));
            assertNotNull(result.get("quality"));
            assertNotNull(result.get("positive"));
        }
    }
}
