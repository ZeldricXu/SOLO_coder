package com.reviewsystem.service;

import com.reviewsystem.config.QueueConfig;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.SentimentAnalysis;
import com.reviewsystem.queue.SentimentTask;
import com.reviewsystem.queue.RedisQueueService;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.SentimentAnalysisRepository;
import com.reviewsystem.util.IdGenerator;
import com.reviewsystem.util.SentimentAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

@Service
public class SentimentService {

    private static final Logger logger = LoggerFactory.getLogger(SentimentService.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Autowired
    private SentimentAnalyzer sentimentAnalyzer;

    @Resource
    private RedisQueueService redisQueueService;

    @Resource
    private QueueConfig queueConfig;

    public Map<String, Object> submitSentimentTask(String commentId, String content) {
        Map<String, Object> result = new HashMap<>();

        SentimentTask task = new SentimentTask();
        task.setTaskId(IdGenerator.generateSentimentId());
        task.setCommentId(commentId);
        task.setContent(content);

        String queueName = queueConfig.getSentiment().getName();
        boolean submitted = redisQueueService.pushTask(queueName, task);

        if (submitted) {
            result.put("success", true);
            result.put("task_id", task.getTaskId());
            result.put("comment_id", commentId);
            result.put("message", "情感分析任务已提交");
            logger.info("情感分析任务已入队: commentId={}, taskId={}", commentId, task.getTaskId());
        } else {
            result.put("success", false);
            result.put("message", "情感分析任务提交失败");
            logger.error("情感分析任务入队失败: commentId={}", commentId);
        }

        return result;
    }

    @Transactional
    public void executeSentimentAnalysis(SentimentTask task) {
        String commentId = task.getCommentId();
        String content = task.getContent();

        SentimentAnalyzer.SentimentResult result = sentimentAnalyzer.analyze(content);

        Optional<SentimentAnalysis> existing = sentimentAnalysisRepository.findByCommentId(commentId);

        SentimentAnalysis analysis;
        if (existing.isPresent()) {
            analysis = existing.get();
        } else {
            analysis = new SentimentAnalysis();
            analysis.setSentimentId(IdGenerator.generateSentimentId());
            analysis.setCommentId(commentId);
        }

        analysis.setSentimentType(result.getSentimentType());
        analysis.setSentimentScore(result.getSentimentScore());
        analysis.setPositiveScore(result.getPositiveScore());
        analysis.setNegativeScore(result.getNegativeScore());
        analysis.setNeutralScore(result.getNeutralScore());
        analysis.setSentimentKeywords(result.getSentimentKeywords());
        sentimentAnalysisRepository.save(analysis);

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isPresent()) {
            Comment comment = commentOpt.get();
            if (comment.getCommentStatus() != null && "published".equals(comment.getCommentStatus())) {
                String contentType = comment.getContentId() != null ? comment.getContentId() : "default";
                int recommendScore = calculateSentimentBasedRecommend(comment, result, contentType);
                comment.setRecommendScore(recommendScore);
                commentRepository.save(comment);
            }
        }

        logger.info("情感分析执行完成: commentId={}, type={}, score={}",
                commentId, result.getSentimentType(), result.getSentimentScore());
    }

    private int calculateSentimentBasedRecommend(Comment comment,
                                                 SentimentAnalyzer.SentimentResult sentimentResult,
                                                 String contentType) {
        int qualityScore = comment.getQualityScore() != null ? comment.getQualityScore() : 50;

        int sentimentFactor = (int) (sentimentResult.getSentimentScore() * 100);
        int heatScore = 0;
        if (comment.getLikeCount() != null && comment.getReplyCount() != null) {
            heatScore = Math.min(30, comment.getLikeCount() * 2 + comment.getReplyCount() * 3);
        }

        return (int) (qualityScore * 0.4 + sentimentFactor * 0.3 + heatScore * 0.3);
    }

    public SentimentAnalysis getSentimentAnalysis(String commentId) {
        return sentimentAnalysisRepository.findByCommentId(commentId).orElse(null);
    }

    public List<SentimentAnalysis> getSentimentHistory(String commentId) {
        return sentimentAnalysisRepository.findByCommentIdOrderByAnalyzedAtDesc(commentId);
    }

    public Map<String, Object> getSentimentStats(String contentId) {
        Map<String, Object> stats = new HashMap<>();

        List<Comment> comments = commentRepository.findByContentIdAndCommentStatus(contentId, "published");
        int positiveCount = 0;
        int negativeCount = 0;
        int neutralCount = 0;
        double totalScore = 0;
        int analyzedCount = 0;

        for (Comment comment : comments) {
            Optional<SentimentAnalysis> sentimentOpt = sentimentAnalysisRepository.findByCommentId(comment.getCommentId());
            if (sentimentOpt.isPresent()) {
                SentimentAnalysis sa = sentimentOpt.get();
                analyzedCount++;
                totalScore += sa.getSentimentScore();

                switch (sa.getSentimentType()) {
                    case "positive":
                        positiveCount++;
                        break;
                    case "negative":
                        negativeCount++;
                        break;
                    default:
                        neutralCount++;
                }
            }
        }

        stats.put("total_comments", comments.size());
        stats.put("analyzed_count", analyzedCount);
        stats.put("positive_count", positiveCount);
        stats.put("negative_count", negativeCount);
        stats.put("neutral_count", neutralCount);
        stats.put("avg_sentiment_score", analyzedCount > 0 ? totalScore / analyzedCount : 0);
        stats.put("positive_ratio", analyzedCount > 0 ? (double) positiveCount / analyzedCount : 0);
        stats.put("negative_ratio", analyzedCount > 0 ? (double) negativeCount / analyzedCount : 0);

        stats.put("queue_size", redisQueueService.getQueueSize(queueConfig.getSentiment().getName()));

        return stats;
    }

    public Map<String, Object> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("pending_queue", redisQueueService.getQueueSize(queueConfig.getSentiment().getName()));
        status.put("processing_queue", redisQueueService.getQueueSize(queueConfig.getSentiment().getName() + ":processing"));
        status.put("dead_queue", redisQueueService.getQueueSize(queueConfig.getSentiment().getName() + ":dead"));
        return status;
    }
}
