package com.reviewsystem.service;

import com.reviewsystem.config.RecommendWeightConfig;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.RecommendRecord;
import com.reviewsystem.model.SentimentAnalysis;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.RecommendRecordRepository;
import com.reviewsystem.repository.SentimentAnalysisRepository;
import com.reviewsystem.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class RecommendService {

    private static final Logger logger = LoggerFactory.getLogger(RecommendService.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private RecommendRecordRepository recommendRecordRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Resource
    private RecommendWeightConfig recommendWeightConfig;

    public int calculateRecommendScore(int qualityScore, double sentimentScore) {
        return calculateRecommendScore(qualityScore, sentimentScore, "default");
    }

    public int calculateRecommendScore(int qualityScore, double sentimentScore, String contentType) {
        RecommendWeightConfig.WeightItem weights = recommendWeightConfig.getWeightByContentType(contentType);

        int qualityFactor = qualityScore;
        int sentimentFactor = (int) (sentimentScore * 100);

        int totalScore = (int) (qualityFactor * weights.getQuality() +
                sentimentFactor * weights.getSentiment());

        return Math.min(100, Math.max(0, totalScore));
    }

    public int calculateRecommendScoreWithHeat(Comment comment, int qualityScore, double sentimentScore) {
        return calculateRecommendScoreWithHeat(comment, qualityScore, sentimentScore,
                comment.getContentId() != null ? comment.getContentId() : "default");
    }

    public int calculateRecommendScoreWithHeat(Comment comment, int qualityScore,
                                                double sentimentScore, String contentType) {
        RecommendWeightConfig.WeightItem weights = recommendWeightConfig.getWeightByContentType(contentType);

        int qualityFactor = qualityScore;
        int sentimentFactor = (int) (sentimentScore * 100);

        int heatScore = 0;
        if (comment.getLikeCount() != null && comment.getReplyCount() != null) {
            heatScore = Math.min(30, comment.getLikeCount() * 2 + comment.getReplyCount() * 3);
        }

        long hoursSinceCreated = ChronoUnit.HOURS.between(comment.getCreatedAt(), LocalDateTime.now());
        double timeDecay = Math.max(0.5, 1.0 - hoursSinceCreated / 168.0);
        int timeFactor = (int) (timeDecay * 100);

        int totalScore = (int) (qualityFactor * weights.getQuality() +
                sentimentFactor * weights.getSentiment() +
                heatScore * weights.getHeat() +
                timeFactor * weights.getTime());

        return Math.min(100, Math.max(0, totalScore));
    }

    public RecommendWeightConfig.WeightItem getWeightConfig(String contentType) {
        return recommendWeightConfig.getWeightByContentType(contentType);
    }

    public Map<String, RecommendWeightConfig.WeightItem> getAllWeightConfigs() {
        return recommendWeightConfig.getWeights();
    }

    @Transactional
    @CacheEvict(value = "recommendRanking", key = "#comment.contentId ?: 'default'")
    public void createRecommendRecord(Comment comment, int recommendScore,
                                       int qualityScore, double sentimentScore) {
        Optional<RecommendRecord> existing = recommendRecordRepository.findByCommentId(comment.getCommentId());

        RecommendRecord record;
        if (existing.isPresent()) {
            record = existing.get();
        } else {
            record = new RecommendRecord();
            record.setRecommendId(IdGenerator.generateRecommendId());
            record.setCommentId(comment.getCommentId());
            record.setContentId(comment.getContentId());
        }

        record.setRecommendType("quality");
        record.setRecommendScore(recommendScore);
        record.setQualityFactor(qualityScore);
        record.setHeatFactor(0);
        record.setTimeFactor(100);
        record.setSentimentFactor((int) (sentimentScore * 100));
        record.setRecommendPosition(0);

        recommendRecordRepository.save(record);
    }

    @Cacheable(value = "recommend", key = "'content:' + #contentId + ':limit:' + #limit")
    public List<Comment> getRecommendedComments(String contentId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return commentRepository.findRecommendedComments(contentId, pageable);
    }

    @Cacheable(value = "recommend", key = "'hot:' + #contentId + ':limit:' + #limit")
    public List<Comment> getHotComments(String contentId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return commentRepository.findHotComments(contentId, pageable);
    }

    @Cacheable(value = "recommend", key = "'latest:' + #contentId + ':limit:' + #limit")
    public List<Comment> getLatestComments(String contentId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return commentRepository.findLatestComments(contentId, pageable);
    }

    @Cacheable(value = "recommend", key = "'quality:' + #contentId + ':limit:' + #limit")
    public List<Comment> getQualityComments(String contentId, int limit) {
        List<Comment> publishedComments = commentRepository.findByContentIdAndCommentStatus(
                contentId, "published");

        publishedComments.sort((c1, c2) -> {
            Integer q1 = c1.getQualityScore() != null ? c1.getQualityScore() : 0;
            Integer q2 = c2.getQualityScore() != null ? c2.getQualityScore() : 0;
            return q2.compareTo(q1);
        });

        return publishedComments.size() > limit ? publishedComments.subList(0, limit) : publishedComments;
    }

    @Cacheable(value = "recommend", key = "'positive:' + #contentId + ':limit:' + #limit")
    public List<Comment> getPositiveComments(String contentId, int limit) {
        List<Comment> publishedComments = commentRepository.findByContentIdAndCommentStatus(
                contentId, "published");

        List<Comment> positiveComments = new ArrayList<>();
        for (Comment comment : publishedComments) {
            Optional<SentimentAnalysis> sentimentOpt = sentimentAnalysisRepository.findByCommentId(comment.getCommentId());
            if (sentimentOpt.isPresent() && "positive".equals(sentimentOpt.get().getSentimentType())) {
                positiveComments.add(comment);
            }
            if (positiveComments.size() >= limit) {
                break;
            }
        }

        return positiveComments;
    }

    @Cacheable(value = "recommendRanking", key = "#contentId ?: 'default'")
    public Map<String, Object> getCommentRanking(String contentId) {
        Map<String, Object> result = new HashMap<>();

        result.put("recommended", getRecommendedComments(contentId, 10));
        result.put("hot", getHotComments(contentId, 10));
        result.put("latest", getLatestComments(contentId, 10));
        result.put("quality", getQualityComments(contentId, 10));
        result.put("positive", getPositiveComments(contentId, 10));

        RecommendWeightConfig.WeightItem weights = getWeightConfig(contentId);
        Map<String, Object> weightInfo = new HashMap<>();
        weightInfo.put("content_type", contentId != null ? contentId : "default");
        weightInfo.put("quality_weight", weights.getQuality());
        weightInfo.put("sentiment_weight", weights.getSentiment());
        weightInfo.put("heat_weight", weights.getHeat());
        weightInfo.put("time_weight", weights.getTime());
        result.put("weight_config", weightInfo);

        return result;
    }

    @Transactional
    @CacheEvict(value = "recommendRanking", key = "#contentId ?: 'default'")
    public void updateRecommendScores(String contentId) {
        List<RecommendRecord> records = recommendRecordRepository.findByContentId(contentId);

        List<RecommendRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort((r1, r2) -> Integer.compare(r2.getRecommendScore(), r1.getRecommendScore()));

        for (int i = 0; i < sortedRecords.size(); i++) {
            RecommendRecord record = sortedRecords.get(i);
            record.setRecommendPosition(i + 1);
            recommendRecordRepository.save(record);
        }

        logger.info("已更新推荐排序: contentId={}, total={}", contentId, records.size());
    }

    @Transactional
    @CacheEvict(value = {"recommend", "recommendRanking"}, allEntries = true)
    public void recalculateRecommendScore(String commentId) {
        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            return;
        }

        Comment comment = commentOpt.get();
        if (comment.getQualityScore() == null) {
            return;
        }

        int qualityScore = comment.getQualityScore();

        Optional<SentimentAnalysis> sentimentOpt = sentimentAnalysisRepository.findByCommentId(commentId);
        double sentimentScore = sentimentOpt.map(SentimentAnalysis::getSentimentScore).orElse(0.5);

        String contentType = comment.getContentId() != null ? comment.getContentId() : "default";
        int newRecommendScore = calculateRecommendScoreWithHeat(comment, qualityScore, sentimentScore, contentType);
        comment.setRecommendScore(newRecommendScore);
        commentRepository.save(comment);

        createRecommendRecord(comment, newRecommendScore, qualityScore, sentimentScore);

        logger.debug("已重新计算推荐分数: commentId={}, contentType={}, score={}",
                commentId, contentType, newRecommendScore);
    }

    @CacheEvict(value = {"recommend", "recommendRanking"}, allEntries = true)
    public void clearCache() {
        logger.info("推荐缓存已清除");
    }

    public Map<String, Object> getCommentRecommendation(String commentId) {
        Map<String, Object> result = new HashMap<>();

        Optional<RecommendRecord> recordOpt = recommendRecordRepository.findByCommentId(commentId);
        if (recordOpt.isPresent()) {
            RecommendRecord record = recordOpt.get();
            result.put("recommend_id", record.getRecommendId());
            result.put("recommend_score", record.getRecommendScore());
            result.put("recommend_type", record.getRecommendType());
            result.put("recommend_position", record.getRecommendPosition());
            result.put("quality_factor", record.getQualityFactor());
            result.put("heat_factor", record.getHeatFactor());
            result.put("time_factor", record.getTimeFactor());
            result.put("sentiment_factor", record.getSentimentFactor());
            result.put("calculated_at", record.getCalculatedAt());
        }

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isPresent()) {
            Comment comment = commentOpt.get();
            result.put("comment_id", commentId);
            result.put("current_recommend_score", comment.getRecommendScore());
            result.put("quality_score", comment.getQualityScore());

            String contentType = comment.getContentId() != null ? comment.getContentId() : "default";
            RecommendWeightConfig.WeightItem weights = getWeightConfig(contentType);
            result.put("content_type", contentType);
            result.put("quality_weight", weights.getQuality());
            result.put("sentiment_weight", weights.getSentiment());
            result.put("heat_weight", weights.getHeat());
            result.put("time_weight", weights.getTime());
        }

        return result;
    }
}
